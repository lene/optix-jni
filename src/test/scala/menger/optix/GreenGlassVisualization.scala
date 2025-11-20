package menger.optix

import java.nio.file.{Files, Paths}

object GreenGlassVisualization:

  def savePPM(filename: String, pixels: Array[Byte], width: Int, height: Int): Unit =
    val header = s"P6\n$width $height\n255\n".getBytes
    val rgb = new Array[Byte](width * height * 3)

    var i = 0
    var j = 0
    while i < pixels.length do
      rgb(j) = pixels(i)
      rgb(j + 1) = pixels(i + 1)
      rgb(j + 2) = pixels(i + 2)
      i += 4
      j += 3

    Files.write(Paths.get(filename), header ++ rgb)
    println(s"Saved: $filename")

  def analyzeDominantColor(pixels: Array[Byte], width: Int, height: Int): Unit =
    // Same logic as ImageValidation.dominantColorChannel
    val startX = width / 4
    val endX = 3 * width / 4
    val startY = height / 4
    val endY = 3 * height / 4

    var totalR = 0.0
    var totalG = 0.0
    var totalB = 0.0
    var count = 0

    for
      y <- startY until endY
      x <- startX until endX
    do
      val idx = (y * width + x) * 4
      totalR += (pixels(idx) & 0xFF)
      totalG += (pixels(idx + 1) & 0xFF)
      totalB += (pixels(idx + 2) & 0xFF)
      count += 1

    val avgR = totalR / count
    val avgG = totalG / count
    val avgB = totalB / count

    val maxChannel = math.max(avgR, math.max(avgG, avgB))
    val minChannel = math.min(avgR, math.min(avgG, avgB))
    val diff = maxChannel - minChannel

    println(f"\nCenter region analysis (50%% of image):")
    println(f"  Average R: $avgR%.2f")
    println(f"  Average G: $avgG%.2f")
    println(f"  Average B: $avgB%.2f")
    println(f"  Max - Min: $diff%.2f (threshold: 10.0)")

    val dominant = if diff < 10.0 then "gray"
                  else if avgR == maxChannel then "r"
                  else if avgG == maxChannel then "g"
                  else "b"

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
    savePPM("green_glass_test.ppm", pixels, width, height)
    analyzeDominantColor(pixels, width, height)

    println("\nConvert to PNG with:")
    println("  convert green_glass_test.ppm green_glass_test.png")
