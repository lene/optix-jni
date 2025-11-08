package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ColorConstants.*
import ImageMatchers.*
import ThresholdConstants.*

/**
 * Camera position variation tests.
 *
 * Tests that different camera positions enable visibility of
 * ceiling and floor planes as expected.
 */
class CameraTest extends AnyFlatSpec with Matchers with RendererFixture:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Camera position" should "enable ceiling plane visibility from y=0.5" in:
    TestScenario.default()
      .withSphereColor(OPAQUE_LIGHT_GRAY)
      .withPlane(1, true, 2.0f)  // Ceiling at y=2
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)

    imageData should showPlaneInRegion("top", TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)

  it should "enable floor plane visibility from y=0.5" in:
    TestScenario.default()
      .withSphereColor(OPAQUE_LIGHT_GRAY)
      .withPlane(1, false, -2.0f)  // Floor at y=-2
      .applyTo(renderer)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)

    imageData should showPlaneInRegion("bottom", TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)
