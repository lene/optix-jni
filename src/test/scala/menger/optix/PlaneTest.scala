package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ColorConstants.*
import ImageMatchers.*
import ThresholdConstants.*


class PlaneTest extends AnyFlatSpec with Matchers with RendererFixture:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Plane rendering" should "show floor at bottom of image" in:
    TestScenario.default()
      .withSphereColor(OPAQUE_LIGHT_GRAY)
      .withPlane(1, false, -2.0f)  // Floor at y=-2
      .applyTo(renderer)

    val imageData = renderImage(TEST_IMAGE_SIZE)

    imageData should showPlaneInRegion("bottom", TEST_IMAGE_SIZE)

  it should "show ceiling at top of image" in:
    TestScenario.default()
      .withSphereColor(OPAQUE_LIGHT_GRAY)
      .withPlane(1, true, 2.0f)  // Ceiling at y=2
      .applyTo(renderer)

    val imageData = renderImage(TEST_IMAGE_SIZE)

    imageData should showPlaneInRegion("top", TEST_IMAGE_SIZE)

  it should "be visible through transparent sphere" in:
    TestScenario.default()
      .withSphereColor(VERY_TRANSPARENT_WHITE)
      .withIOR(1.5f)
      .withPlane(1, false, -2.0f)  // Floor at y=-2
      .applyTo(renderer)

    val imageData = renderImage(TEST_IMAGE_SIZE)

    // Floor should be visible through transparent sphere
    imageData should showPlaneInRegion("bottom", TEST_IMAGE_SIZE)
