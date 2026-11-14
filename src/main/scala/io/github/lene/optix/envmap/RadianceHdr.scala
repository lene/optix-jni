package io.github.lene.optix.envmap

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import scala.annotation.tailrec

/** An equirectangular HDR image as linear RGB floats, row 0 at the top (+y), `width * height * 3`
  * values in row-major order.
  */
final case class HdrImage(width: Int, height: Int, rgb: Array[Float]):
  require(width > 0 && height > 0, s"image size must be positive, got ${width}x$height")
  require(rgb.length == width * height * 3, "rgb must hold width * height * 3 values")

/** Reads a Radiance `.hdr` (RGBE) file without a GPU or the native library, the way the native
  * loader (`HdrLoader.cpp`, which uses stb_image) reads it, so that directions measured from the
  * pixels agree with the ones the renderer shows and lights with.
  *
  * Same as stb_image, it takes only the first line `#?RADIANCE` or `#?RGBE`, a header with
  * `FORMAT=32-bit_rle_rgbe` ended by an empty line, and the resolution line `-Y height +X width`.
  * An image 8 to 32767 pixels wide is read as run-length encoded until the first scanline that
  * does not start with the RLE marker, and flat from then on; any other width is flat. A channel
  * value is `(mantissa + 0.5) * 2^(exponent - 136)` in float arithmetic (stb_image centres the
  * mantissa's bin, which is 0.2 % to 0.4 % brighter than `mantissa * 2^(exponent - 136)`), black
  * for exponent 0. Same as the native loader, it rejects an image over [[RadianceHdr.MaxDimension]]
  * pixels a side and data that ends early. A malformed file throws `IllegalArgumentException`, an
  * unreadable one `IOException`.
  */
