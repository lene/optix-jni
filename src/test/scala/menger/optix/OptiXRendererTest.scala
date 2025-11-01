package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.Try
import java.io.{File, FileOutputStream}

/**
 * Test suite for OptiX JNI bindings.
 *
 * Phase 1: Tests basic structure - library loading, method calls
 * Phase 2+: Will add tests for actual rendering functionality
 */
object OptiXRendererTest:
  // Test configuration constants
  object TestConfig:
    val SmallImageSize = (10, 10)
    val StandardImageSize = (800, 600)

    // Default sphere configuration
    val DefaultSphere = (0.0f, 0.0f, 0.0f, 1.5f)

    // Default camera configuration
    val DefaultCameraEye = Array(0.0f, 0.0f, 3.0f)
    val DefaultCameraLookAt = Array(0.0f, 0.0f, 0.0f)
    val DefaultCameraUp = Array(0.0f, 1.0f, 0.0f)
    val DefaultCameraFov = 60.0f

    // Default light configuration
    val DefaultLightDirection = Array(0.5f, 0.5f, -0.5f)
    val DefaultLightIntensity = 1.0f

  // Helper for analyzing rendered images
  object ImageAnalysis:
    /** Compute brightness (grayscale value) for a pixel */
    def brightness(imageData: Array[Byte], pixelIndex: Int): Double =
      val offset = pixelIndex * 4
      val r = imageData(offset) & 0xFF
      val g = imageData(offset + 1) & 0xFF
      val b = imageData(offset + 2) & 0xFF
      (r + g + b) / 3.0

    /** Compute standard deviation of brightness across all pixels */
    def brightnessStdDev(imageData: Array[Byte], width: Int, height: Int): Double =
      val numPixels = width * height
      val brightnesses = (0 until numPixels).map(i => brightness(imageData, i))

      val mean = brightnesses.sum / numPixels
      val variance = brightnesses.map(b => math.pow(b - mean, 2)).sum / numPixels
      math.sqrt(variance)

    /** Check if center region is brighter than edge region (sphere characteristic) */
    def hasCenterBrightness(imageData: Array[Byte], width: Int, height: Int): Boolean =
      val centerX = width / 2
      val centerY = height / 2
      val edgeX = width / 8
      val edgeY = height / 2

      val centerBrightness = brightness(imageData, centerY * width + centerX)
      val edgeBrightness = brightness(imageData, edgeY * width + edgeX)

      centerBrightness > edgeBrightness

  // Helper for saving PPM images
  object ImageIO:
    def savePPM(imageData: Array[Byte], width: Int, height: Int, filename: String): File =
      val outputFile = new File(filename)
      val header = s"P6\n$width $height\n255\n"

      val out = new FileOutputStream(outputFile)
      try
        out.write(header.getBytes("ASCII"))

        for i <- 0 until width * height do
          val offset = i * 4
          out.write(imageData(offset) & 0xFF)     // R
          out.write(imageData(offset + 1) & 0xFF) // G
          out.write(imageData(offset + 2) & 0xFF) // B
      finally
        out.close()

      outputFile


