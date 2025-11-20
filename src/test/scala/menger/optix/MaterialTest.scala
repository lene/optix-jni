package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ColorConstants.*
import ThresholdConstants.*
import ImageMatchers.*


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

    val imageData = renderer.render(TEST_IMAGE_SIZE).get
    val ratio = ImageValidation.colorChannelRatio(
      imageData,
      TEST_IMAGE_SIZE
    )

    ratio.b should be >= ratio.r  // Blue tint

  "Clear glass" should "render correctly" in:
    TestScenario.glassSphere()
      .withSphereColor(CLEAR_GLASS_WHITE)
      .withSphereRadius(1.0f)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get

    // Glass refraction creates brightness variation
    imageData should showGlassRefraction(TEST_IMAGE_SIZE)

  "Green glass" should "render correctly" in:
    TestScenario.coloredGlassSphere()
      .withSphereRadius(1.0f)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE).get
    val ratio = ImageValidation.colorChannelRatio(
      imageData,
      TEST_IMAGE_SIZE
    )

    ratio.g should be > ratio.r
    ratio.g should be > ratio.b
