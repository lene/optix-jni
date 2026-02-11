package menger.optix.caustics

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

import scala.math.max
import scala.math.min
import scala.math.sqrt

import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Vector
import menger.optix.OptiXRenderer
import menger.optix.RendererFixture
import menger.optix.Slow
import menger.optix.TestScenario
import menger.optix.TestUtilities
import menger.optix.ThresholdConstants
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Phase 1: Test infrastructure for caustics brightness validation against PBRT reference.
  *
  * These tests compare our OptiX PPM implementation against ground truth PBRT BDPT renders
  * to validate caustic brightness and pattern correctness.
  *
  * @see
  *   optix-jni/test-resources/caustics-references/README.md for reference documentation
  * @see
  *   optix-jni/CAUSTICS_ANALYSIS.md Phase 0 for investigation plan
  */
class CausticsReferenceSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  /** Reference scene parameters matching reference-scene.pbrt */
  object ReferenceScene:
    val sphereCenter: Vector[3] = Vector[3](0.0f, 0.0f, 0.0f)
    val sphereRadius: Float = 1.0f
    val sphereIOR: Float = Const.iorGlass
    val sphereColor: Color = Color(1.0f, 1.0f, 1.0f, 0.5f) // Alpha 0.5 = glass
    val planeY: Float = Const.defaultFloorPlaneY
    val planeColor: Color = Color(0.8f, 0.8f, 0.8f, 1.0f) // Light gray
    val cameraPosition: Vector[3] = Vector[3](0.0f, 4.0f, 8.0f)
    val cameraLookAt: Vector[3] = Vector[3](0.0f, 0.0f, 0.0f)
    val cameraUp: Vector[3] = Vector[3](0.0f, -1.0f, 0.0f) // Inverted for correct orientation
    val lightPosition: Vector[3] = Vector[3](0.0f, 10.0f, 0.0f)
    val lightIntensity: Float = 500.0f
    val imageSize: ImageSize = ThresholdConstants.QUICK_TEST_SIZE

  /** Sets up the reference scene on the renderer */
  private def setupReferenceScene(): Unit =
    TestScenario.default()
      .withSpherePosition(ReferenceScene.sphereCenter)
      .withSphereRadius(ReferenceScene.sphereRadius)
      .withSphereColor(ReferenceScene.sphereColor)
      .withIOR(ReferenceScene.sphereIOR)
      .withPlane(1, true, ReferenceScene.planeY)
      .withLightDirection(Vector[3](0.0f, -1.0f, 0.0f))
      .withLightIntensity(ReferenceScene.lightIntensity)
      .withCameraEye(ReferenceScene.cameraPosition)
      .withCameraLookAt(ReferenceScene.cameraLookAt)
      .withCameraUp(ReferenceScene.cameraUp)
      .withHorizontalFOV(45.0f)
      .applyTo(renderer)

  /** Load PNG image from test resources */
  private def loadReferenceImage(filename: String): BufferedImage =
    val resourcePath =
      s"test-resources/caustics-references/$filename"
    val file = new File(resourcePath)
    require(file.exists(), s"Reference image not found: ${file.getAbsolutePath}")
    ImageIO.read(file)

  /** Convert BufferedImage to RGBA byte array matching OptiX format */
  private def imageToByteArray(img: BufferedImage): Array[Byte] =
    val width = img.getWidth
    val height = img.getHeight
    val bytes = new Array[Byte](width * height * 4)

    for
      y <- 0 until height
      x <- 0 until width
    do
      val rgb = img.getRGB(x, y)
      val idx = (y * width + x) * 4
      bytes(idx) = ((rgb >> 16) & 0xff).toByte // R
      bytes(idx + 1) = ((rgb >> 8) & 0xff).toByte // G
      bytes(idx + 2) = (rgb & 0xff).toByte // B
      bytes(idx + 3) = ((rgb >> 24) & 0xff).toByte // A

    bytes

  /** Calculate average brightness in a region (returns 0.0 to 1.0) */
  private def regionBrightness(
      image: Array[Byte],
      size: ImageSize,
      x: Int,
      y: Int,
      width: Int,
      height: Int
  ): Double =
    val region = TestUtilities.Region(x, y, x + width, y + height)
    TestUtilities.regionBrightness(image, size, region) / 255.0

  /** Calculate peak brightness in a region */
  private def regionPeakBrightness(
      image: Array[Byte],
      size: ImageSize,
      x: Int,
      y: Int,
      width: Int,
      height: Int
  ): Double =
    val samples = for
      dy <- 0 until height
      dx <- 0 until width
      px = x + dx
      py = y + dy
      if px >= 0 && px < size.width && py >= 0 && py < size.height
    yield
      val idx = (py * size.width + px) * 4
      val r = (image(idx) & 0xff) / 255.0
      val g = (image(idx + 1) & 0xff) / 255.0
      val b = (image(idx + 2) & 0xff) / 255.0
      (r + g + b) / 3.0

    samples.maxOption.getOrElse(0.0)

  /** Detect caustic region by finding brightest area on floor */
  private def detectCausticRegion(
      image: Array[Byte],
      size: ImageSize
  ): (Int, Int, Int, Int) =
    // Sample bottom 40% of image (where floor is)
    val searchTop = (size.height * 0.6).toInt
    val searchHeight = size.height - searchTop
    val searchWidth = size.width

    // Find brightest 200x200 region
    val regionSize = 200
    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    var maxBrightness = 0.0
    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    var bestX = size.width / 2 - regionSize / 2
    @SuppressWarnings(Array("org.wartremover.warts.Var"))
    var bestY = searchTop + searchHeight / 2 - regionSize / 2

    for
      y <- searchTop until (size.height - regionSize) by 20
      x <- 0 until (size.width - regionSize) by 20
    do
      val brightness = regionBrightness(image, size, x, y, regionSize, regionSize)
      if brightness > maxBrightness then
        maxBrightness = brightness
        bestX = x
        bestY = y

    (bestX, bestY, regionSize, regionSize)

  // ===========================================================================
  // Baseline Tests - Verify current implementation produces caustics
  // ===========================================================================

  behavior of "Baseline Caustics"

  it should "produce visible caustics with current implementation" taggedAs (Slow) in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")

    setupReferenceScene()
    renderer.enableCaustics(photonsPerIter = 10000, iterations = 1)

    val result = renderer.renderWithStats(ReferenceScene.imageSize)

    // Detect caustic region
    val (x, y, w, h) = detectCausticRegion(result.image, ReferenceScene.imageSize)

    val causticBrightness = regionBrightness(result.image, ReferenceScene.imageSize, x, y, w, h)
    val peakBrightness = regionPeakBrightness(result.image, ReferenceScene.imageSize, x, y, w, h)

    info(s"Caustic region: ($x, $y, $w, $h)")
    info(s"Average brightness: $causticBrightness")
    info(s"Peak brightness: $peakBrightness")

    // Should have measurable caustic contribution
    causticBrightness should be > 0.01
    peakBrightness should be > 0.05

  it should "produce brighter caustics than background floor" taggedAs (Slow) in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")

    setupReferenceScene()
    renderer.enableCaustics(photonsPerIter = 10000, iterations = 1)

    val result = renderer.renderWithStats(ReferenceScene.imageSize)

    // Sample caustic region (detected)
    val (causticX, causticY, causticW, causticH) =
      detectCausticRegion(result.image, ReferenceScene.imageSize)
    val causticBrightness =
      regionBrightness(result.image, ReferenceScene.imageSize, causticX, causticY, causticW, causticH)

    // Sample background floor (left edge, away from caustic)
    val bgX = 10
    val bgY = (ReferenceScene.imageSize.height * 0.8).toInt
    val bgBrightness = regionBrightness(result.image, ReferenceScene.imageSize, bgX, bgY, 100, 100)

    info(s"Caustic brightness: $causticBrightness")
    info(s"Background brightness: $bgBrightness")

    causticBrightness should be > bgBrightness

  // ===========================================================================
  // Reference Comparison Tests - Compare against PBRT ground truth
  // ===========================================================================

  behavior of "Reference Comparison"

  it should "load PBRT reference image successfully" in:
    val refImage = loadReferenceImage("pbrt-reference.png")

    refImage.getWidth shouldBe 800
    refImage.getHeight shouldBe 600

  it should "detect caustic region in reference image" in:
    val refImage = loadReferenceImage("pbrt-reference.png")
    val refBytes = imageToByteArray(refImage)
    val size = ImageSize(refImage.getWidth, refImage.getHeight)

    val (x, y, w, h) = detectCausticRegion(refBytes, size)
    val brightness = regionBrightness(refBytes, size, x, y, w, h)
    val peak = regionPeakBrightness(refBytes, size, x, y, w, h)

    info(s"Reference caustic region: ($x, $y, $w, $h)")
    info(s"Reference average brightness: $brightness")
    info(s"Reference peak brightness: $peak")

    // Reference should have strong caustics
    brightness should be > 0.1
    peak should be > 0.3

  it should "compare caustic brightness within 50% of reference (relaxed initial target)" taggedAs (Slow) in:
    pending // Requires reference image at 400x300 resolution (TEST_IMAGE_SIZE)

  it should "eventually match reference brightness within 20% (Phase 2 target)" taggedAs (Slow) in:
    pending // This is our Phase 2 goal after improvements

  // ===========================================================================
  // Improvement Tracking Tests - Will pass after each Phase 2 step
  // ===========================================================================

  behavior of "Improvement Tracking"

  it should "show improvement after increasing MAX_PHOTON_BOUNCES" taggedAs (Slow) in:
    pending // Will implement during Phase 2.1

  it should "show improvement after increasing MAX_TRACE_DEPTH" taggedAs (Slow) in:
    pending // Will implement during Phase 2.2

  it should "show improvement after adjusting scale factor" taggedAs (Slow) in:
    pending // Will implement during Phase 2.3

  it should "show improvement after radiance normalization fix" taggedAs (Slow) in:
    pending // Will implement during Phase 2.4

end CausticsReferenceSuite

/** Tag for slow tests that render images */
object Slow extends org.scalatest.Tag("Slow")
