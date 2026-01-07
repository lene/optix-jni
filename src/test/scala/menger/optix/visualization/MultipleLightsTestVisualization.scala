package menger.optix.visualization

import menger.common.ImageSize
import menger.common.Vector
import menger.optix.ColorConstants
import menger.optix.OptiXRenderer
import menger.optix.ShadowValidation
import menger.optix.TestScenario
import menger.optix.TestUtilities

object MultipleLightsTestVisualization:

  def main(args: Array[String]): Unit =
    // Ensure library is loaded
    require(OptiXRenderer.isLibraryLoaded, "OptiX native library failed to load")

    val renderer = new OptiXRenderer()
    renderer.initialize()

    // Exact setup from failing test
    // TestScenario.default() has NO plane (plane = None)
    TestScenario.default()
      .withSphereColor(ColorConstants.OPAQUE_LIGHT_GRAY)
      .applyTo(renderer)

    // Old API as used in test
    renderer.setLight(Vector[3](0.5f, 0.5f, -0.5f), 1.0f)

    val width = 800
    val height = 600

    // Render
    val result = renderer.render(width, height).get
    TestUtilities.savePNG("multilight_test_image.png", result, width, height)

    // Calculate brightness in center region as test does
    val centerRegion = ShadowValidation.Region.centered(400, 300, 50)
    val brightness = ShadowValidation.regionBrightness(
      result, ImageSize(800, 600), centerRegion
    )

    println(s"Center region brightness: $brightness")
    println(s"Expected: > 60.0")
    println(s"Test status: ${if (brightness > 60.0) "PASS" else "FAIL"}")
