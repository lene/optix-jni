package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ColorConstants.*
import ThresholdConstants.*
import ImageMatchers.*

/**
 * Real-world material simulation tests.
 *
 * Tests rendering of materials with realistic optical properties:
 * - Water (IOR=1.33, blue tint)
 * - Clear glass (IOR=1.5, no tint)
 * - Green glass (IOR=1.5, green tint)
 */
class MaterialTest extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Water material" should "render correctly" in:
    TestScenario.waterSphere()
      .withSphereColor(GLASS_LIGHT_CYAN)  // Blue-tinted like water
      .withSphereRadius(1.0f)
      .withIOR(1.33f)  // Water IOR
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)
    val (r, g, b) = ImageValidation.colorChannelRatio(
      imageData,
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )

    b should be >= r  // Blue tint

  "Clear glass" should "render correctly" in:
    TestScenario.glassSphere()
      .withSphereColor(CLEAR_GLASS_WHITE)
      .withSphereRadius(1.0f)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)

    // Glass refraction creates brightness variation
    imageData should showGlassRefraction(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)

  "Green glass" should "render correctly" in:
    TestScenario.coloredGlassSphere()
      .withSphereRadius(1.0f)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)
    val (r, g, b) = ImageValidation.colorChannelRatio(
      imageData,
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )

    g should be > r
    g should be > b
