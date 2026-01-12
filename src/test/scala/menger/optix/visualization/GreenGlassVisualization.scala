package menger.optix.visualization

import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Vector
import menger.optix.ColorConstants
import menger.optix.ImageValidation
import menger.optix.OptiXRenderer
import menger.optix.TestScenario
import menger.optix.TestUtilities

object GreenGlassVisualization:

  def analyzeDominantColor(pixels: Array[Byte], width: Int, height: Int): Unit =
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

    println(f"\nCenter region analysis (50%% of image):")
    println(f"  Average R: $avgR%.2f")
    println(f"  Average G: $avgG%.2f")
    println(f"  Average B: $avgB%.2f")
    println(f"  Max - Min: ${math.max(avgR, math.max(avgG, avgB)) - math.min(avgR, math.min(avgG, avgB))}%.2f")
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

    // Exact setup from failing test - using GREEN_TINTED_GLASS
    TestScenario.glassSphere()
      .withSphereColor(ColorConstants.GREEN_TINTED_GLASS)
      .withPlane(1, false, -2.0f)
      .applyTo(renderer)

    val pixels = renderer.render(width, height).get
    TestUtilities.savePNG("green_glass_test.png", pixels, width, height)
    analyzeDominantColor(pixels, width, height)
