package io.github.lene.optix

import io.github.lene.optix.ColorConstants.OPAQUE_LIGHT_GRAY
import io.github.lene.optix.ImageMatchers.showPlaneInRegion
import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


class CameraSuite extends AnyFlatSpec with Matchers with RendererFixture:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Camera position" should "enable ceiling plane visibility from y=0.5" in:
    TestScenario.default()
      .withSphereColor(OPAQUE_LIGHT_GRAY)
      .withPlane(1, true, 2.0f)  // Ceiling at y=2
      .applyTo(renderer)

    val imageData = renderImage(TEST_IMAGE_SIZE)

    imageData should showPlaneInRegion("top", TEST_IMAGE_SIZE)

  it should "enable floor plane visibility from y=0.5" in:
    TestScenario.default()
      .withSphereColor(OPAQUE_LIGHT_GRAY)
      .withPlane(1, false, -2.0f)  // Floor at y=-2
      .applyTo(renderer)

    val imageData = renderImage(TEST_IMAGE_SIZE)

    imageData should showPlaneInRegion("bottom", TEST_IMAGE_SIZE)
