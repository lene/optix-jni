package io.github.lene.optix

import io.github.lene.optix.ColorConstants.OPAQUE_BLUE
import io.github.lene.optix.ColorConstants.OPAQUE_GREEN
import io.github.lene.optix.ColorConstants.OPAQUE_RED
import io.github.lene.optix.ThresholdConstants.QUICK_TEST_SIZE
import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


class ConeSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  // ========== Basic Cone Creation ==========

  "addConeInstance" should "return a valid instance ID for a simple cone" in:
    val apex = Vector[3](0.0f, 1.0f, 0.0f)
    val base = Vector[3](0.0f, -1.0f, 0.0f)
    val result = renderer.addConeInstance(apex, base, 0.5f, Material.Chrome)
    result should be >= 0

  it should "return a valid instance ID with Material parameter" in:
    val apex = Vector[3](0.0f, 0.0f, 1.0f)
    val base = Vector[3](0.0f, 0.0f, -1.0f)
    val result = renderer.addConeInstance(apex, base, 0.3f, Material.Glass)
    result should be >= 0

  it should "return a valid instance ID with color and IOR parameters" in:
    val apex = Vector[3](0.0f, 1.0f, 0.0f)
    val base = Vector[3](0.0f, 0.0f, 0.0f)
    val result = renderer.addConeInstance(apex, base, 0.4f, OPAQUE_GREEN, 1.0f)
    result should be >= 0

  it should "support various orientations" in:
    // Cone along X axis
    val xCone = renderer.addConeInstance(
      Vector[3](1.0f, 0.0f, 0.0f),
      Vector[3](-1.0f, 0.0f, 0.0f),
      0.4f, Material.Chrome
    )
    xCone should be >= 0

    // Cone along Y axis
    val yCone = renderer.addConeInstance(
      Vector[3](0.0f, 1.0f, 0.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      0.4f, Material.Chrome
    )
    yCone should be >= 0

    // Cone along Z axis
    val zCone = renderer.addConeInstance(
      Vector[3](0.0f, 0.0f, 1.0f),
      Vector[3](0.0f, 0.0f, -1.0f),
      0.4f, Material.Chrome
    )
    zCone should be >= 0

  // ========== Multiple Cones ==========

  "Multiple cones" should "be addable in sequence" in:
    val ids = for i <- 0 until 3 yield
      val x = (i - 1) * 0.8f
      renderer.addConeInstance(
        Vector[3](x, 0.5f, 0.0f),
        Vector[3](x, -0.5f, 0.0f),
        0.2f, Material.Chrome
      )
    ids.foreach(_ should be >= 0)
    ids.distinct.size shouldBe 3

  // ========== Cone Rendering ==========

  "Cone rendering" should "produce non-empty image data" in:
    renderer.addConeInstance(
      Vector[3](0.0f, 0.8f, 0.0f),
      Vector[3](0.0f, -0.8f, 0.0f),
      0.4f,
      Material.Chrome
    )
    val imageData = renderImage(QUICK_TEST_SIZE)
    imageData.length shouldBe ImageValidation.imageByteSize(QUICK_TEST_SIZE)

  it should "render visible cone geometry" in:
    renderer.addConeInstance(
      Vector[3](0.0f, 1.0f, 0.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      0.6f,
      Material(OPAQUE_GREEN, ior = 1.0f)
    )
    val imageData = renderImage(TEST_IMAGE_SIZE)
    val stdDev = ImageValidation.brightnessStdDev(imageData, TEST_IMAGE_SIZE)
    stdDev should be > 5.0

  // ========== Cone Transparency (Sprint 36 H1.4) ==========
  //
  // __closesthit__cone had no alpha test at all: it went straight to the metallic check and
  // then to flat diffuse shading, so material=glass rendered as opaque gray (manual test
  // "Cone glass"). Both cones below share geometry, colour and IOR and differ *only* in
  // alpha, so a shader that ignores alpha produces two identical images.

  private def coneImageWithAlpha(alpha: Float): Array[Byte] =
    renderer.clearAllInstances()
    renderer.addConeInstance(
      Vector[3](0.9f, 0.8f, 0.0f),
      Vector[3](0.9f, -0.8f, 0.0f),
      0.4f,
      Material(menger.common.Color(1.0f, 1.0f, 1.0f, alpha), ior = 1.5f)
    ) should be >= 0
    renderImage(TEST_IMAGE_SIZE)

  "A transparent cone" should "not render identically to an opaque one" in:
    val transparent = coneImageWithAlpha(0.02f)
    val opaque = coneImageWithAlpha(1.0f)

    val differing = transparent.indices.count(i => transparent(i) != opaque(i))
    withClue(
      s"transparent and opaque cones rendered ${differing} differing bytes of " +
      s"${transparent.length} — alpha is being ignored by __closesthit__cone: "
    ) {
      differing should be > 0
    }

  it should "refract the scene behind it rather than shade flat" in:
    val transparent = coneImageWithAlpha(0.02f)
    val opaque = coneImageWithAlpha(1.0f)

    // A refracting cone bends the floor plane and background through its body, so its own
    // pixels vary more than a flat-lit diffuse cone's do.
    val transparentVariation = ImageValidation.brightnessStdDev(transparent, TEST_IMAGE_SIZE)
    val opaqueVariation = ImageValidation.brightnessStdDev(opaque, TEST_IMAGE_SIZE)
    transparentVariation should not be opaqueVariation

  it should "render multiple colored cones" in:
    renderer.addConeInstance(
      Vector[3](-1.0f, 0.6f, 0.0f),
      Vector[3](-1.0f, -0.6f, 0.0f),
      0.3f, Material(OPAQUE_RED, ior = 1.0f)
    )
    renderer.addConeInstance(
      Vector[3](0.0f, 0.6f, 0.0f),
      Vector[3](0.0f, -0.6f, 0.0f),
      0.3f, Material(OPAQUE_GREEN, ior = 1.0f)
    )
    renderer.addConeInstance(
      Vector[3](1.0f, 0.6f, 0.0f),
      Vector[3](1.0f, -0.6f, 0.0f),
      0.3f, Material(OPAQUE_BLUE, ior = 1.0f)
    )
    val imageData = renderImage(TEST_IMAGE_SIZE)
    val ratio = ImageValidation.colorChannelRatio(imageData, TEST_IMAGE_SIZE)
    ratio.r should be > 0.1
    ratio.g should be > 0.1
    ratio.b should be > 0.1
