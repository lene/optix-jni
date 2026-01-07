package menger.optix.visualization

import menger.optix.ColorConstants
import menger.optix.ImageValidation
import menger.optix.OptiXRenderer
import menger.optix.TestScenario
import menger.optix.TestUtilities

object ColoredGlassComparison:

  def analyzeDominantColor(name: String, pixels: Array[Byte], width: Int, height: Int, expected: String): Unit =
    val dominant = ImageValidation.dominantColorChannel(pixels, width, height)

    println(f"\n=== $name ===")
    println(f"  Dominant: $dominant, Expected: $expected")
    println(f"  Status: ${if dominant == expected then "PASS" else "FAIL"}")

  def main(args: Array[String]): Unit =
    require(OptiXRenderer.isLibraryLoaded, "OptiX native library failed to load")

    val width = 800
    val height = 600

    // Red glass test
    println("=== Test 1: Red Glass ===")
    val renderer1 = new OptiXRenderer()
    renderer1.initialize()

    TestScenario.glassSphere()
      .withSphereColor(ColorConstants.RED_TINTED_GLASS)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer1)

    val pixelsRed = renderer1.render(width, height).get
    TestUtilities.savePNG("glass_red.png", pixelsRed, width, height)
    analyzeDominantColor("Red Glass", pixelsRed, width, height, "r")

    // Green glass test
    println("\n=== Test 2: Green Glass ===")
    val renderer2 = new OptiXRenderer()
    renderer2.initialize()

    TestScenario.glassSphere()
      .withSphereColor(ColorConstants.PERFORMANCE_TEST_GREEN_CYAN)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer2)

    val pixelsGreen = renderer2.render(width, height).get
    TestUtilities.savePNG("glass_green.png", pixelsGreen, width, height)
    analyzeDominantColor("Green Glass", pixelsGreen, width, height, "g")

    // Blue glass test
    println("\n=== Test 3: Blue Glass ===")
    val renderer3 = new OptiXRenderer()
    renderer3.initialize()

    TestScenario.glassSphere()
      .withSphereColor(ColorConstants.BLUE_TINTED_GLASS)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer3)

    val pixelsBlue = renderer3.render(width, height).get
    TestUtilities.savePNG("glass_blue.png", pixelsBlue, width, height)
    analyzeDominantColor("Blue Glass", pixelsBlue, width, height, "b")

    println("\n=== Summary ===")
    println("Current GRAYSCALE_CHANNEL_TOLERANCE = 10")
