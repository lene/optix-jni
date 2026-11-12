package io.github.lene.optix

import java.nio.file.Files

import menger.common.ImageSize
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Image-based lighting lights from where the background shows the env map's bright spot
  * (optix-jni#112). The background maps u = 0.5 + atan2(z, x) / 2pi, so u = 0.5 is +x, 0.75 is +z
  * (toward the camera) and 0.25 is -z (behind the sphere). Before the fix IBL used azimuth 2pi*u
  * and lit the sphere from the opposite side.
  */
class IblAzimuthSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val imageSize = ImageSize(400, 300)
  private val mapWidth = 64
  private val mapHeight = 32
  private val bandRadiance = 8.0f
  private val bandHalfWidth = 0.05
  private val minRatio = 2.0

  // The camera frame is u = up x w, so with the camera at +z looking at the origin the image
  // is x-mirrored: the left half of the image shows the sphere's +x side.
  // The sphere (radius 0.5, camera at distance ~3) covers about 57 px around the image centre.
  private val centre = (imageSize.width / 2, imageSize.height / 2)
  private val leftHalf = TestUtilities.Region(centre._1 - 50, centre._2 - 20, centre._1 - 5, centre._2 + 20)
  private val rightHalf = TestUtilities.Region(centre._1 + 5, centre._2 - 20, centre._1 + 50, centre._2 + 20)
  private val centreRegion = TestUtilities.Region.centered(centre._1, centre._2, 20)

  /** Renders the default sphere lit only by an env map that is black except for a bright band
    * of columns around texture coordinate `bandU`. */
  private def renderLitFromBand(bandU: Double): Array[Byte] =
    val tmp = Files.createTempFile("ibl_azimuth_", ".hdr")
    try
      TestUtilities.writeHdr(tmp, mapWidth, mapHeight, (x, _) =>
        val u = (x + 0.5) / mapWidth
        if math.abs(u - bandU) <= bandHalfWidth then (bandRadiance, bandRadiance, bandRadiance)
        else (0.0f, 0.0f, 0.0f))
      renderer.clearPlanes()
      renderer.setLight(Vector[3](0f, -1f, 0f), 0f)
      renderer.setEnvironmentMap(renderer.uploadTextureFromFile(tmp.toString))
      renderer.setIBL(true, 1.0f, 8)
      renderImage(imageSize)
    finally Files.deleteIfExists(tmp)

  private def brightness(image: Array[Byte], region: TestUtilities.Region): Double =
    TestUtilities.regionBrightness(image, imageSize, region)

  "IBL" should "light the +x side of the sphere from a bright spot at u = 0.5" in:
    val image = renderLitFromBand(0.5)
    val (plusX, minusX) = (brightness(image, leftHalf), brightness(image, rightHalf))
    info(s"+x side (image left) = $plusX, -x side (image right) = $minusX")
    plusX should be > minRatio * minusX

  it should "light the camera-facing side from a bright spot at u = 0.75 (+z)" in:
    val towardCamera = brightness(renderLitFromBand(0.75), centreRegion)
    val behindSphere = brightness(renderLitFromBand(0.25), centreRegion)
    info(s"+z band = $towardCamera, -z band = $behindSphere")
    towardCamera should be > minRatio * behindSphere
