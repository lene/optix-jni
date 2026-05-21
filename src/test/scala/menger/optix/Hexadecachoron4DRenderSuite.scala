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

class Hexadecachoron4DRenderSuite extends AnyFlatSpec with Matchers with LazyLogging with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val pos     = Vector[3](0.0f, 0.0f, -5.0f)
  private val scale   = 2.0f
  private val eyeW    = 2.5f
  private val screenW = 1.0f

  "addHexadecachoron4DInstance" should "return a valid instance ID" in:
    val result = renderer.addHexadecachoron4DInstance(
      level = 1,
      position = pos, scale = scale,
      eyeW = eyeW, screenW = screenW,
      rotXW = 0f, rotYW = 0f, rotZW = 0f,
      material = Material(OPAQUE_RED)
    )
    result should be >= 0

  it should "return distinct IDs for multiple instances" in:
    val id0 = renderer.addHexadecachoron4DInstance(
      1, Vector[3](-1.5f, 0f, -5f), scale, eyeW, screenW, 0f, 0f, 0f,
      Material(OPAQUE_RED)
    )
    val id1 = renderer.addHexadecachoron4DInstance(
      1, Vector[3](1.5f, 0f, -5f), scale, eyeW, screenW, 0f, 0f, 0f,
      Material(OPAQUE_BLUE)
    )
    id0 should be >= 0
    id1 should be >= 0
    id0 should not equal id1

  "hexadecachoron4d rendering" should "produce non-empty image data" in:
    renderer.addHexadecachoron4DInstance(
      1, pos, scale, eyeW, screenW, 0f, 0f, 0f,
      Material(OPAQUE_RED)
    )
    val img = renderImage(TEST_IMAGE_SIZE)
    img.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)
    img.count(_ != 0) should be > 0

  it should "apply color: red instance has red-dominant center" in:
    renderer.addHexadecachoron4DInstance(
      1, pos, scale, eyeW, screenW, 0f, 0f, 0f,
      Material(OPAQUE_RED)
    )
    val img = renderImage(TEST_IMAGE_SIZE)
    img should beRedDominant(TEST_IMAGE_SIZE)

  it should "apply color: blue instance has blue-dominant center" in:
    renderer.addHexadecachoron4DInstance(
      1, pos, scale, eyeW, screenW, 0f, 0f, 0f,
      Material(OPAQUE_BLUE)
    )
    val img = renderImage(TEST_IMAGE_SIZE)
    img should beBlueDominant(TEST_IMAGE_SIZE)

  it should "render level 2 with more detail than level 1" in:
    renderer.addHexadecachoron4DInstance(
      1, pos, scale, eyeW, screenW, 0f, 0f, 0f,
      Material(OPAQUE_RED)
    )
    val level1Img = renderImage(TEST_IMAGE_SIZE)

    renderer.clearAllInstances()
    renderer.addHexadecachoron4DInstance(
      2, pos, scale, eyeW, screenW, 0f, 0f, 0f,
      Material(OPAQUE_RED)
    )
    val level2Img = renderImage(TEST_IMAGE_SIZE)

    // Level 2 fractal has more subdivisions: brightness variance should differ meaningfully
    val n = TEST_IMAGE_SIZE.width * TEST_IMAGE_SIZE.height * 4
    val pixelDiff = (0 until n).map(i => math.abs((level1Img(i) & 0xff) - (level2Img(i) & 0xff))).sum
    pixelDiff should be > 1000

  it should "show 4D nature: different rotXW produces different output" in:
    renderer.addHexadecachoron4DInstance(
      2, pos, scale, eyeW, screenW, rotXW = 0f, rotYW = 0f, rotZW = 0f,
      Material(OPAQUE_RED)
    )
    val rot0Img = renderImage(TEST_IMAGE_SIZE)

    renderer.clearAllInstances()
    renderer.addHexadecachoron4DInstance(
      2, pos, scale, eyeW, screenW, rotXW = 0.5f, rotYW = 0f, rotZW = 0f,
      Material(OPAQUE_RED)
    )
    val rot05Img = renderImage(TEST_IMAGE_SIZE)

    // Different 4D rotation angles must produce meaningfully different 3D cross-sections
    val n = TEST_IMAGE_SIZE.width * TEST_IMAGE_SIZE.height * 4
    val pixelDiff = (0 until n).map(i => math.abs((rot0Img(i) & 0xff) - (rot05Img(i) & 0xff))).sum
    pixelDiff should be > 1000
