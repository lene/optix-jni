package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ColorConstants.*
import ThresholdConstants.*

/**
 * Integration tests for Beer-Lambert absorption (scale parameter).
 *
 * Tests that absorption increases with scale parameter and creates
 * expected center-to-edge brightness gradients.
 */
class AbsorptionTest extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Beer-Lambert absorption" should "increase with scale parameter" in:
    TestScenario.default()
      .withSphereColor(SEMI_TRANSPARENT_GRAY)
      .withIOR(1.5f)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    // Measure brightness at scale=0.5 (less absorption)
    renderer.setScale(0.5f)
    val image1 = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)
    val (cx1, cy1) = ImageValidation.detectSphereCenter(
      image1,
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )
    val bright1 = ImageValidation.brightness(
      image1,
      cy1 * TEST_IMAGE_SIZE._1 + cx1
    )

    // Measure brightness at scale=2.0 (more absorption)
    renderer.setScale(2.0f)
    val image2 = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)
    val (cx2, cy2) = ImageValidation.detectSphereCenter(
      image2,
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )
    val bright2 = ImageValidation.brightness(
      image2,
      cy2 * TEST_IMAGE_SIZE._1 + cx2
    )

    bright1 should be > bright2  // Higher scale = more absorption = darker

  it should "show center-to-edge gradient" in:
    TestScenario.default()
      .withSphereColor(SEMI_TRANSPARENT_GRAY)
      .withIOR(1.5f)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    renderer.setScale(2.0f)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)
    val gradient = ImageValidation.brightnessGradient(
      imageData,
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )

    // With absorption, center should be darker (negative gradient)
    gradient should be > MIN_ABSORPTION_GRADIENT
