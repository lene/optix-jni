package io.github.lene.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Pins `TestUtilities.rgbe`, the encoder behind `writeHdr`, to known Radiance RGBE values. */
class RgbeEncodingSuite extends AnyFlatSpec with Matchers:

  private def bytes(rgb: (Float, Float, Float)): Seq[Int] =
    TestUtilities.rgbe(rgb).toSeq.map(_ & 0xff)

  "rgbe" should "encode 1.0 as (128, 128, 128, 129), the value TextureSuite's old helper wrote" in:
    bytes((1.0f, 1.0f, 1.0f)) shouldBe Seq(128, 128, 128, 129)

  it should "encode 8.0 as (128, 128, 128, 132)" in:
    bytes((8.0f, 8.0f, 8.0f)) shouldBe Seq(128, 128, 128, 132)

  it should "encode black as all zeros" in:
    bytes((0.0f, 0.0f, 0.0f)) shouldBe Seq(0, 0, 0, 0)

  it should "share the exponent of the brightest channel" in:
    bytes((0.5f, 1.0f, 0.25f)) shouldBe Seq(64, 128, 32, 129)

  it should "never start a pixel with the RLE marker bytes 2,2" in:
    // Blue 1.0 sets the exponent (scale 128), so 2/128 gives red and green the mantissa 2.
    bytes((2.0f / 128, 2.0f / 128, 1.0f)).take(2) shouldBe Seq(3, 2)
