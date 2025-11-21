package menger.optix
import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import java.nio.file.{Files, Paths}

object LightComparisonVisualization:

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

  def measureBrightness(pixels: Array[Byte], width: Int, height: Int): Double =
    val centerRegion = ShadowValidation.Region.centered(400, 300, 50)
    ShadowValidation.regionBrightness(pixels, ImageSize(width, height), centerRegion)

  def main(args: Array[String]): Unit =
    require(OptiXRenderer.isLibraryLoaded, "OptiX native library failed to load")

    val width = 800
    val height = 600

    // ========== Test 1: Default C++ light (no setLight call) ==========
    println("=== Test 1: Default C++ light (from constructor) ===")
    val renderer1 = new OptiXRenderer()
    renderer1.initialize()

    // Set up scene WITHOUT calling setLight - use C++ default
    renderer1.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 0.5f)
    renderer1.setSphereColor(Color(0.784f, 0.784f, 0.784f, 1.0f))  // OPAQUE_LIGHT_GRAY
    renderer1.setIOR(1.0f)
    renderer1.setCamera(
      Vector[3](0.0f, 0.5f, 3.0f),
      Vector[3](0.0f, 0.0f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      60.0f
    )
    // NO setLight() call - uses C++ default: (0.577350f, 0.577350f, -0.577350f)

    val pixels1 = renderer1.render(width, height).get
    savePPM("light_default_cpp.ppm", pixels1, width, height)
    val brightness1 = measureBrightness(pixels1, width, height)
    println(s"Brightness: $brightness1")

    // ========== Test 2: setLight with (0.5, 0.5, -0.5) ==========
    println("\n=== Test 2: setLight(0.5, 0.5, -0.5) ===")
    val renderer2 = new OptiXRenderer()
    renderer2.initialize()

    renderer2.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 0.5f)
    renderer2.setSphereColor(Color(0.784f, 0.784f, 0.784f, 1.0f))
    renderer2.setIOR(1.0f)
    renderer2.setCamera(
      Vector[3](0.0f, 0.5f, 3.0f),
      Vector[3](0.0f, 0.0f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      60.0f
    )
    renderer2.setLight(Vector[3](0.5f, 0.5f, -0.5f), 1.0f)

    val pixels2 = renderer2.render(width, height).get
    savePPM("light_setlight_unnormalized.ppm", pixels2, width, height)
    val brightness2 = measureBrightness(pixels2, width, height)
    println(s"Brightness: $brightness2")

    // ========== Test 3: setLight with normalized (0.577, 0.577, -0.577) ==========
    println("\n=== Test 3: setLight(0.577, 0.577, -0.577) normalized ===")
    val renderer3 = new OptiXRenderer()
    renderer3.initialize()

    renderer3.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 0.5f)
    renderer3.setSphereColor(Color(0.784f, 0.784f, 0.784f, 1.0f))
    renderer3.setIOR(1.0f)
    renderer3.setCamera(
      Vector[3](0.0f, 0.5f, 3.0f),
      Vector[3](0.0f, 0.0f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      60.0f
    )
    renderer3.setLight(Vector[3](0.577350f, 0.577350f, -0.577350f), 1.0f)

    val pixels3 = renderer3.render(width, height).get
    savePPM("light_setlight_normalized.ppm", pixels3, width, height)
    val brightness3 = measureBrightness(pixels3, width, height)
    println(s"Brightness: $brightness3")

    // ========== Test 4: EXACT failing test setup ==========
    println("\n=== Test 4: EXACT failing test setup (TestScenario.default()) ===")
    val renderer4 = new OptiXRenderer()
    renderer4.initialize()

    TestScenario.default()
      .withSphereColor(ColorConstants.OPAQUE_LIGHT_GRAY)
      .applyTo(renderer4)

    renderer4.setLight(Vector[3](0.5f, 0.5f, -0.5f), 1.0f)

    val pixels4 = renderer4.render(width, height).get
    savePPM("light_exact_test.ppm", pixels4, width, height)
    val brightness4 = measureBrightness(pixels4, width, height)
    println(s"Brightness: $brightness4")

    println("\n=== Summary ===")
    println(f"Default C++ light: $brightness1%.4f")
    println(f"setLight(0.5, 0.5, -0.5): $brightness2%.4f")
    println(f"setLight(0.577, 0.577, -0.577): $brightness3%.4f")
    println(f"EXACT test setup: $brightness4%.4f")
    println(f"Expected (pure ambient): 60.0")
    println(f"Test threshold: > 60.0")

    println("\nConvert to PNG:")
    println("  convert light_default_cpp.ppm light_default_cpp.png")
    println("  convert light_setlight_unnormalized.ppm light_setlight_unnormalized.png")
    println("  convert light_setlight_normalized.ppm light_setlight_normalized.png")
    println("  convert light_exact_test.ppm light_exact_test.png")
