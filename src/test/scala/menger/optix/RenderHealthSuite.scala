package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RenderHealthSuite extends AnyFlatSpec with Matchers:

  private def fill(width: Int, height: Int, r: Int, g: Int, b: Int): Array[Byte] =
    val total = width * height
    val out   = new Array[Byte](total * 4)
    (0 until total).foreach: i =>
      val base = i * 4
      out(base)     = r.toByte
      out(base + 1) = g.toByte
      out(base + 2) = b.toByte
      out(base + 3) = 0xFF.toByte
    out

  "checkRgba" should "flag an all-red buffer" in:
    val pixels = fill(8, 8, 255, 0, 0)
    val result = RenderHealth.checkRgba(pixels, 8, 8)
    result.isLeft shouldBe true
    result.swap.toOption.get should include("uniform")
    result.swap.toOption.get should include("RGB(255, 0, 0)")

  it should "flag an all-black buffer" in:
    val pixels = fill(4, 4, 0, 0, 0)
    val result = RenderHealth.checkRgba(pixels, 4, 4)
    result.isLeft shouldBe true

  it should "pass a buffer with diverse pixels" in:
    val pixels = new Array[Byte](16 * 16 * 4)
    (0 until 16 * 16).foreach: i =>
      val base = i * 4
      pixels(base)     = (i % 256).toByte
      pixels(base + 1) = ((i * 3) % 256).toByte
      pixels(base + 2) = ((i * 7) % 256).toByte
      pixels(base + 3) = 0xFF.toByte
    val result = RenderHealth.checkRgba(pixels, 16, 16)
    result shouldBe Right(())

  it should "pass when the uniform fraction is below threshold" in:
    val pixels = new Array[Byte](100 * 4)
    (0 until 100).foreach: i =>
      val base = i * 4
      val red  = i < 50
      pixels(base)     = (if red then 255 else 0).toByte
      pixels(base + 1) = 0.toByte
      pixels(base + 2) = (if red then 0 else 255).toByte
      pixels(base + 3) = 0xFF.toByte
    val result = RenderHealth.checkRgba(pixels, 10, 10)
    result shouldBe Right(())

  it should "respect channelEpsilon for near-uniform buffers" in:
    val pixels = new Array[Byte](100 * 4)
    (0 until 100).foreach: i =>
      val base   = i * 4
      val jitter = (i % 3) - 1
      pixels(base)     = (128 + jitter).toByte
      pixels(base + 1) = (128 + jitter).toByte
      pixels(base + 2) = (128 + jitter).toByte
      pixels(base + 3) = 0xFF.toByte
    // jitter spans -1..+1, so a tolerance of 2 covers all variation
    val result = RenderHealth.checkRgba(pixels, 10, 10, channelEpsilon = 2)
    result.isLeft shouldBe true

  it should "reject empty or wrongly-sized buffers" in:
    an[IllegalArgumentException] should be thrownBy
      RenderHealth.checkRgba(new Array[Byte](0), 0, 0)
    an[IllegalArgumentException] should be thrownBy
      RenderHealth.checkRgba(new Array[Byte](16), 4, 4) // need 64 bytes
