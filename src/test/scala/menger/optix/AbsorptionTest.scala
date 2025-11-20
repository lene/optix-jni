package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ColorConstants.*
import ThresholdConstants.*


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
    val image1 = renderer.render(TEST_IMAGE_SIZE).get
    val center1 = ImageValidation.detectSphereCenter(
      image1,
      TEST_IMAGE_SIZE
    )
    val bright1 = ImageValidation.brightness(
      image1,
      center1.y * TEST_IMAGE_SIZE.width + center1.x
    )

    // Measure brightness at scale=2.0 (more absorption)
    renderer.setScale(2.0f)
    val image2 = renderer.render(TEST_IMAGE_SIZE).get
    val center2 = ImageValidation.detectSphereCenter(
      image2,
      TEST_IMAGE_SIZE
    )
    val bright2 = ImageValidation.brightness(
      image2,
      center2.y * TEST_IMAGE_SIZE.width + center2.x
    )

    bright1 should be > bright2  // Higher scale = more absorption = darker

  it should "show center-to-edge gradient" in:
    TestScenario.default()
      .withSphereColor(SEMI_TRANSPARENT_GRAY)
      .withIOR(1.5f)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    renderer.setScale(2.0f)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get
    val gradient = ImageValidation.brightnessGradient(
      imageData,
      TEST_IMAGE_SIZE
    )

    // With absorption, center should be darker (negative gradient)
    gradient should be > MIN_ABSORPTION_GRADIENT
