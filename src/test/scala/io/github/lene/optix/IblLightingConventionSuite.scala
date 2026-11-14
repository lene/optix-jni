package io.github.lene.optix

import java.nio.file.Files

import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** What image-based lighting changes about the other light of a scene, and what it leaves alone.
  *
  * With IBL on the environment is the sky: there is no constant ambient fill (and no occlusion
  * rays for it), and a directional light is not scaled by the blend factor that makes room for the
  * fill. With IBL off every pixel is what it was before IBL's physical scaling: ambient 0.3 plus
  * 0.7 times the direct light, whether or not an env map is loaded.
  */
class IblLightingConventionSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val imageSize = ImageSize(200, 150)
  private val centre = (imageSize.width / 2, imageSize.height / 2)
  private val ambientFactor = 0.3
  private val diffuseBlend = 0.7
  private val lightIntensity = 0.5f
  private val mapWidth = 64
  private val mapHeight = 32
  private val tolerance = 2.0 / 255.0

  /** A patch of radiance 5 behind the sphere (-z, u = 0.25) on a black env: the camera-facing
    * hemisphere of the sphere sees none of it.
    */
  private def envBehind(x: Int, y: Int): (Float, Float, Float) =
    val inPatch = math.abs((x + 0.5) / mapWidth - 0.25) <= 0.07 && math.abs((y + 0.5) / mapHeight - 0.5) <= 0.07
    if inPatch then (5f, 5f, 5f) else (0f, 0f, 0f)

  /** White sphere seen from +z, lit by a light travelling -z (so at the centre cos = 1). */
  private def render(iblEnabled: Boolean, envMap: Boolean, light: Float): Array[Byte] =
    renderer.clearPlanes()
    renderer.setLight(Vector[3](0f, 0f, -1f), light)
    renderer.setShadows(false)
    renderer.setAmbient(AmbientModel.Constant)
    renderer.setSphereColor(Color(1f, 1f, 1f))
    renderer.setCamera(Vector[3](0f, 0f, 3f), Vector[3](0f, 0f, 0f), Vector[3](0f, 1f, 0f), 60f)
    if envMap then
      val tmp = Files.createTempFile("ibl_convention_", ".hdr")
      try
        TestUtilities.writeHdr(tmp, mapWidth, mapHeight, envBehind)
        renderer.setEnvironmentMap(renderer.uploadTextureFromFile(tmp.toString))
      finally Files.deleteIfExists(tmp)
    renderer.setIBL(iblEnabled, 1.0f, 8)
    renderImage(imageSize)

  private def centreRed(image: Array[Byte]): Double =
    ImageValidation.getRGBAt(image, imageSize, centre._1, centre._2).r / 255.0

  "IBL on" should "leave a surface the environment does not light black: no ambient fill" in:
    centreRed(render(iblEnabled = true, envMap = true, light = 0f)) shouldBe 0.0 +- tolerance

  it should "not scale a directional light by the blend factor" in:
    centreRed(render(iblEnabled = true, envMap = true, light = lightIntensity)) shouldBe
      lightIntensity.toDouble +- tolerance

  "IBL off" should "give ambient 0.3 plus 0.7 times the light, as before" in:
    val expected = ambientFactor + diffuseBlend * lightIntensity
    centreRed(render(iblEnabled = false, envMap = true, light = lightIntensity)) shouldBe expected +- tolerance
    centreRed(render(iblEnabled = false, envMap = false, light = 0f)) shouldBe ambientFactor +- tolerance

  it should "render a loaded env map exactly like no env map at all" in:
    val withMap = render(iblEnabled = false, envMap = true, light = lightIntensity)
    val without = render(iblEnabled = false, envMap = false, light = lightIntensity)
    // the background differs (it shows the env map); the sphere does not
    val sphere = for
      y <- 0 until imageSize.height
      x <- 0 until imageSize.width
      if math.hypot(x - centre._1, y - centre._2) < 20.0
    yield (x, y)
    sphere.foreach: (x, y) =>
      ImageValidation.getRGBAt(withMap, imageSize, x, y) shouldBe ImageValidation.getRGBAt(without, imageSize, x, y)
