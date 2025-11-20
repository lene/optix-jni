package menger.optix

import java.nio.file.{Files, Paths}

object ColoredGlassComparison:

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

  def analyzeDominantColor(name: String, pixels: Array[Byte], width: Int, height: Int, expected: String): Unit =
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

    println(f"\n=== $name ===")
    println(f"  Average R: $avgR%.2f")
    println(f"  Average G: $avgG%.2f")
    println(f"  Average B: $avgB%.2f")
    println(f"  Max - Min: $diff%.2f (threshold: 10.0)")

    val dominant = if diff < 10.0 then "gray"
                  else if avgR == maxChannel then "r"
                  else if avgG == maxChannel then "g"
                  else "b"

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
    savePPM("glass_red.ppm", pixelsRed, width, height)
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
    savePPM("glass_green.ppm", pixelsGreen, width, height)
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
    savePPM("glass_blue.ppm", pixelsBlue, width, height)
    analyzeDominantColor("Blue Glass", pixelsBlue, width, height, "b")

    println("\n=== Summary ===")
    println("Current GRAYSCALE_CHANNEL_TOLERANCE = 10")
    println("\nConvert to PNG with:")
    println("  convert glass_red.ppm glass_red.png")
    println("  convert glass_green.ppm glass_green.png")
    println("  convert glass_blue.ppm glass_blue.png")
