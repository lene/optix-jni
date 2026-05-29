package io.github.lene.optix
import com.typesafe.scalalogging.LazyLogging
import menger.common.Color
import menger.common.Const
import menger.common.Vector
import io.github.lene.optix.ColorConstants.FULLY_TRANSPARENT_WHITE
import io.github.lene.optix.ColorConstants.OPAQUE_BLUE
import io.github.lene.optix.ColorConstants.OPAQUE_GREEN
import io.github.lene.optix.ColorConstants.OPAQUE_LIGHT_GRAY
import io.github.lene.optix.ColorConstants.OPAQUE_MEDIUM_GRAY
import io.github.lene.optix.ColorConstants.OPAQUE_RED
import io.github.lene.optix.ColorConstants.OPAQUE_WHITE
import io.github.lene.optix.ColorConstants.SEMI_TRANSPARENT_WHITE
import io.github.lene.optix.ImageMatchers.beBlueDominant
import io.github.lene.optix.ImageMatchers.beGrayscale
import io.github.lene.optix.ImageMatchers.beGreenDominant
import io.github.lene.optix.ImageMatchers.beRedDominant
import io.github.lene.optix.ImageMatchers.haveBrightnessStdDevGreaterThan
import io.github.lene.optix.ThresholdConstants.GRAYSCALE_TOLERANCE
import io.github.lene.optix.ThresholdConstants.MIN_BRIGHTNESS_VARIATION
import io.github.lene.optix.ThresholdConstants.MIN_COLOR_CHANNEL_DIFFERENCE
import io.github.lene.optix.ThresholdConstants.QUICK_TEST_SIZE
import io.github.lene.optix.ThresholdConstants.STANDARD_IMAGE_SIZE
import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers


