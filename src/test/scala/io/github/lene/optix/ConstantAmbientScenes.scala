package io.github.lene.optix

import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector

/** The scenes whose renders under the constant ambient fill are committed as PNGs in
  * `test-resources/ambient-constant/`, rendered by optix-jni b13c283 (before the occlusion
  * ambient existed). Changing a scene here changes what the references must show: regenerate
  * them as that README says.
  */
object ConstantAmbientScenes:

  val imageSize: ImageSize = ImageSize(320, 240)

  /** name -> scene builder (the renderer is initialised and has the fixture's defaults). */
  val all: Seq[(String, OptiXRenderer => Unit)] = Seq(
    "sponge" -> (r => sponge(r, Vector[3](0.577f, -0.577f, 0.577f))),
    "sponge-backlit" -> (r => sponge(r, Vector[3](-0.5f, -0.2f, 0.81f))),
    "glass-gold-cylinder" -> glassGoldCylinder
  )

  private def common(r: OptiXRenderer, light: Vector[3]): Unit =
    r.clearPlanes()  // the fixture's default floor at y = -2
    r.updateImageDimensions(imageSize)
    r.setCamera(Vector[3](-1.6f, 1.4f, 2.2f), Vector[3](0f, -0.1f, 0f), Vector[3](0f, 1f, 0f), 60f)
    r.setLight(light, 1.0f)
    r.setShadows(true)
    r.addPlaneInstance(Vector[3](0f, 1f, 0f), -0.5f, Material.matte(Color(0.7f, 0.7f, 0.7f)))

  private def sponge(r: OptiXRenderer, light: Vector[3]): Unit =
    common(r, light)
    r.setTriangleMesh(TestUtilities.createUnitCubeMesh())
    val c = 1f / 3f
    for i <- -1 to 1; j <- -1 to 1; k <- -1 to 1 if math.abs(i) + math.abs(j) + math.abs(k) > 1 do
      r.addTriangleMeshInstance(Array(c, 0f, 0f, i * c, 0f, c, 0f, j * c, 0f, 0f, c, k * c),
        Material.plastic(Color(0.6f, 0.6f, 0.6f)), TextureIndex.NoTexture)

  private def glassGoldCylinder(r: OptiXRenderer): Unit =
    common(r, Vector[3](0.3f, -1f, -0.2f))
    r.addSphereInstance(Array(0.5f, 0f, 0f, 0f, 0f, 0.5f, 0f, 0f, 0f, 0f, 0.5f, 0f), Material.Glass)
    r.addSphereInstance(Array(0.3f, 0f, 0f, 0.9f, 0f, 0.3f, 0f, -0.2f, 0f, 0f, 0.3f, 0.3f), Material.Gold)
    r.addCylinderInstance(Vector[3](-0.9f, -0.5f, 0f), Vector[3](-0.9f, 0.3f, 0f), 0.15f,
      Material.matte(Color(0.2f, 0.8f, 0.3f)))
