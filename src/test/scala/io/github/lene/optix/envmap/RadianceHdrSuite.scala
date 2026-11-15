package io.github.lene.optix.envmap

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets.ISO_8859_1
import java.nio.file.Files

import io.github.lene.optix.TestUtilities
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The pure-JVM Radiance reader against hand-made files: flat and run-length encoded scanlines,
  * RGBE decoding, and the files the native loader (stb_image) rejects. Needs no GPU.
  */
class RadianceHdrSuite extends AnyFlatSpec with Matchers:

  private def header(width: Int, height: Int, format: String = "FORMAT=32-bit_rle_rgbe", magic: String = "#?RADIANCE",
      resolution: Option[String] = None): Array[Byte] =
    s"$magic\n$format\n\n${resolution.getOrElse(s"-Y $height +X $width")}\n".getBytes(ISO_8859_1)

  private def bytes(parts: Array[Byte]*): Array[Byte] =
    val out = new ByteArrayOutputStream()
    parts.foreach(p => out.write(p))
    out.toByteArray

  private def ints(values: Int*): Array[Byte] = values.map(_.toByte).toArray

  /** One scanline in the "new" run-length format: marker, width, then each channel as runs. */
  private def rleScanline(pixels: Seq[Seq[Int]]): Array[Byte] =
    val width = pixels.length
    val out = new ByteArrayOutputStream()
    out.write(ints(2, 2, width >> 8, width & 0xff))
    (0 until 4).foreach: channel =>
      out.write(encodeChannel(pixels.map(_(channel))))
    out.toByteArray

  /** Runs of 3 or more equal values as (128 + n, value), the rest as literal chunks. */
  private def encodeChannel(values: Seq[Int]): Array[Byte] =
    val out = new ByteArrayOutputStream()
    def literal(chunk: Seq[Int]): Unit =
      chunk.grouped(128).foreach: part =>
        out.write(part.length)
        part.foreach(out.write)
    @scala.annotation.tailrec
    def loop(rest: Seq[Int], pending: Seq[Int]): Unit =
      if rest.isEmpty then literal(pending)
      else
        val (run, after) = rest.span(_ == rest.head)
        if run.length >= 3 then
          literal(pending)
          run.grouped(127).foreach: part =>
            out.write(128 + part.length)
            out.write(part.head)
          loop(after, Seq.empty)
        else loop(after, pending ++ run)
    loop(values, Seq.empty)
    out.toByteArray

  private val width = 16
  private val height = 3
  // a few pixels with runs, none starting with the marker bytes 2, 2
  private def testPixel(x: Int, y: Int): Seq[Int] =
    if x < 8 then Seq(100 + y, 50, 25, 128) else Seq(200, 10 * x, 5, 130 + y)

  private val expected: Array[Float] =
    (for y <- 0 until height; x <- 0 until width; c <- 0 until 3
      yield (testPixel(x, y)(c) + 0.5f) * math.pow(2.0, testPixel(x, y)(3) - 136).toFloat).toArray

  "A flat file" should "decode to RGBE values the way stb_image does: (mantissa + 0.5) * 2^(exponent - 136)" in:
    val flat = for y <- 0 until height; x <- 0 until width yield ints(testPixel(x, y)*)
    val image = RadianceHdr.decode(bytes((header(width, height) +: flat)*))
    (image.width, image.height) shouldBe ((width, height))
    image.rgb shouldBe expected

  it should "decode 128, 128, 128, 129 as 1.0039 (the centre of its mantissa bin), exponent 0 as black" in:
    val image = RadianceHdr.decode(bytes(header(3, 1), ints(128, 128, 128, 129, 0, 0, 0, 0, 64, 128, 32, 129)))
    val one = 128.5f / 128f
    image.rgb shouldBe Array(one, one, one, 0f, 0f, 0f, 64.5f / 128f, one, 32.5f / 128f)

  it should "read an image narrower than 8 pixels, which is never run-length encoded" in:
    val image = RadianceHdr.decode(bytes(header(2, 2), ints(128, 128, 128, 129, 64, 64, 64, 129, 1, 2, 3, 129, 4, 5, 6, 129)))
    image.rgb should have length 12

  it should "read what TestUtilities.writeHdr writes, to the precision of RGBE" in:
    val tmp = Files.createTempFile("radiance_suite_", ".hdr")
    try
      TestUtilities.writeHdr(tmp, 32, 16, (x, y) => (x * 0.1f, y * 0.2f, 0.5f))
      val image = RadianceHdr.read(tmp)
      (image.width, image.height) shouldBe ((32, 16))
      val i = (5 * 32 + 20) * 3
      image.rgb(i) shouldBe 2.0f +- 0.01f
      image.rgb(i + 1) shouldBe 1.0f +- 0.01f
      image.rgb(i + 2) shouldBe 0.5f +- 0.01f
    finally Files.deleteIfExists(tmp)

  "A run-length encoded file" should "decode to the same pixels as the flat file" in:
    val scanlines = for y <- 0 until height yield rleScanline((0 until width).map(testPixel(_, y)))
    val image = RadianceHdr.decode(bytes((header(width, height) +: scanlines)*))
    image.rgb shouldBe expected

  it should "give way to flat scanlines from the first flat one on, as stb_image does" in:
    val scanlines = for y <- 0 until height yield
      if y == 0 then rleScanline((0 until width).map(testPixel(_, y)))
      else bytes((0 until width).map(x => ints(testPixel(x, y)*))*)
    RadianceHdr.decode(bytes((header(width, height) +: scanlines)*)).rgb shouldBe expected

  it should "be rejected when the scanline length is not the image width" in:
    val wrong = rleScanline((0 until width + 1).map(testPixel(_, 0)))
    an[IllegalArgumentException] should be thrownBy RadianceHdr.decode(bytes(header(width, 1), wrong))

  it should "be rejected when a run is empty or goes past the end of the scanline" in:
    val tooLong = ints(2, 2, 0, width) ++ ints(128 + width + 1, 7)
    an[IllegalArgumentException] should be thrownBy RadianceHdr.decode(bytes(header(width, 1), tooLong))
    val empty = ints(2, 2, 0, width) ++ ints(0, 7)
    an[IllegalArgumentException] should be thrownBy RadianceHdr.decode(bytes(header(width, 1), empty))

  "A file the native loader rejects" should "be rejected: wrong signature" in:
    an[IllegalArgumentException] should be thrownBy
      RadianceHdr.decode(bytes(header(1, 1, magic = "#?PNG"), ints(1, 1, 1, 129)))

  it should "be rejected: no FORMAT line" in:
    an[IllegalArgumentException] should be thrownBy
      RadianceHdr.decode(bytes(header(1, 1, format = "EXPOSURE=1"), ints(1, 1, 1, 129)))

  it should "be rejected: an orientation other than -Y height +X width" in:
    an[IllegalArgumentException] should be thrownBy
      RadianceHdr.decode(bytes(header(1, 1, resolution = Some("+Y 1 +X 1")), ints(1, 1, 1, 129)))

  it should "be rejected: more than 8192 pixels a side" in:
    an[IllegalArgumentException] should be thrownBy
      RadianceHdr.decode(bytes(header(8193, 1), new Array[Byte](4 * 8193)))
    an[IllegalArgumentException] should be thrownBy
      RadianceHdr.decode(bytes(header(1, 8193), new Array[Byte](4 * 8193)))

  it should "be rejected: pixel data that ends early" in:
    an[IllegalArgumentException] should be thrownBy
      RadianceHdr.decode(bytes(header(4, 2), ints(1, 1, 1, 129, 1, 1, 1, 129)))
    an[IllegalArgumentException] should be thrownBy
      RadianceHdr.decode(bytes(header(width, 2), rleScanline((0 until width).map(testPixel(_, 0)))))

  it should "be rejected: a header that never ends" in:
    an[IllegalArgumentException] should be thrownBy RadianceHdr.decode("#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n".getBytes(ISO_8859_1))
