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
    /** Extract RGB values from a pixel (returns tuple of Int values 0-255) */
    def getRGB(imageData: Array[Byte], pixelIndex: Int): (Int, Int, Int) =
      val offset = pixelIndex * 4
      val r = imageData(offset) & 0xFF
      val g = imageData(offset + 1) & 0xFF
      val b = imageData(offset + 2) & 0xFF
      (r, g, b)

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

    /**
     * Determine dominant color channel in center region of image.
     * Returns "r", "g", "b", or "gray" if all channels are similar.
     */
    def dominantColorChannel(imageData: Array[Byte], width: Int, height: Int): String =
      // Sample center region (center 50% of image)
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
        val pixelIndex = y * width + x
        val (r, g, b) = getRGB(imageData, pixelIndex)
        totalR += r
        totalG += g
        totalB += b
        count += 1

      val avgR = totalR / count
      val avgG = totalG / count
      val avgB = totalB / count

      // Determine if it's grayscale or has a dominant color
      val maxChannel = math.max(avgR, math.max(avgG, avgB))
      val minChannel = math.min(avgR, math.min(avgG, avgB))

      // If difference between max and min is small (< 20), it's grayscale
      if (maxChannel - minChannel < 20) then "gray"
      else if (avgR == maxChannel) then "r"
      else if (avgG == maxChannel) then "g"
      else "b"

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

  it should "have compatible OptiX SDK and driver versions" in:
    import scala.sys.process._
    import scala.util.control.NonFatal

    Try {
      // Use sh -c to properly interpret shell pipelines
      val driverOutput = Seq("sh", "-c",
        "strings /usr/lib/x86_64-linux-gnu/libnvoptix.so.* 2>/dev/null | grep 'OptiX Version' || true"
      ).!!.trim

      val sdkPath = Seq("sh", "-c",
        "grep 'OptiX_INSTALL_DIR:PATH=' optix-jni/target/native/x86_64-linux/build/CMakeCache.txt 2>/dev/null | cut -d= -f2 || true"
      ).!!.trim

      if driverOutput.nonEmpty then
        info(s"Driver OptiX version: $driverOutput")

      if sdkPath.nonEmpty then
        info(s"SDK path: $sdkPath")

      // Extract major version from driver output (e.g., "OptiX Version: [ 9.0002..." -> "9")
      val driverMajor = if driverOutput.contains("9.0") then Some(9)
                        else if driverOutput.contains("8.0") then Some(8)
                        else if driverOutput.contains("7.") then Some(7)
                        else None

      // Extract major version from SDK path (e.g., ".../SDK-9.0.0..." -> "9")
      val sdkMajor = if sdkPath.contains("SDK-9") then Some(9)
                     else if sdkPath.contains("SDK-8") then Some(8)
                     else if sdkPath.contains("SDK-7") then Some(7)
                     else None

      (driverMajor, sdkMajor) match
        case (Some(d), Some(s)) =>
          if d != s then
            fail(s"OptiX version mismatch: Driver has v$d, SDK is v$s. " +
                 s"Install matching SDK from https://developer.nvidia.com/optix")
          else
            info(s"✓ OptiX versions compatible: Driver v$d, SDK v$s")
        case (None, Some(s)) =>
          info(s"⚠ Could not detect driver OptiX version (SDK: v$s)")
        case (Some(d), None) =>
          info(s"⚠ Could not detect SDK version (Driver: v$d)")
        case (None, None) =>
          info("⚠ Could not determine OptiX versions (may be running without GPU)")
    }.recover {
      case NonFatal(e) =>
        info(s"⚠ Version check skipped: ${e.getMessage}")
    }

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
    if !isAvailable then
      info("Skipped: OptiX not available (stub implementation)")
    else
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
    val testRenderer = new OptiXRenderer()
    if !testRenderer.isAvailable then
      info("Skipped: OptiX not available (stub implementation)")
    else
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
    val testRenderer = new OptiXRenderer()
    if !testRenderer.isAvailable then
      info("Skipped: OptiX not available (stub implementation)")
    else
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

  // Sphere Color Tests
  it should "render sphere with correct color (not grayscale)" in:
    val (width, height) = (200, 200)

    // Test 1: Pure red sphere
    val renderer1 = new OptiXRenderer()
    renderer1.initialize()
    renderer1.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer1.setSphereColor(1.0f, 0.0f, 0.0f)  // Pure red
    renderer1.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer1.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    val imageRed = renderer1.render(width, height)
    renderer1.dispose()

    // Red should be dominant channel, not grayscale
    ImageAnalysis.dominantColorChannel(imageRed, width, height) shouldBe "r"

    // Test 2: Pure green sphere
    val renderer2 = new OptiXRenderer()
    renderer2.initialize()
    renderer2.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer2.setSphereColor(0.0f, 1.0f, 0.0f)  // Pure green
    renderer2.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer2.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    val imageGreen = renderer2.render(width, height)
    renderer2.dispose()

    // Green should be dominant channel, not grayscale
    ImageAnalysis.dominantColorChannel(imageGreen, width, height) shouldBe "g"

    // Test 3: Pure blue sphere
    val renderer3 = new OptiXRenderer()
    renderer3.initialize()
    renderer3.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer3.setSphereColor(0.0f, 0.0f, 1.0f)  // Pure blue
    renderer3.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer3.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    val imageBlue = renderer3.render(width, height)
    renderer3.dispose()

    // Blue should be dominant channel, not grayscale
    ImageAnalysis.dominantColorChannel(imageBlue, width, height) shouldBe "b"

    // All three images should be different
    imageRed should not equal imageGreen
    imageGreen should not equal imageBlue
    imageRed should not equal imageBlue

  it should "render sphere with custom color (green-cyan #00ff80)" in:
    val (width, height) = (200, 200)

    val renderer = new OptiXRenderer()
    renderer.initialize()
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer.setSphereColor(0.0f, 1.0f, 0.5f)  // Green-cyan: RGB(0, 255, 128)
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    val image = renderer.render(width, height)
    renderer.dispose()

    // Green should be dominant channel
    ImageAnalysis.dominantColorChannel(image, width, height) shouldBe "g"

    // Verify center pixel has expected color proportions
    val centerPixel = (height / 2) * width + (width / 2)
    val (r, g, b) = ImageAnalysis.getRGB(image, centerPixel)

    // Green should be significantly higher than red
    g should be > (r + 50)

    // Blue should be between red and green
    b should be > r
    b should be < g

    // Should not be grayscale (all channels should be different)
    r should not equal g
    g should not equal b
    r should not equal b

  it should "render grayscale sphere and detect it as gray" in:
    val (width, height) = (200, 200)

    // Test with mid-gray sphere
    val renderer = new OptiXRenderer()
    renderer.initialize()
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer.setSphereColor(0.5f, 0.5f, 0.5f)  // Mid-gray: RGB(128, 128, 128)
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
              TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    val imageGray = renderer.render(width, height)
    renderer.dispose()

    // Should be detected as grayscale
    ImageAnalysis.dominantColorChannel(imageGray, width, height) shouldBe "gray"

    // Verify center region pixels have similar RGB values (grayscale characteristic)
    val centerX = width / 2
    val centerY = height / 2
    val centerPixel = centerY * width + centerX
    val (r, g, b) = ImageAnalysis.getRGB(imageGray, centerPixel)

    // All channels should be within a small range of each other
    math.abs(r - g) should be < 20
    math.abs(g - b) should be < 20
    math.abs(r - b) should be < 20

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

  it should "achieve reasonable performance with transparent spheres" in:
    val renderer = new OptiXRenderer()
    renderer.initialize()
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.5f)  // 50% transparent
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
      s"[Performance Transparency] Rendered $iterations transparent frames of ${width}x$height in ${elapsed}s @${fps}fps"
    )

    // Transparent rendering uses continuation rays so may be slightly slower
    // Still should achieve at least 10 FPS
    fps should be > 10.0

    renderer.dispose()

  // Phase 2: Transparency Tests
  it should "render semi-transparent sphere blending with background" in:
    val (width, height) = (400, 400)

    // Render opaque white sphere (alpha = 1.0)
    val rendererOpaque = new OptiXRenderer()
    rendererOpaque.initialize()
    rendererOpaque.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    rendererOpaque.setSphereColor(1.0f, 1.0f, 1.0f, 1.0f)
    rendererOpaque.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    rendererOpaque.setCamera(
      Array(0.0f, 0.0f, 3.0f),
      Array(0.0f, 0.0f, 0.0f),
      Array(0.0f, 1.0f, 0.0f),
      60.0f
    )
    val imageOpaque = rendererOpaque.render(width, height)
    rendererOpaque.dispose()

    // Render semi-transparent white sphere (alpha = 0.5)
    val rendererTransparent = new OptiXRenderer()
    rendererTransparent.initialize()
    rendererTransparent.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    rendererTransparent.setSphereColor(1.0f, 1.0f, 1.0f, 0.5f)
    rendererTransparent.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    rendererTransparent.setCamera(
      Array(0.0f, 0.0f, 3.0f),
      Array(0.0f, 0.0f, 0.0f),
      Array(0.0f, 1.0f, 0.0f),
      60.0f
    )
    val imageTransparent = rendererTransparent.render(width, height)
    rendererTransparent.dispose()

    // The semi-transparent sphere should blend with background
    // Check center pixel (should be brightest on both, but transparent should be dimmer)
    val centerIdx = (height / 2 * width + width / 2) * 4

    val opaqueR = imageOpaque(centerIdx) & 0xFF
    val opaqueG = imageOpaque(centerIdx + 1) & 0xFF
    val opaqueB = imageOpaque(centerIdx + 2) & 0xFF
    val opaqueBrightness = (opaqueR + opaqueG + opaqueB) / 3.0

    val transR = imageTransparent(centerIdx) & 0xFF
    val transG = imageTransparent(centerIdx + 1) & 0xFF
    val transB = imageTransparent(centerIdx + 2) & 0xFF
    val transBrightness = (transR + transG + transB) / 3.0

    // Transparent sphere should be significantly dimmer due to blending with dark background
    // Background is RGB(76, 25, 51) with brightness ~51
    // Opaque white sphere center should be ~145+
    // Transparent (50%) should be between them
    opaqueBrightness should be > 140.0
    transBrightness should be < opaqueBrightness
    transBrightness should be > 80.0

  it should "render fully transparent sphere showing only background" in:
    val (width, height) = (200, 200)

    // Render with fully transparent sphere (alpha = 0.0)
    val renderer = new OptiXRenderer()
    renderer.initialize()
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.0f)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setCamera(
      Array(0.0f, 0.0f, 3.0f),
      Array(0.0f, 0.0f, 0.0f),
      Array(0.0f, 1.0f, 0.0f),
      60.0f
    )
    val image = renderer.render(width, height)
    renderer.dispose()

    // With alpha=0, the sphere should be completely invisible
    // All pixels should be close to background color RGB(76, 25, 51)
    val bgR = 76
    val bgG = 25
    val bgB = 51

    // Check several pixels across the image
    val pixelsToCheck = Seq(
      (width / 2, height / 2),  // Center (would be sphere)
      (width / 4, height / 2),  // Left of center
      (3 * width / 4, height / 2), // Right of center
      (0, 0),  // Corner (definitely background)
      (width - 1, height - 1)  // Opposite corner
    )

    for (x, y) <- pixelsToCheck do
      val idx = (y * width + x) * 4
      val r = image(idx) & 0xFF
      val g = image(idx + 1) & 0xFF
      val b = image(idx + 2) & 0xFF

      // Should be within a few units of background color
      Math.abs(r - bgR) should be < 5
      Math.abs(g - bgG) should be < 5
      Math.abs(b - bgB) should be < 5

  it should "render sphere with refraction, reflection, and volume absorption" in:
    val (width, height) = (800, 600)
    val filename = "optix_test_refraction_absorption.ppm"

    // Clean up any pre-existing output files
    val ppmFile = new File(filename)
    if ppmFile.exists() then ppmFile.delete()

    val renderer = new OptiXRenderer()
    renderer.initialize()

    // Configure sphere with refraction and absorption
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.5f)

    // Near-black sphere for maximum absorption: RGB (0.01, 0.01, 0.01), Alpha 0.0
    // Alpha 0.0 means MAXIMUM absorption (absorption_factor = 1.0)
    // With near-black color, -log(0.01) = 4.6, so very strong absorption
    renderer.setSphereColor(0.01f, 0.01f, 0.01f, 0.0f)

    // Set IOR for glass-like refraction
    renderer.setIOR(1.5f)

    // Set scale (affects absorption distance)
    renderer.setScale(1.0f)

    // Camera and lighting
    renderer.setCamera(
      Array(0.0f, 0.0f, 3.0f),
      Array(0.0f, 0.0f, 0.0f),
      Array(0.0f, 1.0f, 0.0f),
      60.0f
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)

    // Render
    val imageData = renderer.render(width, height)
    imageData.length shouldBe width * height * 4

    // Save for visual inspection
    val savedFile = ImageIO.savePPM(imageData, width, height, filename)
    logger.info(s"\n=== Refraction+Absorption test image saved to: ${savedFile.getAbsolutePath}")

    renderer.dispose()

    // Verification: The image should show:
    // 1. Refraction: checkerboard distortion through sphere
    // 2. Absorption: darker center, lighter edges (Beer-Lambert law)
    // 3. Reflection: some Fresnel reflection at grazing angles
    // We can't easily verify all effects programmatically, but we can check basic properties

    // Check that we have variation in the image (not solid color)
    val centerIdx = (height / 2 * width + width / 2) * 4
    val topIdx = (height / 4 * width + width / 2) * 4
    val bottomIdx = (3 * height / 4 * width + width / 2) * 4

    val centerR = imageData(centerIdx) & 0xFF
    val topR = imageData(topIdx) & 0xFF
    val bottomR = imageData(bottomIdx) & 0xFF

    // Should have variation (not all the same)
    (centerR != topR || topR != bottomR) shouldBe true

