package menger.optix

import menger.common.Vector
import menger.optix.ColorConstants.OPAQUE_BLUE
import menger.optix.ColorConstants.OPAQUE_GREEN
import menger.optix.ColorConstants.OPAQUE_RED
import menger.optix.ThresholdConstants.QUICK_TEST_SIZE
import menger.optix.ThresholdConstants.TEST_IMAGE_SIZE
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


class ConeSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  // ========== Basic Cone Creation ==========

  "addConeInstance" should "return a valid instance ID for a simple cone" in:
    val apex = Vector[3](0.0f, 1.0f, 0.0f)
    val base = Vector[3](0.0f, -1.0f, 0.0f)
    val result = renderer.addConeInstance(apex, base, 0.5f, Material.Chrome)
    result shouldBe defined
    result.get should be >= 0

  it should "return a valid instance ID with Material parameter" in:
    val apex = Vector[3](0.0f, 0.0f, 1.0f)
    val base = Vector[3](0.0f, 0.0f, -1.0f)
    val result = renderer.addConeInstance(apex, base, 0.3f, Material.Glass)
    result shouldBe defined
    result.get should be >= 0

  it should "return a valid instance ID with color and IOR parameters" in:
    val apex = Vector[3](0.0f, 1.0f, 0.0f)
    val base = Vector[3](0.0f, 0.0f, 0.0f)
    val result = renderer.addConeInstance(apex, base, 0.4f, OPAQUE_GREEN, 1.0f)
    result shouldBe defined
    result.get should be >= 0

  it should "support various orientations" in:
    // Cone along X axis
    val xCone = renderer.addConeInstance(
      Vector[3](1.0f, 0.0f, 0.0f),
      Vector[3](-1.0f, 0.0f, 0.0f),
      0.4f, Material.Chrome
    )
    xCone shouldBe defined

    // Cone along Y axis
    val yCone = renderer.addConeInstance(
      Vector[3](0.0f, 1.0f, 0.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      0.4f, Material.Chrome
    )
    yCone shouldBe defined

    // Cone along Z axis
    val zCone = renderer.addConeInstance(
      Vector[3](0.0f, 0.0f, 1.0f),
      Vector[3](0.0f, 0.0f, -1.0f),
      0.4f, Material.Chrome
    )
    zCone shouldBe defined

  // ========== Multiple Cones ==========

  "Multiple cones" should "be addable in sequence" in:
    val ids = for i <- 0 until 3 yield
      val x = (i - 1) * 0.8f
      renderer.addConeInstance(
        Vector[3](x, 0.5f, 0.0f),
        Vector[3](x, -0.5f, 0.0f),
        0.2f, Material.Chrome
      )
    ids.foreach(_ shouldBe defined)
    ids.map(_.get).distinct.size shouldBe 3

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
