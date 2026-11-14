package io.github.lene.optix

import java.nio.file.Files

import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** `setIBLSunExclusion` leaves the sun out of image-based lighting, so that a sun lit by its own
  * directional light is not counted twice, and leaves the background alone.
  *
  * The env map is a uniform dim sky with one small, very bright patch, the "sun", 38 degrees above
  * the horizon. A white horizontal plane under it has the irradiance of the sky plus the patch;
  * with the patch excluded it has that of the sky outside the cone. The reference is computed on
  * the CPU, with the cone test written here from the EnvMapMapping.h convention (u = 0.5 is +x,
  * u = 0.75 is +z, v = 0 is +y), which checks the device's convention too.
  */
class IblSunExclusionSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private type EnvMap = (Int, Int) => Float

  private val mapWidth = 128
  private val mapHeight = 64
  private val skyRadiance = 0.2f
  private val sunRadiance = 200f
  private val sunColumns = 80 to 82
  private val sunRows = 17 to 19
  private val coneHalfAngle = 8f  // degrees; holds the 3 x 3 patch (about 8 degrees across)
  private val iblSamples = 16
  private val relativeTolerance = 0.10
  private val halfLevelInIrradiance = 0.5 * math.Pi / 255.0

  private val planeSize = ImageSize(128, 128)
  private val planeRegion = TestUtilities.Region(32, 32, 96, 96)
  private val viewSize = ImageSize(64, 64)

  private def isSun(x: Int, y: Int): Boolean = sunColumns.contains(x) && sunRows.contains(y)
  private def sky(x: Int, y: Int): Float = skyRadiance
  private def skyWithSun(x: Int, y: Int): Float = if isSun(x, y) then sunRadiance else skyRadiance

  private def texelDirection(x: Int, y: Int): (Double, Double, Double) =
    val theta = (y + 0.5) / mapHeight * math.Pi
    val phi = ((x + 0.5) / mapWidth - 0.5) * 2.0 * math.Pi
    (math.sin(theta) * math.cos(phi), math.cos(theta), math.sin(theta) * math.sin(phi))

  /** Direction to the sun: the centre of the patch. */
  private val sunDirection: Vector[3] =
    val (cx, cy) = (sunColumns.sum / sunColumns.length.toDouble, sunRows.sum / sunRows.length.toDouble)
    val theta = (cy + 0.5) / mapHeight * math.Pi
    val phi = ((cx + 0.5) / mapWidth - 0.5) * 2.0 * math.Pi
    Vector[3](
      (math.sin(theta) * math.cos(phi)).toFloat, math.cos(theta).toFloat, (math.sin(theta) * math.sin(phi)).toFloat)

  private def inCone(x: Int, y: Int): Boolean =
    val (dx, dy, dz) = texelDirection(x, y)
    dx * sunDirection(0) + dy * sunDirection(1) + dz * sunDirection(2) >= math.cos(math.toRadians(coneHalfAngle.toDouble))

  /** CPU irradiance at the normal +y, over the texels not excluded. */
  private def referenceIrradiance(env: EnvMap, excludeCone: Boolean): Double =
    val terms = for
      y <- 0 until mapHeight
      x <- 0 until mapWidth
      if !(excludeCone && inCone(x, y))
    yield
      val theta = (y + 0.5) / mapHeight * math.Pi
      val (_, dy, _) = texelDirection(x, y)
      env(x, y).toDouble * math.max(0.0, dy) * math.sin(theta) * (2.0 * math.Pi / mapWidth) * (math.Pi / mapHeight)
    terms.sum

  private def identical(a: Array[Byte], b: Array[Byte]): Boolean = a.sameElements(b)

  private def loadEnv(env: EnvMap): Unit =
    val tmp = Files.createTempFile("ibl_sun_exclusion_", ".hdr")
    try
      TestUtilities.writeHdr(tmp, mapWidth, mapHeight, (x, y) =>
        val l = env(x, y)
        (l, l, l))
      renderer.setEnvironmentMap(renderer.uploadTextureFromFile(tmp.toString))
    finally Files.deleteIfExists(tmp)

  /** A white plane seen from above: every pixel has the irradiance at the normal +y. */
  private def renderPlane(env: EnvMap, exclusion: Option[Float], excludeBeforeLoading: Boolean = false)
      : Array[Byte] =
    renderer.clearPlanes()
    renderer.addPlaneSolidColor(1, true, 0.0f, 1f, 1f, 1f)
    renderer.setSphere(Vector[3](50f, 50f, 50f), 0.5f)
    renderer.setLight(Vector[3](0f, -1f, 0f), 0f)
    renderer.setShadows(false)
    renderer.setCamera(Vector[3](0f, 3f, 0f), Vector[3](0f, 0f, 0f), Vector[3](0f, 0f, -1f), 60f)
    def exclude(): Unit = exclusion.foreach(angle => renderer.setIBLSunExclusion(sunDirection, angle))
    if excludeBeforeLoading then exclude()
    loadEnv(env)
    if !excludeBeforeLoading then exclude()
    renderer.setIBL(true, 1.0f, iblSamples)
    renderImage(planeSize)

  private def meanIrradiance(image: Array[Byte]): Double =
    val values = for y <- planeRegion.y0 until planeRegion.y1; x <- planeRegion.x0 until planeRegion.x1
      yield ImageValidation.getRGBAt(image, planeSize, x, y).r / 255.0
    math.Pi * values.sum / values.length + halfLevelInIrradiance

  "IBL with the sun excluded" should "have the irradiance of the sky outside the cone, not of the sun" in:
    val measured = meanIrradiance(renderPlane(skyWithSun, Some(coneHalfAngle)))
    val expected = referenceIrradiance(skyWithSun, excludeCone = true)
    info(f"E measured = $measured%.4f, sky outside the cone = $expected%.4f, with the sun = ${referenceIrradiance(skyWithSun, false)}%.4f")
    measured shouldBe expected +- (relativeTolerance * expected + halfLevelInIrradiance)

  it should "equal IBL under the same sky without a sun, excluded the same way" in:
    val withSun = renderPlane(skyWithSun, Some(coneHalfAngle))
    val withoutSun = renderPlane(sky, Some(coneHalfAngle))
    val differences = withSun.zip(withoutSun).map((a, b) => math.abs((a & 0xff) - (b & 0xff)))
    info(s"largest pixel difference ${differences.max} levels")
    differences.max should be <= 1

  it should "not depend on whether the exclusion is set before or after the env map is loaded" in:
    val after = renderPlane(skyWithSun, Some(coneHalfAngle))
    val before = renderPlane(skyWithSun, Some(coneHalfAngle), excludeBeforeLoading = true)
    identical(before, after) shouldBe true

  "IBL without a sun exclusion" should "count the sun, and by default there is none" in:
    val measured = meanIrradiance(renderPlane(skyWithSun, None))
    val expected = referenceIrradiance(skyWithSun, excludeCone = false)
    info(f"E measured = $measured%.4f, reference = $expected%.4f")
    measured shouldBe expected +- (relativeTolerance * expected + halfLevelInIrradiance)

  "clearIBLSunExclusion" should "bring back the sun and render as if no exclusion was ever set" in:
    val never = renderPlane(skyWithSun, None)
    renderer.setIBLSunExclusion(sunDirection, coneHalfAngle)
    renderer.clearIBLSunExclusion()
    renderer.setIBL(true, 1.0f, iblSamples)
    identical(renderImage(planeSize), never) shouldBe true

  "The background" should "still show the sun when IBL leaves it out" in:
    // Camera at the origin looking at the sun: the centre pixel is the patch itself.
    def renderLookingAtSun(exclusion: Option[Float]): Array[Byte] =
      renderer.clearPlanes()
      renderer.setSphere(Vector[3](0f, -50f, 0f), 0.5f)  // out of view
      renderer.setLight(Vector[3](0f, -1f, 0f), 0f)
      renderer.setCamera(
        Vector[3](0f, 0f, 0f), sunDirection, Vector[3](0f, 1f, 0f), 20f)
      loadEnv(skyWithSun)
      exclusion.foreach(angle => renderer.setIBLSunExclusion(sunDirection, angle))
      renderer.setIBL(true, 1.0f, iblSamples)
      renderImage(viewSize)
    val centre = (viewSize.width / 2, viewSize.height / 2)
    def red(image: Array[Byte]) = ImageValidation.getRGBAt(image, viewSize, centre._1, centre._2).r
    val included = renderLookingAtSun(None)
    val excluded = renderLookingAtSun(Some(coneHalfAngle))
    info(s"centre pixel: ${red(included)} without exclusion, ${red(excluded)} with")
    red(excluded) should be >= 250  // the 200-radiance patch saturates; the sky alone would be 51
    identical(excluded, included) shouldBe true
