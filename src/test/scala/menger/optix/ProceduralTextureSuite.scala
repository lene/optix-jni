package menger.optix

import menger.common.{Color, ImageSize, Vector}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ProceduralTextureSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val imgSize = ImageSize(64, 64)
  private val white   = Color(0.8f, 0.8f, 0.8f)

  private def pixelSum(image: Array[Byte]): Long =
    image.map(_ & 0xff).map(_.toLong).sum

  "ValueNoise procedural texture" should "produce a different image than no procedural" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)
      .getOrElse(fail("addSphereInstance failed"))
    val flatSum = pixelSum(renderImage(imgSize))

    renderer.setProceduralTexture(instanceId, ProceduralType.ValueNoise, 3.0f)
    val noisySum = pixelSum(renderImage(imgSize))

    noisySum should not equal flatSum
  }

  "FBM and ValueNoise procedural textures" should "produce different images" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)
      .getOrElse(fail("addSphereInstance failed"))

    renderer.setProceduralTexture(instanceId, ProceduralType.ValueNoise, 2.0f)
    val valueNoiseSum = pixelSum(renderImage(imgSize))

    renderer.setProceduralTexture(instanceId, ProceduralType.FBM, 2.0f)
    val fbmSum = pixelSum(renderImage(imgSize))

    valueNoiseSum should not equal fbmSum
  }

  "setProceduralTexture" should "reject invalid type < 0" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)
      .getOrElse(fail("addSphereInstance failed"))
    an[IllegalArgumentException] should be thrownBy {
      renderer.setProceduralTexture(instanceId, -1, 1.0f)
    }
  }

  it should "reject invalid type > 4" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)
      .getOrElse(fail("addSphereInstance failed"))
    an[IllegalArgumentException] should be thrownBy {
      renderer.setProceduralTexture(instanceId, 5, 1.0f)
    }
  }

  it should "reject non-positive scale" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)
      .getOrElse(fail("addSphereInstance failed"))
    an[IllegalArgumentException] should be thrownBy {
      renderer.setProceduralTexture(instanceId, ProceduralType.ValueNoise, 0.0f)
    }
  }
