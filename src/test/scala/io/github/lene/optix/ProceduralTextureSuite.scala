package io.github.lene.optix

import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
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
    val flatSum = pixelSum(renderImage(imgSize))

    renderer.setProceduralTexture(instanceId, ProceduralType.ValueNoise, 3.0f)
    val noisySum = pixelSum(renderImage(imgSize))

    noisySum should not equal flatSum
  }

  "FBM and ValueNoise procedural textures" should "produce different images" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)

    renderer.setProceduralTexture(instanceId, ProceduralType.ValueNoise, 2.0f)
    val valueNoiseSum = pixelSum(renderImage(imgSize))

    renderer.setProceduralTexture(instanceId, ProceduralType.FBM, 2.0f)
    val fbmSum = pixelSum(renderImage(imgSize))

    valueNoiseSum should not equal fbmSum
  }

  "setProceduralTexture" should "reject invalid type < 0" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)
    an[IllegalArgumentException] should be thrownBy {
      renderer.setProceduralTexture(instanceId, -1, 1.0f)
    }
  }

  it should "reject invalid type > 10" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)
    an[IllegalArgumentException] should be thrownBy {
      renderer.setProceduralTexture(instanceId, 11, 1.0f)
    }
  }

  "HeatMap procedural texture" should "produce a different image than no procedural" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)
    val flatSum = pixelSum(renderImage(imgSize))

    renderer.setProceduralTexture(instanceId, ProceduralType.HeatMap, 1.0f)
    val heatSum = pixelSum(renderImage(imgSize))

    heatSum should not equal flatSum
  }

  it should "produce a different image than plain FBM" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)

    renderer.setProceduralTexture(instanceId, ProceduralType.FBM, 1.0f)
    val fbmSum = pixelSum(renderImage(imgSize))

    renderer.setProceduralTexture(instanceId, ProceduralType.HeatMap, 1.0f)
    val heatSum = pixelSum(renderImage(imgSize))

    heatSum should not equal fbmSum
  }

  "XYZToRGB procedural texture" should "produce a different image than no procedural" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)
    val flatSum = pixelSum(renderImage(imgSize))

    renderer.setProceduralTexture(instanceId, ProceduralType.XYZToRGB, 1.0f)
    val xyzSum = pixelSum(renderImage(imgSize))

    xyzSum should not equal flatSum
  }

  it should "produce a different image than wood" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)

    renderer.setProceduralTexture(instanceId, ProceduralType.Wood, 1.0f)
    val woodSum = pixelSum(renderImage(imgSize))

    renderer.setProceduralTexture(instanceId, ProceduralType.XYZToRGB, 1.0f)
    val xyzSum = pixelSum(renderImage(imgSize))

    xyzSum should not equal woodSum
  }

  "Wood procedural texture" should "produce a different image than marble" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)

    renderer.setProceduralTexture(instanceId, ProceduralType.Wood, 1.0f)
    val woodSum = pixelSum(renderImage(imgSize))

    renderer.setProceduralTexture(instanceId, ProceduralType.Marble, 1.0f)
    val marbleSum = pixelSum(renderImage(imgSize))

    woodSum should not equal marbleSum
  }

  "LayeredNoise procedural texture" should "produce a different image than plain FBM" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)

    renderer.setProceduralTexture(instanceId, ProceduralType.FBM, 1.0f)
    val fbmSum = pixelSum(renderImage(imgSize))

    renderer.setProceduralTexture(instanceId, ProceduralType.LayeredNoise, 1.0f)
    val layeredSum = pixelSum(renderImage(imgSize))

    layeredSum should not equal fbmSum
  }

  it should "reject non-positive scale" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)
    an[IllegalArgumentException] should be thrownBy {
      renderer.setProceduralTexture(instanceId, ProceduralType.ValueNoise, 0.0f)
    }
  }

  "Triplanar procedural texture" should "produce a different image than no procedural" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)
    val flatSum = pixelSum(renderImage(imgSize))

    renderer.setProceduralTexture(instanceId, ProceduralType.Triplanar, 1.0f)
    val triplanarSum = pixelSum(renderImage(imgSize))

    triplanarSum should not equal flatSum
  }

  it should "produce a different image than plain FBM" in {
    val instanceId = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), white, 1.5f)

    renderer.setProceduralTexture(instanceId, ProceduralType.FBM, 1.0f)
    val fbmSum = pixelSum(renderImage(imgSize))

    renderer.setProceduralTexture(instanceId, ProceduralType.Triplanar, 1.0f)
    val triplanarSum = pixelSum(renderImage(imgSize))

    triplanarSum should not equal fbmSum
  }

  "Cone procedural texture" should "produce a different image than no procedural" in {
    val apex = Vector[3](0f, 1f, 0f)
    val base = Vector[3](0f, -1f, 0f)
    val instanceId = renderer.addConeInstance(apex, base, 0.8f, Material(white, 1.0f))
    val flatSum = pixelSum(renderImage(imgSize))

    renderer.setProceduralTexture(instanceId, ProceduralType.ValueNoise, 3.0f)
    val noisySum = pixelSum(renderImage(imgSize))

    noisySum should not equal flatSum
  }

  "Plane procedural texture" should "produce a different image than no procedural" in {
    val normal = Vector[3](0f, 1f, 0f)
    val instanceId = renderer.addPlaneInstance(normal, -1.5f, Material(white, 1.0f))
    val flatSum = pixelSum(renderImage(imgSize))

    renderer.setProceduralTexture(instanceId, ProceduralType.ValueNoise, 3.0f)
    val noisySum = pixelSum(renderImage(imgSize))

    noisySum should not equal flatSum
  }
