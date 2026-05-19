package menger.optix

import com.typesafe.scalalogging.LazyLogging
import menger.common.Vector
import menger.optix.ColorConstants.OPAQUE_BLUE
import menger.optix.ColorConstants.OPAQUE_RED
import menger.optix.ImageMatchers.beBlueDominant
import menger.optix.ImageMatchers.beRedDominant
import menger.optix.ThresholdConstants.TEST_IMAGE_SIZE
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class Menger4DRenderSuite extends AnyFlatSpec with Matchers with LazyLogging with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val pos     = Vector[3](0.0f, 0.0f, 0.0f)
  private val scale   = 1.5f
  private val eyeW    = 3.0f
  private val screenW = 1.5f

  "addMenger4DInstance" should "return a valid instance ID" in:
    val result = renderer.addMenger4DInstance(
      level = 0, distanceThreshold = 2,
      position = pos, scale = scale,
      eyeW = eyeW, screenW = screenW,
      rotXW = 15f, rotYW = 10f, rotZW = 0f,
      material = Material(OPAQUE_RED)
    )
    result shouldBe defined
    result.get should be >= 0

  it should "return distinct IDs for multiple instances" in:
    val id0 = renderer.addMenger4DInstance(
      0, 2, Vector[3](-1.5f, 0f, 0f), scale, eyeW, screenW, 0f, 0f, 0f,
      Material(OPAQUE_RED)
    )
    val id1 = renderer.addMenger4DInstance(
      0, 2, Vector[3](1.5f, 0f, 0f), scale, eyeW, screenW, 0f, 0f, 0f,
      Material(OPAQUE_BLUE)
    )
    id0 shouldBe defined
    id1 shouldBe defined
    id0.get should not equal id1.get

  "menger4d rendering" should "produce non-empty image data" in:
    renderer.addMenger4DInstance(
      0, 2, pos, scale, eyeW, screenW, 15f, 10f, 0f,
      Material(OPAQUE_RED)
    )
    val img = renderImage(TEST_IMAGE_SIZE)
    img.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)
    img.count(_ != 0) should be > 0

  it should "apply color: red instance has red-dominant center" in:
    renderer.addMenger4DInstance(
      0, 2, pos, scale, eyeW, screenW, 15f, 10f, 0f,
      Material(OPAQUE_RED)
    )
    val img = renderImage(TEST_IMAGE_SIZE)
    img should beRedDominant(TEST_IMAGE_SIZE)

  it should "apply color: blue instance has blue-dominant center" in:
    renderer.addMenger4DInstance(
      0, 2, pos, scale, eyeW, screenW, 15f, 10f, 0f,
      Material(OPAQUE_BLUE)
    )
    val img = renderImage(TEST_IMAGE_SIZE)
    img should beBlueDominant(TEST_IMAGE_SIZE)

  it should "render metallic chrome material" in:
    renderer.addMenger4DInstance(
      0, 2, pos, scale, eyeW, screenW, 15f, 10f, 0f,
      Material.Chrome
    )
    val img    = renderImage(TEST_IMAGE_SIZE)
    val stdDev = ImageValidation.brightnessStdDev(img, TEST_IMAGE_SIZE)
    stdDev should be > 5.0

  it should "render glass differently from opaque white" in:
    val whiteOpaque = Material(ColorConstants.OPAQUE_WHITE)

    renderer.addMenger4DInstance(0, 2, pos, scale, eyeW, screenW, 15f, 10f, 0f, Material.Glass)
    val glassImg = renderImage(TEST_IMAGE_SIZE)

    renderer.clearAllInstances()
    renderer.addMenger4DInstance(0, 2, pos, scale, eyeW, screenW, 15f, 10f, 0f, whiteOpaque)
    val opaqueImg = renderImage(TEST_IMAGE_SIZE)

    // If glass fallback to diffuse: both renders identical → diff ≈ 0.
    // With correct glass path: refraction + Fresnel differ from opaque lit surface.
    val n = TEST_IMAGE_SIZE.width * TEST_IMAGE_SIZE.height * 4
    val pixelDiff = (0 until n).map(i => math.abs((glassImg(i) & 0xff) - (opaqueImg(i) & 0xff))).sum
    pixelDiff should be > 10000

  it should "render level 2" in:
    renderer.addMenger4DInstance(
      2, 2, pos, scale, eyeW, screenW, 15f, 10f, 0f,
      Material(OPAQUE_RED)
    )
    val img = renderImage(TEST_IMAGE_SIZE)
    img should beRedDominant(TEST_IMAGE_SIZE)
