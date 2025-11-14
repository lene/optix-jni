package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ColorConstants.*
import ThresholdConstants.*
import ImageMatchers.*

/**
 * Geometric validation tests.
 *
 * Tests sphere geometry properties:
 * - Area scaling with radius²
 * - Center position detection
 */
class GeometryTest extends AnyFlatSpec with Matchers with RendererFixture:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Sphere geometry" should "scale area with radius²" in:
    TestScenario.default()
      .withSphereColor(OPAQUE_GREEN)
      .applyTo(renderer)

    // Render at radius 0.5
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    val img1 = renderImage(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)
    val area1 = ImageValidation.spherePixelArea(img1, TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)

    // Render at radius 1.0 (double radius)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.0f)
    val img2 = renderImage(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)
    val area2 = ImageValidation.spherePixelArea(img2, TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)

    // Area should scale with radius² → area2 ≈ 4 × area1
    val ratio = area2.toDouble / area1.toDouble
    ratio should (be >= MIN_ASPECT_RATIO_4_3 and be <= MAX_ASPECT_RATIO_4_3)

  it should "detect center position correctly" in:
    TestScenario.default()
      .withSphereColor(OPAQUE_GREEN)
      .applyTo(renderer)

    val imageData = renderImage(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)

    // Use custom matcher for cleaner assertion
    imageData should haveSphereCenteredInImage(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)
