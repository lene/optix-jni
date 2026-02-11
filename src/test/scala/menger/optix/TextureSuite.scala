package menger.optix

import scala.util.Failure
import scala.util.Success

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class TextureSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private def createTestTexture(width: Int, height: Int): Array[Byte] =
    val data = new Array[Byte](width * height * 4)
    for
      y <- 0 until height
      x <- 0 until width
    do
      val idx = (y * width + x) * 4
      data(idx) = ((x * 255) / width).toByte       // R: gradient left-right
      data(idx + 1) = ((y * 255) / height).toByte  // G: gradient top-bottom
      data(idx + 2) = 128.toByte                   // B: constant
      data(idx + 3) = 255.toByte                   // A: fully opaque
    data

  private def createCheckerTexture(width: Int, height: Int, cellSize: Int = 8): Array[Byte] =
    val data = new Array[Byte](width * height * 4)
    for
      y <- 0 until height
      x <- 0 until width
    do
      val idx = (y * width + x) * 4
      val isWhite = ((x / cellSize) + (y / cellSize)) % 2 == 0
      val value = if isWhite then 255.toByte else 0.toByte
      data(idx) = value      // R
      data(idx + 1) = value  // G
      data(idx + 2) = value  // B
      data(idx + 3) = 255.toByte  // A: fully opaque
    data

  "Texture upload" should "succeed with valid parameters" in:
    val width = 64
    val height = 64
    val imageData = createTestTexture(width, height)

    val result = renderer.uploadTexture("test_texture", imageData, width, height)
    result shouldBe a[Success[?]]
    result.get should be >= 0

  it should "return same index for same texture name" in:
    val width = 32
    val height = 32
    val imageData = createTestTexture(width, height)

    val result1 = renderer.uploadTexture("same_name", imageData, width, height)
    val result2 = renderer.uploadTexture("same_name", imageData, width, height)

    result1 shouldBe a[Success[?]]
    result2 shouldBe a[Success[?]]
    result1.get shouldBe result2.get

  it should "return different indices for different texture names" in:
    val width = 16
    val height = 16
    val imageData = createTestTexture(width, height)

    val result1 = renderer.uploadTexture("texture_a", imageData, width, height)
    val result2 = renderer.uploadTexture("texture_b", imageData, width, height)

    result1 shouldBe a[Success[?]]
    result2 shouldBe a[Success[?]]
    result1.get should not equal result2.get

  it should "handle various texture sizes" in:
    val sizes = Seq((16, 16), (64, 64), (128, 128), (256, 256), (512, 512))

    sizes.zipWithIndex.foreach { case ((w, h), idx) =>
      val imageData = createTestTexture(w, h)
      val result = renderer.uploadTexture(s"size_test_$idx", imageData, w, h)
      result shouldBe a[Success[?]]
    }

  it should "handle non-square textures" in:
    val imageData = createTestTexture(128, 64)
    val result = renderer.uploadTexture("non_square", imageData, 128, 64)
    result shouldBe a[Success[?]]

  "Texture validation" should "reject empty texture name" in:
    val imageData = createTestTexture(32, 32)
    an[IllegalArgumentException] should be thrownBy:
      renderer.uploadTexture("", imageData, 32, 32)

  it should "reject null image data" in:
    an[IllegalArgumentException] should be thrownBy:
      // scalafix:off DisableSyntax.null
      // Note: Testing null parameter handling for Java interop boundary
      renderer.uploadTexture("test", null, 32, 32)
      // scalafix:on DisableSyntax.null

  it should "reject zero width" in:
    val imageData = new Array[Byte](0)
    an[IllegalArgumentException] should be thrownBy:
      renderer.uploadTexture("test", imageData, 0, 32)

  it should "reject zero height" in:
    val imageData = new Array[Byte](0)
    an[IllegalArgumentException] should be thrownBy:
      renderer.uploadTexture("test", imageData, 32, 0)

  it should "reject negative width" in:
    val imageData = new Array[Byte](0)
    an[IllegalArgumentException] should be thrownBy:
      renderer.uploadTexture("test", imageData, -1, 32)

  it should "reject negative height" in:
    val imageData = new Array[Byte](0)
    an[IllegalArgumentException] should be thrownBy:
      renderer.uploadTexture("test", imageData, 32, -1)

  it should "reject image data size mismatch" in:
    val smallData = new Array[Byte](100)  // Not 64*64*4
    an[IllegalArgumentException] should be thrownBy:
      renderer.uploadTexture("test", smallData, 64, 64)

  "Texture release" should "not throw when called" in:
    noException should be thrownBy:
      renderer.releaseTextures()

  it should "allow new uploads after release" in:
    val imageData = createTestTexture(32, 32)

    renderer.uploadTexture("before_release", imageData, 32, 32) shouldBe a[Success[?]]
    renderer.releaseTextures()
    renderer.uploadTexture("after_release", imageData, 32, 32) shouldBe a[Success[?]]

  it should "reset texture indices after release" in:
    val imageData = createTestTexture(32, 32)

    val before = renderer.uploadTexture("reset_test", imageData, 32, 32)
    before shouldBe a[Success[?]]
    val beforeIdx = before.get

    renderer.releaseTextures()

    val after = renderer.uploadTexture("reset_test", imageData, 32, 32)
    after shouldBe a[Success[?]]
    // After release, indices start fresh (typically from 0)
    after.get shouldBe beforeIdx
