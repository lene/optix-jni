package io.github.lene.optix

import java.util.Optional

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OptiXDenoiserSuite extends AnyFlatSpec with Matchers:

  private def rgba(width: Int, height: Int, value: Float = 0.5f): Array[Float] =
    Array.tabulate(width * height * 4) { index =>
      if index % 4 == 3 then 1.0f else value
    }

  private def withDenoiser[T](
    guideAlbedo: Boolean = false,
    guideNormal: Boolean = false
  )(test: OptiXDenoiser => T): T =
    val denoiser = new OptiXDenoiser(guideAlbedo, guideNormal)
    try test(denoiser)
    finally denoiser.close()

  "OptiXDenoiser validation" should "reject invalid dimensions" in:
    withDenoiser() { denoiser =>
      an[IllegalArgumentException] should be thrownBy
        denoiser.denoise(DenoiseImage(0, 1, Array.empty[Float]))
    }

  it should "reject null image arrays" in:
    withDenoiser() { denoiser =>
      an[IllegalArgumentException] should be thrownBy
        denoiser.denoise(DenoiseImage(1, 1, null)) // scalafix:ok DisableSyntax.null
    }

  it should "reject wrong image lengths" in:
    withDenoiser() { denoiser =>
      an[IllegalArgumentException] should be thrownBy
        denoiser.denoise(DenoiseImage(2, 2, Array.fill(15)(0.0f)))
    }

  it should "reject non-finite image values" in:
    withDenoiser() { denoiser =>
      an[IllegalArgumentException] should be thrownBy
        denoiser.denoise(DenoiseImage(1, 1, Array(0.0f, Float.NaN, 0.0f, 1.0f)))
    }

  it should "reject missing required albedo guides" in:
    withDenoiser(guideAlbedo = true) { denoiser =>
      an[IllegalArgumentException] should be thrownBy
        denoiser.denoise(DenoiseImage(1, 1, rgba(1, 1)))
    }

  it should "reject missing required normal guides" in:
    withDenoiser(guideNormal = true) { denoiser =>
      an[IllegalArgumentException] should be thrownBy
        denoiser.denoise(DenoiseImage(1, 1, rgba(1, 1)))
    }

  it should "represent absent and present guides without Scala Option" in:
    val albedo = rgba(1, 1, 0.2f)
    val normal = rgba(1, 1, 0.8f)

    DenoiseGuides.empty().albedo.isEmpty shouldBe true
    DenoiseGuides.empty().normal.isEmpty shouldBe true
    DenoiseGuides.albedo(albedo).albedo.get shouldBe albedo
    DenoiseGuides.normal(normal).normal.get shouldBe normal
    DenoiseGuides.albedoAndNormal(albedo, normal).normal.get shouldBe normal

  it should "accept Java Optional guide containers" in:
    val albedo = rgba(1, 1, 0.2f)
    val guides = DenoiseGuides.optional(Optional.of(albedo), Optional.empty())

    guides.albedo.get shouldBe albedo
    guides.normal.isEmpty shouldBe true

  it should "reject null guide arrays" in:
    an[IllegalArgumentException] should be thrownBy
      DenoiseGuides.albedo(null) // scalafix:ok DisableSyntax.null

  it should "reject invalid optional guide containers" in:
    an[IllegalArgumentException] should be thrownBy
      DenoiseGuides.optional(null, Optional.empty()) // scalafix:ok DisableSyntax.null

  it should "return without throwing when native denoising is unavailable" in:
    withDenoiser() { denoiser =>
      val result = denoiser.denoise(DenoiseImage(1, 1, rgba(1, 1)))
      result.isPresent shouldBe denoiser.isAvailable
    }
