package menger.optix.visualization

import menger.common.Color
import menger.common.Const
import menger.common.Vector
import menger.optix.ColorConstants
import menger.optix.ImageValidation
import menger.optix.OptiXRenderer
import menger.optix.TestScenario
import menger.optix.TestUtilities

object GreenGlassVisualization:

  def analyzeDominantColor(pixels: Array[Byte], width: Int, height: Int): Unit =
    val dominant = ImageValidation.dominantColorChannel(pixels, width, height)

    println(f"\nCenter region analysis (50%% of image):")
    println(f"  Dominant channel: $dominant")
    println(f"  Expected: g")
    println(f"  Test status: ${if dominant == "g" then "PASS" else "FAIL"}")

  def main(args: Array[String]): Unit =
    require(OptiXRenderer.isLibraryLoaded, "OptiX native library failed to load")

    val width = 800
    val height = 600

    println("=== Green Glass Refraction Test ===")
    val renderer = new OptiXRenderer()
    renderer.initialize()

    // Exact setup from failing test
    TestScenario.glassSphere()
      .withSphereColor(ColorConstants.PERFORMANCE_TEST_GREEN_CYAN)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val pixels = renderer.render(width, height).get
    TestUtilities.savePNG("green_glass_test.png", pixels, width, height)
    analyzeDominantColor(pixels, width, height)