object RadianceHdr:

  /** The largest width or height the native loader decodes. */
  val MaxDimension: Int = 8192

  private val Signatures = Set("#?RADIANCE", "#?RGBE")
  private val FormatLine = "FORMAT=32-bit_rle_rgbe"
  private val Resolution = raw"-Y\s+(\d+)\s+\+X\s+(\d+)\s*".r
  private val MinRleWidth = 8
  private val MaxRleWidth = 32767
  private val RleMarker = 2
  private val RleRunFlag = 128
  private val ExponentBias = 136

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def reject(message: String): Nothing = throw new IllegalArgumentException(message)

  private def check(ok: Boolean, message: => String): Unit = if !ok then reject(message)

  def read(path: Path): HdrImage = decode(Files.readAllBytes(path))

  def decode(bytes: Array[Byte]): HdrImage =
    val (width, height, dataStart) = parseHeader(bytes)
    val rgb = new Array[Float](width * height * 3)
    val startsRle = width >= MinRleWidth && width <= MaxRleWidth
    (0 until height).foldLeft((dataStart, startsRle)): (state, row) =>
      decodeScanline(bytes, state._1, state._2, width, rgb, row * width * 3)
    HdrImage(width, height, rgb)

  /** (width, height, offset of the first pixel byte). */
  private def parseHeader(bytes: Array[Byte]): (Int, Int, Int) =
    val (signature, afterSignature) = line(bytes, 0)
    check(Signatures.contains(signature), s"not a Radiance file: first line is '$signature'")
    val (hasFormat, afterHeader) = skipHeader(bytes, afterSignature, found = false)
    check(hasFormat, s"Radiance header has no $FormatLine line")
    val (resolution, dataStart) = line(bytes, afterHeader)
    resolution match
      case Resolution(h, w) =>
        check(h.length < 6 && w.length < 6, s"unreasonable image size '$resolution'")
        val (height, width) = (h.toInt, w.toInt)
        check(width > 0 && height > 0, s"image size must be positive, got '$resolution'")
        check(
          width <= MaxDimension && height <= MaxDimension,
          s"image ${width}x$height is over $MaxDimension pixels a side")
        (width, height, dataStart)
      case _ => reject(s"only '-Y height +X width' images are read, got '$resolution'")

  /** The line starting at `from` (without its '\n') and the offset after it. */
  private def line(bytes: Array[Byte], from: Int): (String, Int) =
    val end = bytes.indexOf('\n'.toByte, from)
    check(end >= 0, "Radiance header ends early")
    (new String(bytes, from, end - from, StandardCharsets.ISO_8859_1), end + 1)

  /** Skips header lines up to and including the empty one; whether a FORMAT line was among them. */
  @tailrec
  private def skipHeader(bytes: Array[Byte], from: Int, found: Boolean): (Boolean, Int) =
    val (text, next) = line(bytes, from)
    if text.isEmpty then (found, next) else skipHeader(bytes, next, found || text == FormatLine)

  /** Decodes the scanline starting at `pos` into `out` at `outOffset`; the offset after it and
    * whether the next scanline may still be run-length encoded (the first flat one ends that).
    */
  private def decodeScanline(
      bytes: Array[Byte], pos: Int, mayBeRle: Boolean, width: Int, out: Array[Float], outOffset: Int)
      : (Int, Boolean) =
    val isRle = mayBeRle && pos + 4 <= bytes.length &&
      (bytes(pos) & 0xff) == RleMarker && (bytes(pos + 1) & 0xff) == RleMarker &&
      (bytes(pos + 2) & 0x80) == 0
    if isRle then (decodeRle(bytes, pos, width, out, outOffset), true)
    else (decodeFlat(bytes, pos, width, out, outOffset), false)

  private def decodeFlat(
      bytes: Array[Byte], pos: Int, width: Int, out: Array[Float], outOffset: Int): Int =
    check(pos + 4 * width <= bytes.length, "Radiance file ends before its pixel data does")
    (0 until width).foreach: x =>
      val p = pos + 4 * x
      storePixel(bytes(p), bytes(p + 1), bytes(p + 2), bytes(p + 3), out, outOffset + 3 * x)
    pos + 4 * width

  private def decodeRle(
      bytes: Array[Byte], pos: Int, width: Int, out: Array[Float], outOffset: Int): Int =
    val length = ((bytes(pos + 2) & 0xff) << 8) | (bytes(pos + 3) & 0xff)
    check(length == width, s"RLE scanline of $length pixels in an image $width wide")
    val planes = new Array[Byte](4 * width)
    val end = (0 until 4).foldLeft(pos + 4): (p, channel) =>
      decodeRuns(bytes, p, planes, channel * width, width)
    (0 until width).foreach: x =>
      storePixel(planes(x), planes(width + x), planes(2 * width + x), planes(3 * width + x), out, outOffset + 3 * x)
    end

  /** Decodes the runs of one channel into `planes` from `planeStart`; the offset after them. */
  @tailrec
  private def decodeRuns(
      bytes: Array[Byte], pos: Int, planes: Array[Byte], planeStart: Int, width: Int,
      filled: Int = 0): Int =
    if filled >= width then pos
    else
      check(pos < bytes.length, "Radiance file ends before its pixel data does")
      val count = bytes(pos) & 0xff
      if count > RleRunFlag then
        val run = count - RleRunFlag
        check(pos + 1 < bytes.length, "Radiance file ends before its pixel data does")
        check(run <= width - filled, "bad RLE data: a run goes past the end of the scanline")
        java.util.Arrays.fill(planes, planeStart + filled, planeStart + filled + run, bytes(pos + 1))
        decodeRuns(bytes, pos + 2, planes, planeStart, width, filled + run)
      else
        check(count > 0 && count <= width - filled, "bad RLE data: empty run or a run past the end of the scanline")
        check(pos + 1 + count <= bytes.length, "Radiance file ends before its pixel data does")
        System.arraycopy(bytes, pos + 1, planes, planeStart + filled, count)
        decodeRuns(bytes, pos + 1 + count, planes, planeStart, width, filled + count)

  private def storePixel(r: Byte, g: Byte, b: Byte, e: Byte, out: Array[Float], at: Int): Unit =
    val exponent = e & 0xff
    val scale = if exponent == 0 then 0f else Math.scalb(1f, exponent - ExponentBias)
    out(at) = ((r & 0xff) + 0.5f) * scale
    out(at + 1) = ((g & 0xff) + 0.5f) * scale
    out(at + 2) = ((b & 0xff) + 0.5f) * scale
