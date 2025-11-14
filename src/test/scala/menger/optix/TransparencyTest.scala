package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

// ImageValidation used for pixel brightness analysis
import ColorConstants.*
import ThresholdConstants.*
import ImageMatchers.*

/**
 * Integration tests for transparent sphere rendering (Beer-Lambert absorption).
 *
 * Tests alpha channel variation with IOR=1.0 (no refraction):
 * - Alpha=1.0 (fully opaque) → maximum absorption, no background
 * - Alpha=0.5 (semi-transparent) → moderate absorption, some background
 * - Alpha=0.0 (fully transparent) → no absorption, full background
 *
 * Uses refactored test utilities:
 * - RendererFixture for automatic lifecycle
 * - ColorConstants for named transparency levels
 * - ThresholdConstants for validation thresholds
 * - TestScenario for fluent configuration
 * - Custom ImageMatchers for readable assertions
 */
class TransparencyTest extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Transparent sphere" should "be fully opaque at alpha=1.0" in:
    TestScenario.opaqueSphere()
      .withPlane(1, false, -2.0f)  // Y-axis floor at y=-2
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2).get

    // Opaque sphere should have substantial green area
    val greenArea = ImageValidation.spherePixelArea(
      imageData,
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )
    greenArea should be > MIN_VISIBLE_SPHERE_AREA

  it should "show partial transparency at alpha=0.75" in:
    TestScenario.default()
      .withSphereColor(MOSTLY_OPAQUE_GREEN)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2).get

    // Should have some variation (sphere+background)
    imageData should haveBrightnessStdDevGreaterThan(
      MIN_BASIC_REFRACTION_STDDEV,
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )

  it should "show moderate transparency at alpha=0.5" in:
    TestScenario.semiTransparentSphere()
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2).get

    // Semi-transparent sphere should have fewer green pixels than opaque sphere
    val greenArea = ImageValidation.spherePixelArea(
      imageData,
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )
    greenArea should (be > MIN_SEMI_TRANSPARENT_AREA and be < MAX_TRANSPARENT_COMPARISON_AREA)

  it should "show high transparency at alpha=0.25" in:
    TestScenario.default()
      .withSphereColor(MOSTLY_TRANSPARENT_GREEN)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2).get

    // Very transparent sphere should have fewer green pixels than opaque
    val greenArea = ImageValidation.spherePixelArea(
      imageData,
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )
    greenArea should be < MAX_TRANSPARENT_COMPARISON_AREA

  it should "be fully transparent at alpha=0.0" in:
    TestScenario.fullyTransparentSphere()
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2).get

    // Fully transparent sphere should have NO green pixels (invisible)
    val greenArea = ImageValidation.spherePixelArea(
      imageData,
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )
    greenArea shouldBe 0

  it should "show monotonic brightness decrease with increasing alpha" in:
    TestScenario.default()
      .withSphereColor(FULLY_TRANSPARENT_GREEN)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    // Measure green pixel count at different alpha values
    val alphas = Seq(0, 64, 128, 191, 255)  // 0.0, 0.25, 0.5, 0.75, 1.0
    val greenAreas = alphas.map { alpha =>
      setSphereColor(0.0f, 1.0f, 0.0f, alpha / 255.0f)
      val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2).get
      ImageValidation.spherePixelArea(
        imageData,
        TEST_IMAGE_SIZE._1,
        TEST_IMAGE_SIZE._2
      )
    }

    // Green pixel count should increase monotonically with alpha
    greenAreas(0) shouldBe 0  // alpha=0.0 → invisible
    greenAreas(4) should be > greenAreas(3)  // alpha=1.0 > alpha=0.75
    greenAreas(3) should be > greenAreas(2)  // alpha=0.75 > alpha=0.5

  it should "show colored transparency with green sphere" in:
    TestScenario.default()
      .withSphereColor(SEMI_TRANSPARENT_GREEN)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2).get

    // Image should show color variation (not grayscale)
    val (r, g, b) = ImageValidation.colorChannelRatio(
      imageData,
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )

    // With plane interaction, ratios vary but should not all be equal (not grayscale)
    val maxRatio = math.max(r, math.max(g, b))
    val minRatio = math.min(r, math.min(g, b))
    (maxRatio - minRatio) should be > 0.05  // At least 5% difference between channels

  it should "preserve background checkered pattern with low alpha" in:
    TestScenario.default()
      .withSphereColor(NEARLY_TRANSPARENT_WHITE)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2).get

    // Checkered floor should be visible at bottom
    imageData should showPlaneInRegion("bottom", TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)