class RendererTest extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "OptiXRenderer" should "be instantiable" in new OptiXRenderer:
    this shouldBe a[OptiXRenderer]

  it should "load native library without error" in:
    noException should be thrownBy { new OptiXRenderer() }

  it should "have compatible OptiX SDK and driver versions" in:
    import scala.sys.process.Process
    import scala.util.control.NonFatal

    scala.util.Try {
      val driverOutput = Process(Seq("sh", "-c",
        "strings /usr/lib/x86_64-linux-gnu/libnvoptix.so.* 2>/dev/null | grep 'OptiX Version' || true"
      )).!!.trim

      val sdkPath = Process(Seq("sh", "-c",
        "grep 'OptiX_INSTALL_DIR:PATH=' optix-jni/target/native/x86_64-linux/build/CMakeCache.txt 2>/dev/null | cut -d= -f2 || true"
      )).!!.trim

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
                 "Install matching SDK from https://developer.nvidia.com/optix")
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
    noException should be thrownBy { setSphere(Vector[3](0.0f, 0.0f, 0.0f), 1.5f) }

  it should "allow setCamera() to be called without crashing" in new OptiXRenderer:
    initialize()
    val eye = Vector[3](0.0f, 0.0f, 3.0f)
    val lookAt = Vector[3](0.0f, 0.0f, 0.0f)
    val up = Vector[3](0.0f, 1.0f, 0.0f)
    noException should be thrownBy { setCamera(eye, lookAt, up, 60.0f) }

  it should "allow setLight() to be called without crashing" in new OptiXRenderer:
    initialize()
    val direction = Vector[3](0.5f, 0.5f, -0.5f)
    noException should be thrownBy { setLight(direction, 1.0f) }

  it should "return non-null from render()" in new OptiXRenderer:
    initialize()
    val result = render(STANDARD_IMAGE_SIZE)
    result should not be null // scalafix:ok DisableSyntax.null

  it should "return correct size array from render() (RGBA)" in new OptiXRenderer:
    initialize()
    val result = render(STANDARD_IMAGE_SIZE)
    val expectedSize = ImageValidation.imageByteSize(STANDARD_IMAGE_SIZE)
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

    setSphere(Vector[3](0.0f, 0.0f, 0.0f), 0.5f)
    setCamera(Vector[3](0.0f, 0.5f, 3.0f), Vector[3](0.0f, 0.0f, 0.0f), Vector[3](0.0f, 1.0f, 0.0f), 60.0f)
    setLight(Vector[3](0.5f, 0.5f, -0.5f), 1.0f)

    val image = render(100, 100)
    image.length shouldBe 100 * 100 * 4

    dispose()

  it should "save rendered output as PPM for visual inspection" in:
    TestScenario.default()
      .withSphereRadius(0.5f)
      .applyTo(renderer)

    val imageData = renderer.render(STANDARD_IMAGE_SIZE)
    imageData.length shouldBe ImageValidation.imageByteSize(STANDARD_IMAGE_SIZE)

    val savedFile = TestUtilities.savePNG("optix_test_output.png", imageData,
      STANDARD_IMAGE_SIZE.width, STANDARD_IMAGE_SIZE.height).get

    savedFile.exists() shouldBe true
    savedFile.length() should be > 0L

  "Camera position" should "produce different images" in:
    val size = QUICK_TEST_SIZE

    // Render from default position (front)
    TestScenario.default()
      .withSphereRadius(Const.defaultSphereRadius)
      .withCameraEye(Vector[3](0.0f, 0.0f, 3.0f))
      .applyTo(renderer)
    val image1 = renderer.render(size)

    // Render from side
    TestScenario.default()
      .withSphereRadius(Const.defaultSphereRadius)
      .withCameraEye(Vector[3](3.0f, 0.0f, 0.0f))
      .applyTo(renderer)
    val image2 = renderer.render(size)

    // Render from top
    TestScenario.default()
      .withSphereRadius(Const.defaultSphereRadius)
      .withCameraEye(Vector[3](0.0f, 3.0f, 0.0f))
      .withCameraUp(Vector[3](0.0f, 0.0f, -1.0f))
      .applyTo(renderer)
    val image3 = renderer.render(size)

    // All should have brightness variation
    ImageValidation.brightnessStdDev(image1, size) should be > MIN_BRIGHTNESS_VARIATION
    ImageValidation.brightnessStdDev(image2, size) should be > MIN_BRIGHTNESS_VARIATION
    ImageValidation.brightnessStdDev(image3, size) should be > MIN_BRIGHTNESS_VARIATION

    // Valid sizes
    image1.length shouldBe ImageValidation.imageByteSize(size)
    image2.length shouldBe ImageValidation.imageByteSize(size)
    image3.length shouldBe ImageValidation.imageByteSize(size)

  "Light direction" should "produce different images" in:
    val size = QUICK_TEST_SIZE

    // Light from top-right
    TestScenario.default()
      .withSphereColor(OPAQUE_LIGHT_GRAY)
      .withSphereRadius(Const.defaultSphereRadius)
      .withIOR(1.0f)
      .withLightDirection(Vector[3](0.5f, 0.5f, -0.5f))
      .applyTo(renderer)
    val image1 = renderer.render(size)

    // Light from left
    TestScenario.default()
      .withSphereColor(OPAQUE_LIGHT_GRAY)
      .withSphereRadius(Const.defaultSphereRadius)
      .withIOR(1.0f)
      .withLightDirection(Vector[3](-1.0f, 0.0f, 0.0f))
      .applyTo(renderer)
    val image2 = renderer.render(size)

    // Light from behind camera
    TestScenario.default()
      .withSphereColor(OPAQUE_LIGHT_GRAY)
      .withSphereRadius(Const.defaultSphereRadius)
      .withIOR(1.0f)
      .withLightDirection(Vector[3](0.0f, 0.0f, 1.0f))
      .applyTo(renderer)
    val image3 = renderer.render(size)

    // Images should be different
    image1 should not equal image2
    image2 should not equal image3
    image1 should not equal image3

  "Sphere size" should "produce different images" in:
    val size = QUICK_TEST_SIZE

    TestScenario.default().withSphereRadius(0.5f).applyTo(renderer)
    val image1 = renderer.render(size)

    TestScenario.default().withSphereRadius(Const.defaultSphereRadius).applyTo(renderer)
    val image2 = renderer.render(size)

    TestScenario.default().withSphereRadius(2.5f).applyTo(renderer)
    val image3 = renderer.render(size)

    image1 should not equal image2
    image2 should not equal image3
    image1 should not equal image3

  "Sphere position" should "produce different images" in:
    val size = QUICK_TEST_SIZE

    TestScenario.default().withSpherePosition(Vector[3](0.0f, 0.0f, 0.0f)).applyTo(renderer)
    val image1 = renderer.render(size)

    TestScenario.default().withSpherePosition(Vector[3](1.0f, 0.0f, 0.0f)).applyTo(renderer)
    val image2 = renderer.render(size)

    TestScenario.default().withSpherePosition(Vector[3](0.0f, 1.0f, 0.0f)).applyTo(renderer)
    val image3 = renderer.render(size)

    image1 should not equal image2
    image2 should not equal image3

  "Sequential renders" should "support multiple renders with different configurations" in:
    val size = QUICK_TEST_SIZE

    val images = for i <- 0 until 10 yield
      val radius = 0.5f + i * 0.1f
      TestScenario.default().withSphereRadius(radius).applyTo(renderer)
      renderer.render(size)

    // All images should be valid
    images.foreach { image =>
      image.length shouldBe ImageValidation.imageByteSize(size)
      image should haveBrightnessStdDevGreaterThan(5.0, size)
    }

    // Sequential images should be different
    for i <- 0 until 9 do
      images(i) should not equal images(i + 1)

  "Edge cases" should "handle extreme FOV values" in:
    val size = QUICK_TEST_SIZE

    // Very narrow FOV
    TestScenario.default().withHorizontalFOV(1.0f).applyTo(renderer)
    val image1 = renderer.render(size)
    image1.length shouldBe ImageValidation.imageByteSize(size)

    // Very wide FOV
    TestScenario.default().withHorizontalFOV(179.0f).applyTo(renderer)
    val image2 = renderer.render(size)
    image2.length shouldBe ImageValidation.imageByteSize(size)

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
      .withSpherePosition(Vector[3](100.0f, 100.0f, 100.0f))
      .withSphereRadius(Const.defaultSphereRadius)
      .withCameraEye(Vector[3](100.0f, 100.0f, 103.0f))
      .withCameraLookAt(Vector[3](100.0f, 100.0f, 100.0f))
      .applyTo(renderer)
    val image = renderer.render(100, 100)
    image.length shouldBe 100 * 100 * 4

  it should "handle camera very close to sphere" in:
    TestScenario.default()
      .withSphereRadius(Const.defaultSphereRadius)
      .withCameraEye(Vector[3](0.0f, 0.0f, 0.01f))
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
    val imageSize = QUICK_TEST_SIZE

    // Pure red sphere
    TestScenario.default()
      .withSphereColor(OPAQUE_RED)
      .withSphereRadius(Const.defaultSphereRadius)
      .withIOR(1.0f)
      .applyTo(renderer)
    val imageRed = renderer.render(imageSize)
    imageRed should beRedDominant(imageSize)

    // Pure green sphere
    TestScenario.default()
      .withSphereColor(OPAQUE_GREEN)
      .withSphereRadius(Const.defaultSphereRadius)
      .withIOR(1.0f)
      .applyTo(renderer)
    val imageGreen = renderer.render(imageSize)
    imageGreen should beGreenDominant(imageSize)

    // Pure blue sphere
    TestScenario.default()
      .withSphereColor(OPAQUE_BLUE)
      .withSphereRadius(Const.defaultSphereRadius)
      .withIOR(1.0f)
      .applyTo(renderer)
    val imageBlue = renderer.render(imageSize)
    imageBlue should beBlueDominant(imageSize)

    // All three images should be different
    imageRed should not equal imageGreen
    imageGreen should not equal imageBlue
    imageRed should not equal imageBlue

  it should "render sphere with custom color (green-cyan #00ff80)" in:
    val imageSize = QUICK_TEST_SIZE

    TestScenario.default()
      .withSphereColor(Color(0.0f, 1.0f, 0.5f, 1.0f))
      .withSphereRadius(Const.defaultSphereRadius)
      .withIOR(1.0f)
      .applyTo(renderer)
    val image = renderer.render(imageSize)

    image should beGreenDominant(imageSize)

    // Verify center pixel proportions
    val centerPixel = (imageSize.height / 2) * imageSize.width + (imageSize.width / 2)
    val rgb = ImageValidation.getRGB(image, centerPixel)

    rgb.g should be > (rgb.r + MIN_COLOR_CHANNEL_DIFFERENCE)
    rgb.b should be > rgb.r
    rgb.b should be < rgb.g

    rgb.r should not equal rgb.g
    rgb.g should not equal rgb.b
    rgb.r should not equal rgb.b

  it should "render grayscale sphere and detect it as gray" in:
    val imageSize = QUICK_TEST_SIZE

    TestScenario.default()
      .withSphereColor(OPAQUE_MEDIUM_GRAY)
      .withSphereRadius(Const.defaultSphereRadius)
      .applyTo(renderer)
    val imageGray = renderer.render(imageSize)

    imageGray should beGrayscale(imageSize)

    // Verify center pixel is grayscale
    val centerPixel = (imageSize.height / 2) * imageSize.width + (imageSize.width / 2)
    val rgb = ImageValidation.getRGB(imageGray, centerPixel)

    math.abs(rgb.r - rgb.g) should be < GRAYSCALE_TOLERANCE
    math.abs(rgb.g - rgb.b) should be < GRAYSCALE_TOLERANCE
    math.abs(rgb.r - rgb.b) should be < GRAYSCALE_TOLERANCE

  it should "support integer color API (0-255 range)" in:
    renderer.updateImageDimensions(TEST_IMAGE_SIZE)
    renderer.setCamera(Vector[3](0.0f, 0.5f, 3.0f), Vector[3](0.0f, 0.0f, 0.0f), Vector[3](0.0f, 1.0f, 0.0f), 60.0f)
    renderer.setLight(Vector[3](0.5f, 0.5f, -0.5f), 1.0f)
    renderer.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 1.5f)  // Larger sphere to fill more of the image
    renderer.setSphereColor(Color.fromRGBA(0, 255, 0, 255))  // Integer version: green, fully opaque
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TEST_IMAGE_SIZE)

    // Green should be dominant channel
    val ratio = ImageValidation.colorChannelRatio(
      imageData,
      TEST_IMAGE_SIZE
    )

    ratio.g should be > ratio.r
    ratio.g should be > ratio.b

  "Transparency" should "render semi-transparent sphere blending with background" in:
    val size = TEST_IMAGE_SIZE

    // Opaque white sphere
    TestScenario.default()
      .withSphereColor(OPAQUE_WHITE)
      .withSphereRadius(Const.defaultSphereRadius)
      .withIOR(1.0f)
      .withCameraEye(Vector[3](0.0f, 0.0f, 3.0f))
      .applyTo(renderer)
    val imageOpaque = renderer.render(size)

    // Semi-transparent white sphere
    TestScenario.default()
      .withSphereColor(SEMI_TRANSPARENT_WHITE)
      .withSphereRadius(Const.defaultSphereRadius)
      .withIOR(1.0f)
      .withCameraEye(Vector[3](0.0f, 0.0f, 3.0f))
      .applyTo(renderer)
    val imageTransparent = renderer.render(size)

    val centerIdx = size.height / 2 * size.width + size.width / 2

    val opaqueBrightness = ImageValidation.brightness(imageOpaque, centerIdx)
    val transBrightness = ImageValidation.brightness(imageTransparent, centerIdx)

    opaqueBrightness should be >= 20.0
    transBrightness should be >= 20.0
    imageOpaque should not equal imageTransparent

  it should "render fully transparent sphere showing only background" in:
    val size = QUICK_TEST_SIZE

    TestScenario.default()
      .withSphereColor(FULLY_TRANSPARENT_WHITE)
      .withSphereRadius(Const.defaultSphereRadius)
      .withIOR(1.0f)
      .withCameraEye(Vector[3](0.0f, 0.0f, 3.0f))
      .applyTo(renderer)
    val image = renderer.render(size)

    // Center should show background, not the opaque sphere color (pure white)
    val centerIdx = size.height / 2 * size.width + size.width / 2
    val rgb = ImageValidation.getRGB(image, centerIdx)

    // A fully transparent sphere is invisible; the center pixel must not be pure white
    (rgb.r != 255 || rgb.g != 255 || rgb.b != 255) should be (true)

  "Refraction and absorption" should "render with all optical effects combined" in:
    val size = ThresholdConstants.STANDARD_IMAGE_SIZE

    TestScenario.default()
      .withSphereColor(Color(0.5f, 1.0f, 0.5f, 0.8f))  // Green-tinted glass
      .withSphereRadius(Const.defaultSphereRadius)
      .withIOR(Const.iorGlass)
      .withCameraEye(Vector[3](0.0f, 0.0f, 3.0f))
      .applyTo(renderer)

    renderer.setScale(1.0f)

    val imageData = renderer.render(size)
    imageData.length shouldBe ImageValidation.imageByteSize(size)

    // Check variation across 5 vertical samples spanning the sphere
    // A refracting glass sphere must produce different colors at different heights —
    // sampling 5 rows makes a coincidental tie across all 5 astronomically unlikely.
    val col = size.width / 2
    val rows = Seq(1, 2, 3, 4, 5).map(n => n * size.height / 6)
    val rValues = rows.map(row => imageData((row * size.width + col) * 4) & 0xFF)

    rValues.distinct.size should be > 1

  it should "render small sphere with refraction" in:
    val size = ThresholdConstants.STANDARD_IMAGE_SIZE

    TestScenario.default()
      .withSphereRadius(0.5f)
      .withIOR(Const.iorGlass)
      .withCameraEye(Vector[3](0.0f, 0.0f, 3.0f))
      .applyTo(renderer)

    renderer.setScale(1.0f)

    val imageData = renderer.render(size)
    imageData.length shouldBe ImageValidation.imageByteSize(size)

  // Caustics (Progressive Photon Mapping) tests

  "Caustics" should "allow setCaustics to be called without crashing" in new OptiXRenderer:
    initialize()
    noException should be thrownBy:
      setCaustics(enabled = true, photonsPerIter = 10000, iterations = 5, initialRadius = 0.1f, alpha = 0.7f)

  it should "allow enableCaustics convenience method" in new OptiXRenderer:
    initialize()
    noException should be thrownBy:
      enableCaustics()

  it should "allow disableCaustics convenience method" in new OptiXRenderer:
    initialize()
    noException should be thrownBy:
      disableCaustics()

  it should "render with caustics enabled (basic smoke test)" in:
    TestScenario.default()
      .withSphereRadius(0.5f)
      .withIOR(Const.iorGlass)
      .withCameraEye(Vector[3](0.0f, 0.5f, 3.0f))
      .applyTo(renderer)

    // Enable caustics with minimal parameters for faster test
    renderer.setCaustics(enabled = true, photonsPerIter = 1000, iterations = 2, initialRadius = 0.1f, alpha = 0.7f)

    // Small render to keep test fast
    val size = QUICK_TEST_SIZE
    val imageData = renderer.render(size)

    // Verify valid image output
    imageData.length shouldBe ImageValidation.imageByteSize(size)
    imageData should haveBrightnessStdDevGreaterThan(MIN_BRIGHTNESS_VARIATION, size)

  it should "produce valid image with caustics and shadows" in:
    TestScenario.default()
      .withSphereRadius(0.5f)
      .withIOR(Const.iorGlass)
      .withCameraEye(Vector[3](0.0f, 0.5f, 3.0f))
      .applyTo(renderer)

    renderer.setShadows(true)
    renderer.setCaustics(enabled = true, photonsPerIter = 1000, iterations = 2, initialRadius = 0.1f, alpha = 0.7f)

    val size = QUICK_TEST_SIZE
    val imageData = renderer.render(size)

    imageData.length shouldBe ImageValidation.imageByteSize(size)

  it should "allow toggling caustics on and off" in:
    TestScenario.default()
      .withSphereRadius(0.5f)
      .withIOR(Const.iorGlass)
      .applyTo(renderer)

    val size = QUICK_TEST_SIZE

    // Render without caustics
    renderer.setCaustics(enabled = false, photonsPerIter = 0, iterations = 0, initialRadius = 0.0f, alpha = 0.0f)
    val imageNoCaustics = renderer.render(size)
    imageNoCaustics.length shouldBe ImageValidation.imageByteSize(size)

    // Render with caustics
    renderer.setCaustics(enabled = true, photonsPerIter = 1000, iterations = 2, initialRadius = 0.1f, alpha = 0.7f)
    val imageWithCaustics = renderer.render(size)
    imageWithCaustics.length shouldBe ImageValidation.imageByteSize(size)

    // Disable again
    renderer.disableCaustics()
    val imageDisabled = renderer.render(size)
    imageDisabled.length shouldBe ImageValidation.imageByteSize(size)
