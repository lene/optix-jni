package io.github.lene.optix

import java.nio.file.Files

import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A scene's tone mapping applies to the whole image: to a lit surface as well as to the
  * background (optix-jni#112).
  *
  * A white sphere is lit head-on by a directional light of intensity 3, so its lit radiance
  * (ambient 0.3 + 0.7 * 3 * cos) is above 1 over most of the disc: 2.4 at the centre, 1.35 where the
  * normal is 60 degrees off. Without tone mapping the pixel clips at 255 over that whole area and
  * the sphere is a flat white disc. With tone mapping the radiance is compressed: below 255 and
  * still falling toward the rim.
  *
  * The background is a uniform HDR env map of radiance 4. Reinhard maps it to 4 / 5 = 0.8 (204),
  * once; mapped a second time it would be 0.8 / 1.8 = 0.44 (113).
  */
class ToneMappingSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val imageSize = ImageSize(400, 300)
  private val none = 0
  private val reinhard = 1
  private val aces = 2
  private val lightIntensity = 3.0f
  private val envRadiance = 4.0f
  private val backgroundPixel = (5, 5)
  // Pixels along the sphere's horizontal radius, from the centre (cos = 1) to near the rim.
  private val centre = (200, 150)
  private val midway = (240, 150)
  private val nearRim = (250, 150)
  private val byteMax = 255
  private val minimumStep = 5  // levels between neighbouring radii that count as a gradient
  private val roundingTolerance = 2

  private def render(operator: Int, exposure: Float, denoise: Boolean = false): Array[Byte] =
    renderer.clearPlanes()
    renderer.setSphere(Vector[3](0f, 0f, 0f), 0.5f)
    renderer.setSphereColor(Color(1f, 1f, 1f))
    renderer.setCamera(Vector[3](0f, 0f, 3f), Vector[3](0f, 0f, 0f), Vector[3](0f, 1f, 0f), 60f)
    renderer.setLight(Vector[3](0f, 0f, -1f), lightIntensity)
    renderer.setShadows(false)
    renderer.setAmbient(AmbientModel.Constant)
    val tmp = Files.createTempFile("tone_mapping_", ".hdr")
    try
      TestUtilities.writeHdr(tmp, 8, 4, (_, _) => (envRadiance, envRadiance, envRadiance))
      renderer.setEnvironmentMap(renderer.uploadTextureFromFile(tmp.toString))
    finally Files.deleteIfExists(tmp)
    renderer.setToneMapping(operator, exposure)
    renderer.setDenoisingEnabled(denoise)
    renderImage(imageSize)

  private def red(image: Array[Byte], pixel: (Int, Int)): Int =
    ImageValidation.getRGBAt(image, imageSize, pixel._1, pixel._2).r.toInt & 0xff

  private def reinhardOf(radiance: Double, exposure: Double = 1.0): Int =
    (radiance * exposure / (1.0 + radiance * exposure) * 255.99).toInt

  "Without tone mapping, a surface brighter than 1" should "clip to a flat white disc" in:
    val image = render(none, 1.0f)
    Seq(centre, midway, nearRim).map(red(image, _)) shouldBe Seq(byteMax, byteMax, byteMax)

  "Under Reinhard, a surface brighter than 1" should "come out below 255 and keep its gradient" in:
    val image = render(reinhard, 1.0f)
    val Seq(atCentre, atMidway, atRim) = Seq(centre, midway, nearRim).map(red(image, _))
    info(s"centre $atCentre, midway $atMidway, near the rim $atRim")
    atCentre should be < byteMax
    atCentre shouldBe reinhardOf(0.3 + 0.7 * lightIntensity) +- 3
    atCentre should be >= atMidway + minimumStep
    atMidway should be >= atRim + minimumStep

  "Under ACES, a surface brighter than 1" should "come out below 255 and keep its gradient" in:
    val image = render(aces, 1.0f)
    val Seq(atCentre, atMidway, atRim) = Seq(centre, midway, nearRim).map(red(image, _))
    info(s"centre $atCentre, midway $atMidway, near the rim $atRim")
    atCentre should be < byteMax
    atCentre should be >= atMidway + minimumStep
    atMidway should be >= atRim + minimumStep

  "The exposure" should "scale the surface radiance before it is mapped" in:
    val image = render(reinhard, 0.5f)
    red(image, centre) shouldBe reinhardOf(0.3 + 0.7 * lightIntensity, 0.5) +- 3

  "The background of a tone-mapped scene" should "be mapped once, as before" in:
    red(render(reinhard, 1.0f), backgroundPixel) shouldBe reinhardOf(envRadiance) +- roundingTolerance

  it should "be mapped once with the denoiser on, too" in:
    red(render(reinhard, 1.0f, denoise = true), backgroundPixel) shouldBe
      reinhardOf(envRadiance) +- roundingTolerance

  "Without tone mapping, the background" should "clip at 255" in:
    red(render(none, 1.0f), backgroundPixel) shouldBe byteMax
