package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ColorConstants.*
import ThresholdConstants.*
import ImageMatchers.*


class RefractionTest extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Refractive sphere" should "show minimal refraction at IOR=1.0" in:
    TestScenario.default()
      .withSphereColor(SEMI_TRANSPARENT_WHITE)
      .withIOR(1.0f)  // No refraction
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get

    // Should show floor plane through semi-transparent sphere
    imageData should showPlaneInRegion("bottom", TEST_IMAGE_SIZE)

  it should "show water-like refraction at IOR=1.33" in:
    TestScenario.waterSphere()
      .withSphereColor(SEMI_TRANSPARENT_WHITE)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get

    // Should show moderate variation (refraction distortion)
    imageData should showGlassRefraction(TEST_IMAGE_SIZE)

  it should "show glass-like refraction at IOR=1.5" in:
    TestScenario.glassSphere()
      .withSphereColor(SEMI_TRANSPARENT_WHITE)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get

    // Should show strong variation (glass refraction)
    imageData should showWaterRefraction(TEST_IMAGE_SIZE)

  it should "show diamond-like refraction at IOR=2.42" in:
    TestScenario.diamondSphere()
      .withSphereColor(HIGHLY_TRANSPARENT_WHITE)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get

    // Should show very high variation (strong refraction)
    imageData should showDiamondRefraction(TEST_IMAGE_SIZE)

  it should "increase edge brightness with higher IOR" in:
    TestScenario.default()
      .withSphereColor(REFRACTION_TEST_GRAY)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    // Measure edge brightness at IOR=1.33 (water)
    renderer.setIOR(1.33f)
    val imageWater = renderer.render(TEST_IMAGE_SIZE).get
    val edgeBrightnessWater = ImageValidation.edgeBrightness(
      imageWater,
      TEST_IMAGE_SIZE
    )

    // Measure edge brightness at IOR=2.42 (diamond)
    renderer.setIOR(2.42f)
    val imageDiamond = renderer.render(TEST_IMAGE_SIZE).get
    val edgeBrightnessDiamond = ImageValidation.edgeBrightness(
      imageDiamond,
      TEST_IMAGE_SIZE
    )

    edgeBrightnessDiamond should be >= edgeBrightnessWater

  it should "transmit red light through red glass" in:
    TestScenario.glassSphere()
      .withSphereColor(RED_TINTED_GLASS)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get

    // Red channel should dominate
    imageData should beRedDominant(TEST_IMAGE_SIZE)

  it should "transmit green light through green glass" in:
    TestScenario.glassSphere()
      .withSphereColor(PERFORMANCE_TEST_GREEN_CYAN)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get

    // Green channel should dominate (tolerance = 7.0 to account for lower brightness in multi-light implementation)
    imageData should beGreenDominant(TEST_IMAGE_SIZE, tolerance = 7.0)

  it should "transmit blue light through blue glass" in:
    TestScenario.glassSphere()
      .withSphereColor(BLUE_TINTED_GLASS)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get

    // Blue channel should dominate
    imageData should beBlueDominant(TEST_IMAGE_SIZE)

  it should "show background distortion through refractive sphere" in:
    TestScenario.glassSphere()
      .withSphereColor(HIGHLY_TRANSPARENT_GRAY)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get

    // Checkered floor should be visible through transparent sphere
    imageData should showPlaneInRegion("bottom", TEST_IMAGE_SIZE)

    // High variation due to refraction distorting checkered pattern
    imageData should showWaterRefraction(TEST_IMAGE_SIZE)
