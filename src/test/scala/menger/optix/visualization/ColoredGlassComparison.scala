package menger.optix.visualization

import menger.common.ImageSize
import menger.optix.ColorConstants
import menger.optix.ImageValidation
import menger.optix.OptiXRenderer
import menger.optix.TestScenario
import menger.optix.TestUtilities

object ColoredGlassComparison:

  def analyzeDominantColor(name: String, pixels: Array[Byte], width: Int, height: Int, expected: String): Unit =
    val dominant = ImageValidation.dominantColorChannel(pixels, width, height)
    val size = ImageSize(width, height)

    // Calculate average RGB in center 50%
    val startX = width / 4
    val endX = 3 * width / 4
    val startY = height / 4
    val endY = 3 * height / 4

    val rgbValues = for
      y <- startY until endY
      x <- startX until endX
    yield ImageValidation.getRGBAt(pixels, size, x, y)

    val count = rgbValues.length
    val avgR = rgbValues.map(_.r).sum.toDouble / count
    val avgG = rgbValues.map(_.g).sum.toDouble / count
    val avgB = rgbValues.map(_.b).sum.toDouble / count

    println(f"\n=== $name ===")
    println(f"  Avg R: $avgR%.2f, G: $avgG%.2f, B: $avgB%.2f")
    println(f"  Max - Min: ${math.max(avgR, math.max(avgG, avgB)) - math.min(avgR, math.min(avgG, avgB))}%.2f")
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

    // Green glass test - using pure green
    println("\n=== Test 2: Green Glass ===")
    val renderer2 = new OptiXRenderer()
    renderer2.initialize()

    TestScenario.glassSphere()
      .withSphereColor(ColorConstants.SEMI_TRANSPARENT_GREEN)
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
