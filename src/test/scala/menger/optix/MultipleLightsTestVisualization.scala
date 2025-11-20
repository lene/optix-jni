package menger.optix

import menger.common.ImageSize
import java.nio.file.{Files, Paths}

object MultipleLightsTestVisualization:

  def savePPM(filename: String, pixels: Array[Byte], width: Int, height: Int): Unit =
    val header = s"P6\n$width $height\n255\n".getBytes
    val rgb = new Array[Byte](width * height * 3)

    // Convert RGBA to RGB
    var i = 0
    var j = 0
    while i < pixels.length do
      rgb(j) = pixels(i)       // R
      rgb(j + 1) = pixels(i + 1) // G
      rgb(j + 2) = pixels(i + 2) // B
      i += 4
      j += 3

    Files.write(Paths.get(filename), header ++ rgb)
    println(s"Saved: $filename")

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
    renderer.setLight(Array(0.5f, 0.5f, -0.5f), 1.0f)

    val width = 800
    val height = 600

    // Render
    val result = renderer.render(width, height).get
    savePPM("multilight_test_image.ppm", result, width, height)

    // Calculate brightness in center region as test does
    val centerRegion = ShadowValidation.Region.centered(400, 300, 50)
    val brightness = ShadowValidation.regionBrightness(
      result, ImageSize(800, 600), centerRegion
    )

    println(s"Center region brightness: $brightness")
    println(s"Expected: > 60.0")
    println(s"Test status: ${if (brightness > 60.0) "PASS" else "FAIL"}")

    println("\nConvert to PNG with:")
    println("  convert multilight_test_image.ppm multilight_test_image.png")