class OptiXRendererTest extends AnyFlatSpec with Matchers with LazyLogging:
  import OptiXRendererTest._

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "OptiXRenderer" should "be instantiable" in new OptiXRenderer:
    this should not be null

  it should "load native library without error" in:
    // The library should already be loaded by the companion object
    // If we got this far without UnsatisfiedLinkError, it worked
    noException should be thrownBy { new OptiXRenderer() }

  it should "allow initialize() to be called without crashing" in new OptiXRenderer:
    noException should be thrownBy { initialize() }

  it should "return true from initialize() (placeholder implementation)" in new OptiXRenderer:
    val initialized = initialize()
    initialized shouldBe true

  it should "allow setSphere() to be called without crashing" in new OptiXRenderer:
    initialize()
    noException should be thrownBy { setSphere(0.0f, 0.0f, 0.0f, 1.5f) }

  it should "allow setCamera() to be called without crashing" in new OptiXRenderer:
    initialize()
    val eye = Array(0.0f, 0.0f, 3.0f)
    val lookAt = Array(0.0f, 0.0f, 0.0f)
    val up = Array(0.0f, 1.0f, 0.0f)
    noException should be thrownBy { setCamera(eye, lookAt, up, 60.0f) }

  it should "allow setLight() to be called without crashing" in new OptiXRenderer:
    initialize()
    val direction = Array(0.5f, 0.5f, -0.5f)
    noException should be thrownBy { setLight(direction, 1.0f) }

  it should "return non-null array from render()" in new OptiXRenderer:
    initialize()
    val (width, height) = TestConfig.StandardImageSize
    val result = render(width, height)
    result should not be null

  it should "return correct size array from render() (RGBA)" in new OptiXRenderer:
    initialize()
    val (width, height) = TestConfig.StandardImageSize
    val result = render(width, height)
    val expectedSize = width * height * 4 // RGBA
    result.length shouldBe expectedSize

  it should "render actual OptiX output (not placeholder)" in new OptiXRenderer:
    initialize()
    val (width, height) = TestConfig.SmallImageSize
    val result = render(width, height)
    result should not be null
    result.length shouldBe width * height * 4

    // Rendered sphere should have brightness variation (not uniform like stub)
    val stdDev = ImageAnalysis.brightnessStdDev(result, width, height)
    stdDev should be > 30.0  // Sphere has gradients; stub/uniform would be ~0

    // Sphere center should be brighter than edges (basic lighting check)
    ImageAnalysis.hasCenterBrightness(result, width, height) shouldBe true

  it should "allow dispose() to be called without crashing" in new OptiXRenderer:
    initialize()
    noException should be thrownBy { dispose() }

  it should "allow dispose() to be called multiple times safely" in new OptiXRenderer:
    initialize()
    dispose()
    noException should be thrownBy { dispose() }

  it should "return a boolean from isAvailable" in new OptiXRenderer:
    val available = isAvailable
    // We can't assert true/false here as it depends on the system
    // But we verify it returns a valid boolean
    available shouldBe a[Boolean]

  it should "support full workflow: init -> configure -> render -> dispose" in new OptiXRenderer:
    val initialized = initialize()
    initialized shouldBe true

    val (x, y, z, r) = TestConfig.DefaultSphere
    setSphere(x, y, z, r)
    setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)

    val image = render(100, 100)
    image.length shouldBe 100 * 100 * 4

    dispose()

  it should "save rendered output as PPM for visual inspection" in new OptiXRenderer:
    val (width, height) = TestConfig.StandardImageSize
    val filename = "optix_test_output.ppm"

    // Clean up any pre-existing output files (PPM and converted PNG)
    val ppmFile = new File(filename)
    val pngFile = new File(filename.replace(".ppm", ".png"))
    if ppmFile.exists() then ppmFile.delete()
    if pngFile.exists() then pngFile.delete()

    val initialized = initialize()
    initialized shouldBe true

    val (x, y, z, r) = TestConfig.DefaultSphere
    setSphere(x, y, z, r)
    setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)

    val imageData = render(width, height)
    imageData.length shouldBe width * height * 4

    val savedFile = ImageIO.savePPM(imageData, width, height, filename)

    logger.info(s"\n=== Rendered image saved to: ${savedFile.getAbsolutePath}")

    ppmFile.exists() shouldBe true
    val expectedSize = s"P6\n$width $height\n255\n".length + width * height * 3
    savedFile.length() shouldBe expectedSize

    dispose()

  // Phase 4 Integration Tests
  // NOTE: These tests use separate renderer instances because SBT updates after pipeline build
  // are not yet implemented. This will be addressed in the SBT update enhancement.
  it should "render different images with different camera positions" in:
    val (width, height) = (100, 100)

    // Render from default position (front)
    val renderer1 = new OptiXRenderer()
    renderer1.initialize()
    renderer1.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer1.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer1.setCamera(Array(0.0f, 0.0f, 3.0f), Array(0.0f, 0.0f, 0.0f), Array(0.0f, 1.0f, 0.0f), 60.0f)
    val image1 = renderer1.render(width, height)
    renderer1.dispose()

    // Render from side
    val renderer2 = new OptiXRenderer()
    renderer2.initialize()
    renderer2.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer2.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer2.setCamera(Array(3.0f, 0.0f, 0.0f), Array(0.0f, 0.0f, 0.0f), Array(0.0f, 1.0f, 0.0f), 60.0f)
    val image2 = renderer2.render(width, height)
    renderer2.dispose()

    // Render from top
    val renderer3 = new OptiXRenderer()
    renderer3.initialize()
    renderer3.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer3.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer3.setCamera(Array(0.0f, 3.0f, 0.0f), Array(0.0f, 0.0f, 0.0f), Array(0.0f, 0.0f, -1.0f), 60.0f)
    val image3 = renderer3.render(width, height)
    renderer3.dispose()

    // All should render validly with brightness variation (not uniform like stub)
    // Lower threshold accounts for viewing angles that produce less variation
    ImageAnalysis.brightnessStdDev(image1, width, height) should be > 15.0
    ImageAnalysis.brightnessStdDev(image2, width, height) should be > 15.0
    ImageAnalysis.brightnessStdDev(image3, width, height) should be > 15.0

    // All images should have valid size
    image1.length shouldBe width * height * 4
    image2.length shouldBe width * height * 4
    image3.length shouldBe width * height * 4

  it should "render different images with different light directions" in:
    val (width, height) = (100, 100)

    // Light from top-right
    val renderer1 = new OptiXRenderer()
    renderer1.initialize()
    renderer1.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer1.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer1.setLight(Array(0.5f, 0.5f, -0.5f), 1.0f)
    val image1 = renderer1.render(width, height)
    renderer1.dispose()

    // Light from left
    val renderer2 = new OptiXRenderer()
    renderer2.initialize()
    renderer2.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer2.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer2.setLight(Array(-1.0f, 0.0f, 0.0f), 1.0f)
    val image2 = renderer2.render(width, height)
    renderer2.dispose()

    // Light from behind camera
    val renderer3 = new OptiXRenderer()
    renderer3.initialize()
    renderer3.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer3.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer3.setLight(Array(0.0f, 0.0f, 1.0f), 1.0f)
    val image3 = renderer3.render(width, height)
    renderer3.dispose()

    // Images should be different due to different lighting
    image1 should not equal image2
    image2 should not equal image3
    image1 should not equal image3

  it should "render different images with different sphere sizes" in:
    val (width, height) = (100, 100)

    // Small sphere
    val renderer1 = new OptiXRenderer()
    renderer1.initialize()
    renderer1.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer1.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer1.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    val image1 = renderer1.render(width, height)
    renderer1.dispose()

    // Medium sphere
    val renderer2 = new OptiXRenderer()
    renderer2.initialize()
    renderer2.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer2.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer2.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    val image2 = renderer2.render(width, height)
    renderer2.dispose()

    // Large sphere
    val renderer3 = new OptiXRenderer()
    renderer3.initialize()
    renderer3.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer3.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer3.setSphere(0.0f, 0.0f, 0.0f, 2.5f)
    val image3 = renderer3.render(width, height)
    renderer3.dispose()

    // Images should be different due to different sphere sizes
    image1 should not equal image2
    image2 should not equal image3
    image1 should not equal image3

  it should "render different images with sphere at different positions" in:
    val (width, height) = (100, 100)

    // Sphere at center
    val renderer1 = new OptiXRenderer()
    renderer1.initialize()
    renderer1.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer1.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer1.setSphere(0.0f, 0.0f, 0.0f, 1.0f)
    val image1 = renderer1.render(width, height)
    renderer1.dispose()

    // Sphere offset to right
    val renderer2 = new OptiXRenderer()
    renderer2.initialize()
    renderer2.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer2.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer2.setSphere(1.0f, 0.0f, 0.0f, 1.0f)
    val image2 = renderer2.render(width, height)
    renderer2.dispose()

    // Sphere offset up
    val renderer3 = new OptiXRenderer()
    renderer3.initialize()
    renderer3.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer3.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer3.setSphere(0.0f, 1.0f, 0.0f, 1.0f)
    val image3 = renderer3.render(width, height)
    renderer3.dispose()

    // Images should be different
    image1 should not equal image2
    image2 should not equal image3

  it should "support multiple sequential renders with separate instances" in:
    val (width, height) = (100, 100)

    // Render 10 times with different sphere sizes using separate instances
    val images = for i <- 0 until 10 yield
      val renderer = new OptiXRenderer()
      renderer.initialize()
      val radius = 0.5f + i * 0.2f
      renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
                TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
      renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
      renderer.setSphere(0.0f, 0.0f, 0.0f, radius)
      val image = renderer.render(width, height)
      renderer.dispose()
      image

    // All images should be valid
    images.foreach: image =>
      image.length shouldBe width * height * 4
      ImageAnalysis.brightnessStdDev(image, width, height) should be > 15.0  // Lower threshold for large spheres

    // Sequential images should be different
    for i <- 0 until 9 do
      images(i) should not equal images(i + 1)

  // Edge Case Tests
  it should "handle extreme FOV values" in:
    val (width, height) = (100, 100)

    // Very narrow FOV
    val renderer1 = new OptiXRenderer()
    renderer1.initialize()
    renderer1.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer1.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, 1.0f)  // 1 degree FOV
    renderer1.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    val image1 = renderer1.render(width, height)
    image1.length shouldBe width * height * 4
    renderer1.dispose()

    // Very wide FOV
    val renderer2 = new OptiXRenderer()
    renderer2.initialize()
    renderer2.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer2.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, 179.0f)  // 179 degree FOV
    renderer2.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    val image2 = renderer2.render(width, height)
    image2.length shouldBe width * height * 4
    renderer2.dispose()

  it should "handle very small sphere radius" in:
    val (width, height) = (100, 100)
    val renderer = new OptiXRenderer()
    renderer.initialize()
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.001f)  // Very small sphere
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    val image = renderer.render(width, height)
    image.length shouldBe width * height * 4
    renderer.dispose()

  it should "handle very large sphere radius" in:
    val (width, height) = (100, 100)
    val renderer = new OptiXRenderer()
    renderer.initialize()
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1000.0f)  // Very large sphere
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    val image = renderer.render(width, height)
    image.length shouldBe width * height * 4
    renderer.dispose()

  it should "handle sphere far from origin" in:
    val (width, height) = (100, 100)
    val renderer = new OptiXRenderer()
    renderer.initialize()
    renderer.setSphere(100.0f, 100.0f, 100.0f, 1.5f)  // Sphere far away
    renderer.setCamera(Array(100.0f, 100.0f, 103.0f), Array(100.0f, 100.0f, 100.0f),
              Array(0.0f, 1.0f, 0.0f), 60.0f)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    val image = renderer.render(width, height)
    image.length shouldBe width * height * 4
    renderer.dispose()

  it should "handle camera very close to sphere" in:
    val (width, height) = (100, 100)
    val renderer = new OptiXRenderer()
    renderer.initialize()
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer.setCamera(Array(0.0f, 0.0f, 0.01f), Array(0.0f, 0.0f, 0.0f),
              Array(0.0f, 1.0f, 0.0f), 60.0f)  // Very close to sphere surface
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    val image = renderer.render(width, height)
    image.length shouldBe width * height * 4
    renderer.dispose()

  it should "handle multiple initialize() calls" in:
    val renderer = new OptiXRenderer()
    renderer.initialize() shouldBe true
    renderer.initialize() shouldBe true  // Should handle re-initialization
    renderer.initialize() shouldBe true
    renderer.dispose()

  it should "handle dispose() before render()" in:
    val renderer = new OptiXRenderer()
    renderer.initialize()
    renderer.dispose()
    // Rendering after dispose should either fail gracefully or return placeholder
    noException should be thrownBy { renderer.render(100, 100) }

  it should "handle different render sizes" in:
    val renderer = new OptiXRenderer()
    renderer.initialize()
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)

    // Very small
    val image1 = renderer.render(10, 10)
    image1.length shouldBe 10 * 10 * 4

    // Square
    val image2 = renderer.render(512, 512)
    image2.length shouldBe 512 * 512 * 4

    // Widescreen
    val image3 = renderer.render(1920, 1080)
    image3.length shouldBe 1920 * 1080 * 4

    // Portrait
    val image4 = renderer.render(600, 800)
    image4.length shouldBe 600 * 800 * 4

    renderer.dispose()

  // Performance Benchmarking
  it should "achieve reasonable rendering performance" in:
    val renderer = new OptiXRenderer()
    renderer.initialize()
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)

    val width = 800
    val height = 600
    val iterations = 100

    // Warm-up renders
    for _ <- 0 until 10 do
      renderer.render(width, height)

    // Benchmark
    val start = System.nanoTime()
    for _ <- 0 until iterations do
      renderer.render(width, height)
    val elapsed = (System.nanoTime() - start) / 1e9
    val fps = iterations / elapsed

    logger.info(
      s"[Performance] Rendered $iterations frames of ${width}x$height in ${elapsed}s @${fps}fps"
    )

    // Should achieve at least 10 FPS for 800x600
    // (This is very conservative; OptiX should be much faster)
    fps should be > 10.0

    renderer.dispose()

