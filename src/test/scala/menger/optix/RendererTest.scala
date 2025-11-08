package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ColorConstants.*
import ThresholdConstants.*
import ImageMatchers.*

/**
 * Test suite for OptiX JNI bindings.
 *
 * Tests basic structure - library loading, method calls, rendering functionality,
 * edge cases, and performance benchmarking.
 */
class RendererTest extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "OptiXRenderer" should "be instantiable" in new OptiXRenderer:
    this should not be null

  it should "load native library without error" in:
    noException should be thrownBy { new OptiXRenderer() }

  it should "have compatible OptiX SDK and driver versions" in:
    import scala.sys.process.*
    import scala.util.control.NonFatal

    scala.util.Try {
      val driverOutput = Seq("sh", "-c",
        "strings /usr/lib/x86_64-linux-gnu/libnvoptix.so.* 2>/dev/null | grep 'OptiX Version' || true"
      ).!!.trim

      val sdkPath = Seq("sh", "-c",
        "grep 'OptiX_INSTALL_DIR:PATH=' optix-jni/target/native/x86_64-linux/build/CMakeCache.txt 2>/dev/null | cut -d= -f2 || true"
      ).!!.trim

      if driverOutput.nonEmpty then info(s"Driver OptiX version: $driverOutput")
      if sdkPath.nonEmpty then info(s"SDK path: $sdkPath")

      val driverMajor = if driverOutput.contains("9.0") then Some(9)
                        else if driverOutput.contains("8.0") then Some(8)
                        else if driverOutput.contains("7.") then Some(7)
                        else None

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
        case (None, Some(s)) => info(s"⚠ Could not detect driver OptiX version (SDK: v$s)")
        case (Some(d), None) => info(s"⚠ Could not detect SDK version (Driver: v$d)")
        case (None, None) => info("⚠ Could not determine OptiX versions (may be running without GPU)")
    }.recover {
      case NonFatal(e) => info(s"⚠ Version check skipped: ${e.getMessage}")
    }

  it should "allow initialize() to be called without crashing" in new OptiXRenderer:
    noException should be thrownBy { initialize() }

  it should "return true from initialize()" in new OptiXRenderer:
    initialize() shouldBe true

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
    val result = render(STANDARD_IMAGE_SIZE._1, STANDARD_IMAGE_SIZE._2)
    result should not be null

  it should "return correct size array from render() (RGBA)" in new OptiXRenderer:
    initialize()
    val result = render(STANDARD_IMAGE_SIZE._1, STANDARD_IMAGE_SIZE._2)
    val expectedSize = STANDARD_IMAGE_SIZE._1 * STANDARD_IMAGE_SIZE._2 * 4
    result.length shouldBe expectedSize

  it should "allow dispose() to be called without crashing" in new OptiXRenderer:
    initialize()
    noException should be thrownBy { dispose() }

  it should "allow dispose() to be called multiple times safely" in new OptiXRenderer:
    initialize()
    dispose()
    noException should be thrownBy { dispose() }

  it should "return a boolean from isAvailable" in new OptiXRenderer:
    val available = isAvailable
    available shouldBe a[Boolean]

  it should "support full workflow: init -> configure -> render -> dispose" in new OptiXRenderer:
    initialize() shouldBe true

    setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    setCamera(Array(0.0f, 0.5f, 3.0f), Array(0.0f, 0.0f, 0.0f), Array(0.0f, 1.0f, 0.0f), 60.0f)
    setLight(Array(0.5f, 0.5f, -0.5f), 1.0f)

    val image = render(100, 100)
    image.length shouldBe 100 * 100 * 4

    dispose()

  it should "save rendered output as PPM for visual inspection" in:
    TestScenario.default()
      .withSphereRadius(0.5f)
      .applyTo(renderer)

    val imageData = renderer.render(STANDARD_IMAGE_SIZE._1, STANDARD_IMAGE_SIZE._2)
    imageData.length shouldBe STANDARD_IMAGE_SIZE._1 * STANDARD_IMAGE_SIZE._2 * 4

    val savedFile = TestUtilities.savePPM("optix_test_output.ppm", imageData,
      STANDARD_IMAGE_SIZE._1, STANDARD_IMAGE_SIZE._2).get

    logger.info(s"\n=== Rendered image saved to: ${savedFile.getAbsolutePath}")

    savedFile.exists() shouldBe true
    val expectedSize = s"P6\n${STANDARD_IMAGE_SIZE._1} ${STANDARD_IMAGE_SIZE._2}\n255\n".length +
      STANDARD_IMAGE_SIZE._1 * STANDARD_IMAGE_SIZE._2 * 3
    savedFile.length() shouldBe expectedSize

  "Camera position" should "produce different images" in:
    val (width, height) = (100, 100)

    // Render from default position (front)
    TestScenario.default()
      .withSphereRadius(1.5f)
      .withCameraEye(0.0f, 0.0f, 3.0f)
      .applyTo(renderer)
    val image1 = renderer.render(width, height)

    // Render from side
    TestScenario.default()
      .withSphereRadius(1.5f)
      .withCameraEye(3.0f, 0.0f, 0.0f)
      .applyTo(renderer)
    val image2 = renderer.render(width, height)

    // Render from top
    TestScenario.default()
      .withSphereRadius(1.5f)
      .withCameraEye(0.0f, 3.0f, 0.0f)
      .withCameraUp(0.0f, 0.0f, -1.0f)
      .applyTo(renderer)
    val image3 = renderer.render(width, height)

    // All should have brightness variation
    ImageValidation.brightnessStdDev(image1, width, height) should be > MIN_BRIGHTNESS_VARIATION
    ImageValidation.brightnessStdDev(image2, width, height) should be > MIN_BRIGHTNESS_VARIATION
    ImageValidation.brightnessStdDev(image3, width, height) should be > MIN_BRIGHTNESS_VARIATION

    // Valid sizes
    image1.length shouldBe ImageValidation.imageByteSize(width, height)
    image2.length shouldBe ImageValidation.imageByteSize(width, height)
    image3.length shouldBe ImageValidation.imageByteSize(width, height)

  "Light direction" should "produce different images" in:
    val (width, height) = (100, 100)

    // Light from top-right
    TestScenario.default()
      .withSphereColor(OPAQUE_LIGHT_GRAY)
      .withSphereRadius(1.5f)
      .withIOR(1.0f)
      .withLightDirection(0.5f, 0.5f, -0.5f)
      .applyTo(renderer)
    val image1 = renderer.render(width, height)

    // Light from left
    TestScenario.default()
      .withSphereColor(OPAQUE_LIGHT_GRAY)
      .withSphereRadius(1.5f)
      .withIOR(1.0f)
      .withLightDirection(-1.0f, 0.0f, 0.0f)
      .applyTo(renderer)
    val image2 = renderer.render(width, height)

    // Light from behind camera
    TestScenario.default()
      .withSphereColor(OPAQUE_LIGHT_GRAY)
      .withSphereRadius(1.5f)
      .withIOR(1.0f)
      .withLightDirection(0.0f, 0.0f, 1.0f)
      .applyTo(renderer)
    val image3 = renderer.render(width, height)

    // Images should be different
    image1 should not equal image2
    image2 should not equal image3
    image1 should not equal image3

  "Sphere size" should "produce different images" in:
    val (width, height) = (100, 100)

    TestScenario.default().withSphereRadius(0.5f).applyTo(renderer)
    val image1 = renderer.render(width, height)

    TestScenario.default().withSphereRadius(1.5f).applyTo(renderer)
    val image2 = renderer.render(width, height)

    TestScenario.default().withSphereRadius(2.5f).applyTo(renderer)
    val image3 = renderer.render(width, height)

    image1 should not equal image2
    image2 should not equal image3
    image1 should not equal image3

  "Sphere position" should "produce different images" in:
    val (width, height) = (100, 100)

    TestScenario.default().withSpherePosition(0.0f, 0.0f, 0.0f).applyTo(renderer)
    val image1 = renderer.render(width, height)

    TestScenario.default().withSpherePosition(1.0f, 0.0f, 0.0f).applyTo(renderer)
    val image2 = renderer.render(width, height)

    TestScenario.default().withSpherePosition(0.0f, 1.0f, 0.0f).applyTo(renderer)
    val image3 = renderer.render(width, height)

    image1 should not equal image2
    image2 should not equal image3

  "Sequential renders" should "support multiple renders with different configurations" in:
    val (width, height) = (100, 100)

    val images = for i <- 0 until 10 yield
      val radius = 0.5f + i * 0.1f
      TestScenario.default().withSphereRadius(radius).applyTo(renderer)
      renderer.render(width, height)

    // All images should be valid
    images.foreach { image =>
      image.length shouldBe ImageValidation.imageByteSize(width, height)
      image should haveBrightnessStdDevGreaterThan(5.0, width, height)
    }

    // Sequential images should be different
    for i <- 0 until 9 do
      images(i) should not equal images(i + 1)

  "Edge cases" should "handle extreme FOV values" in:
    val (width, height) = (100, 100)

    // Very narrow FOV
    TestScenario.default().withFOV(1.0f).applyTo(renderer)
    val image1 = renderer.render(width, height)
    image1.length shouldBe ImageValidation.imageByteSize(width, height)

    // Very wide FOV
    TestScenario.default().withFOV(179.0f).applyTo(renderer)
    val image2 = renderer.render(width, height)
    image2.length shouldBe ImageValidation.imageByteSize(width, height)

  it should "handle very small sphere radius" in:
    TestScenario.default().withSphereRadius(0.001f).applyTo(renderer)
    val image = renderer.render(100, 100)
    image.length shouldBe 100 * 100 * 4

  it should "handle very large sphere radius" in:
    TestScenario.default().withSphereRadius(1000.0f).applyTo(renderer)
    val image = renderer.render(100, 100)
    image.length shouldBe 100 * 100 * 4

  it should "handle sphere far from origin" in:
    TestScenario.default()
      .withSpherePosition(100.0f, 100.0f, 100.0f)
      .withSphereRadius(1.5f)
      .withCameraEye(100.0f, 100.0f, 103.0f)
      .withCameraLookAt(100.0f, 100.0f, 100.0f)
      .applyTo(renderer)
    val image = renderer.render(100, 100)
    image.length shouldBe 100 * 100 * 4

  it should "handle camera very close to sphere" in:
    TestScenario.default()
      .withSphereRadius(1.5f)
      .withCameraEye(0.0f, 0.0f, 0.01f)
      .applyTo(renderer)
    val image = renderer.render(100, 100)
    image.length shouldBe 100 * 100 * 4

  it should "handle multiple initialize() calls" in new OptiXRenderer:
    initialize() shouldBe true
    initialize() shouldBe true
    initialize() shouldBe true
    dispose()

  it should "handle dispose() before render()" in new OptiXRenderer:
    initialize()
    dispose()
    noException should be thrownBy { render(100, 100) }

  it should "handle different render sizes" in:
    TestScenario.default().applyTo(renderer)

    val image1 = renderer.render(10, 10)
    image1.length shouldBe 10 * 10 * 4

    val image2 = renderer.render(512, 512)
    image2.length shouldBe 512 * 512 * 4

    val image3 = renderer.render(1920, 1080)
    image3.length shouldBe 1920 * 1080 * 4

    val image4 = renderer.render(600, 800)
    image4.length shouldBe 600 * 800 * 4

  "Sphere color" should "render correct color (not grayscale)" in:
    val (width, height) = (200, 200)

    // Pure red sphere
    TestScenario.default()
      .withSphereColor(OPAQUE_RED)
      .withSphereRadius(1.5f)
      .withIOR(1.0f)
      .applyTo(renderer)
    val imageRed = renderer.render(width, height)
    imageRed should beRedDominant(width, height)

    // Pure green sphere
    TestScenario.default()
      .withSphereColor(OPAQUE_GREEN)
      .withSphereRadius(1.5f)
      .withIOR(1.0f)
      .applyTo(renderer)
    val imageGreen = renderer.render(width, height)
    imageGreen should beGreenDominant(width, height)

    // Pure blue sphere
    TestScenario.default()
      .withSphereColor(OPAQUE_BLUE)
      .withSphereRadius(1.5f)
      .withIOR(1.0f)
      .applyTo(renderer)
    val imageBlue = renderer.render(width, height)
    imageBlue should beBlueDominant(width, height)

    // All three images should be different
    imageRed should not equal imageGreen
    imageGreen should not equal imageBlue
    imageRed should not equal imageBlue

  it should "render sphere with custom color (green-cyan #00ff80)" in:
    val (width, height) = (200, 200)

    TestScenario.default()
      .withSphereColor(0.0f, 1.0f, 0.5f, 1.0f)
      .withSphereRadius(1.5f)
      .withIOR(1.0f)
      .applyTo(renderer)
    val image = renderer.render(width, height)

    image should beGreenDominant(width, height)

    // Verify center pixel proportions
    val centerPixel = (height / 2) * width + (width / 2)
    val rgb = ImageValidation.getRGB(image, centerPixel)

    rgb.g should be > (rgb.r + MIN_COLOR_CHANNEL_DIFFERENCE)
    rgb.b should be > rgb.r
    rgb.b should be < rgb.g

    rgb.r should not equal rgb.g
    rgb.g should not equal rgb.b
    rgb.r should not equal rgb.b

  it should "render grayscale sphere and detect it as gray" in:
    val (width, height) = (200, 200)

    TestScenario.default()
      .withSphereColor(OPAQUE_MEDIUM_GRAY)
      .withSphereRadius(1.5f)
      .applyTo(renderer)
    val imageGray = renderer.render(width, height)

    imageGray should beGrayscale(width, height)

    // Verify center pixel is grayscale
    val centerPixel = (height / 2) * width + (width / 2)
    val rgb = ImageValidation.getRGB(imageGray, centerPixel)

    math.abs(rgb.r - rgb.g) should be < GRAYSCALE_TOLERANCE
    math.abs(rgb.g - rgb.b) should be < GRAYSCALE_TOLERANCE
    math.abs(rgb.r - rgb.b) should be < GRAYSCALE_TOLERANCE

  it should "support integer color API (0-255 range)" in:
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0, 255, 0, 128)  // Integer version: green, 50% opacity
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TEST_IMAGE_SIZE._1, TEST_IMAGE_SIZE._2)

    // Green should be dominant channel
    val (r, g, b) = ImageValidation.colorChannelRatio(
      imageData,
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )

    g should be > r
    g should be > b

  "Performance" should "achieve reasonable rendering performance" in:
    TestScenario.default()
      .withSphereRadius(1.5f)
      .applyTo(renderer)

    val width = 800
    val height = 600
    val iterations = 100

    // Warm-up
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

    fps should be > MIN_FPS

  it should "achieve reasonable performance with transparent spheres" in:
    TestScenario.default()
      .withSphereColor(SEMI_TRANSPARENT_WHITE)
      .withSphereRadius(1.5f)
      .applyTo(renderer)

    val width = 800
    val height = 600
    val iterations = 100

    // Warm-up
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

    fps should be > MIN_FPS

  "Transparency" should "render semi-transparent sphere blending with background" in:
    val (width, height) = (400, 400)

    // Opaque white sphere
    TestScenario.default()
      .withSphereColor(OPAQUE_WHITE)
      .withSphereRadius(1.5f)
      .withIOR(1.0f)
      .withCameraEye(0.0f, 0.0f, 3.0f)
      .applyTo(renderer)
    val imageOpaque = renderer.render(width, height)

    // Semi-transparent white sphere
    TestScenario.default()
      .withSphereColor(SEMI_TRANSPARENT_WHITE)
      .withSphereRadius(1.5f)
      .withIOR(1.0f)
      .withCameraEye(0.0f, 0.0f, 3.0f)
      .applyTo(renderer)
    val imageTransparent = renderer.render(width, height)

    val centerIdx = height / 2 * width + width / 2

    val opaqueBrightness = ImageValidation.brightness(imageOpaque, centerIdx)
    val transBrightness = ImageValidation.brightness(imageTransparent, centerIdx)

    opaqueBrightness should be >= 20.0
    transBrightness should be >= 20.0
    imageOpaque should not equal imageTransparent

  it should "render fully transparent sphere showing only background" in:
    val (width, height) = (200, 200)

    TestScenario.default()
      .withSphereColor(FULLY_TRANSPARENT_WHITE)
      .withSphereRadius(1.5f)
      .withIOR(1.0f)
      .withCameraEye(0.0f, 0.0f, 3.0f)
      .applyTo(renderer)
    val image = renderer.render(width, height)

    // Center should show background, not white sphere
    val centerIdx = height / 2 * width + width / 2
    val rgb = ImageValidation.getRGB(image, centerIdx)

    val isBackground = (rgb.r != 255 || rgb.g != 255 || rgb.b != 255) &&
                       (Math.abs(rgb.r - rgb.g) < BACKGROUND_GRAYSCALE_TOLERANCE &&
                        Math.abs(rgb.g - rgb.b) < BACKGROUND_GRAYSCALE_TOLERANCE)

    if !isBackground then
      logger.info(f"Alpha=0.0 center pixel: RGB(${rgb.r}%d, ${rgb.g}%d, ${rgb.b}%d)")

    isBackground should be (true)

  "Refraction and absorption" should "render with all optical effects combined" in:
    val (width, height) = (800, 600)

    TestScenario.default()
      .withSphereColor(0.5f, 1.0f, 0.5f, 0.8f)  // Green-tinted glass
      .withSphereRadius(1.5f)
      .withIOR(1.5f)
      .withCameraEye(0.0f, 0.0f, 3.0f)
      .applyTo(renderer)

    renderer.setScale(1.0f)

    val imageData = renderer.render(width, height)
    imageData.length shouldBe ImageValidation.imageByteSize(width, height)

    // Check variation (refraction, absorption, reflection)
    val centerIdx = height / 2 * width + width / 2
    val topIdx = height / 4 * width + width / 2
    val bottomIdx = 3 * height / 4 * width + width / 2

    val centerR = imageData(centerIdx * 4) & 0xFF
    val topR = imageData(topIdx * 4) & 0xFF
    val bottomR = imageData(bottomIdx * 4) & 0xFF

    (centerR != topR || topR != bottomR) shouldBe true

  it should "render small sphere with refraction" in:
    TestScenario.default()
      .withSphereRadius(0.5f)
      .withIOR(1.5f)
      .withCameraEye(0.0f, 0.0f, 3.0f)
      .applyTo(renderer)

    renderer.setScale(1.0f)

    val imageData = renderer.render(800, 600)
    imageData.length shouldBe 800 * 600 * 4
