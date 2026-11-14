package io.github.lene.optix

import java.nio.file.Files

import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Image-based lighting returns the irradiance E = integral of L(w) cos(theta) dw of the env map.
  *
  * Reference values are computed on the CPU from the env map written by the test, with the
  * EnvMapMapping.h convention (u = 0.5 is +x, u = 0.75 is +z, v = 0 is +y). A white matte sphere,
  * constant ambient, no direct light and no shadows isolate the IBL term: a pixel is
  * `ambient + DIFFUSE_BLEND_FACTOR * E` (no 1/pi: the renderer's convention, like a directional
  * light's `intensity * cos`), so `E = (pixel - ambient pixel) / DIFFUSE_BLEND_FACTOR`.
  *
  * Before the fix the env sample was weighted by a one-sample MIS factor
  * `pdf_env^2 / (pdf_env^2 + pdf_bsdf^2)` although no BSDF sample is ever taken. That weight is 1
  * for a small bright patch but 0.06..0.5 for a smooth env: a uniform env lit the sphere with
  * 0.177 of its irradiance.
  */
class IblIrradianceSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private type EnvMap = (Int, Int) => Float
  private type Pixel = (Int, Int)

  private val imageSize = ImageSize(400, 300)
  private val mapWidth = 64
  private val mapHeight = 32
  private val iblSamples = 64  // the maximum
  // Pixels clamp at 1: every env below keeps ambient 0.3 + 0.7 * E under 1 over the sphere.
  private val diffuseBlend = 0.7  // 1 - ambient factor 0.3
  private val relativeTolerance = 0.10
  private val ambientOnlyTolerance = 3.0 / 255.0  // pixel units of the IBL term, off the black side
  private val verticalFovTan = math.tan(math.toRadians(30.0))
  private val sphereRadius = 0.5
  private val cameraDistance = 3.0

  // Camera at (0, 0, 3) looking down -z. Regions are symmetric about x = 0, so the image mirroring
  // of the camera frame does not matter.
  private val top = TestUtilities.Region(185, 112, 215, 128)
  private val middle = TestUtilities.Region(185, 140, 215, 160)
  private val bottom = TestUtilities.Region(185, 172, 215, 188)

  private def uniform(radiance: Float): EnvMap = (_, _) => radiance

  /** Radiance `radiance` above the horizon (v < 0.5), black below. */
  private def upperSky(radiance: Float): EnvMap = (_, y) =>
    if (y + 0.5) / mapHeight < 0.5 then radiance else 0.0f

  /** One bright patch centred on texture coordinates (uc, vc), black elsewhere. */
  private def patch(uc: Double, vc: Double, halfWidth: Double, radiance: Float): EnvMap = (x, y) =>
    val inPatch =
      math.abs((x + 0.5) / mapWidth - uc) <= halfWidth && math.abs((y + 0.5) / mapHeight - vc) <= halfWidth
    if inPatch then radiance else 0.0f

  private val plusY = patch(0.5, 0.1, 0.07, 3.0f)
  private val plusZ = patch(0.75, 0.5, 0.07, 2.0f)
  private val minusZ = patch(0.25, 0.5, 0.07, 2.0f)

  private def render(env: EnvMap, ibl: Boolean): Array[Byte] =
    renderer.clearPlanes()
    renderer.setLight(Vector[3](0f, -1f, 0f), 0f)
    renderer.setShadows(false)
    renderer.setAmbient(AmbientModel.Constant)
    renderer.setSphereColor(Color(1f, 1f, 1f))
    renderer.setCamera(
      Vector[3](0f, 0f, cameraDistance.toFloat), Vector[3](0f, 0f, 0f), Vector[3](0f, 1f, 0f), 60f)
    val tmp = Files.createTempFile("ibl_irradiance_", ".hdr")
    try
      TestUtilities.writeHdr(tmp, mapWidth, mapHeight, (x, y) =>
        val l = env(x, y)
        (l, l, l))
      renderer.setEnvironmentMap(renderer.uploadTextureFromFile(tmp.toString))
    finally Files.deleteIfExists(tmp)
    renderer.setIBL(ibl, 1.0f, iblSamples)
    renderImage(imageSize)

  /** Outward unit normal of the sphere under pixel (px, py), if the camera ray hits it. */
  private def normalAt(px: Int, py: Int): Option[(Double, Double, Double)] =
    val dx = (px + 0.5 - imageSize.width / 2.0) / (imageSize.height / 2.0) * verticalFovTan
    val dy = (imageSize.height / 2.0 - (py + 0.5)) / (imageSize.height / 2.0) * verticalFovTan
    val len = math.sqrt(dx * dx + dy * dy + 1.0)
    val (rx, ry, rz) = (dx / len, dy / len, -1.0 / len)
    val b = cameraDistance * rz  // dot(origin, ray) with origin (0, 0, d)
    val discriminant = b * b - (cameraDistance * cameraDistance - sphereRadius * sphereRadius)
    if discriminant < 0.0 then None
    else
      val t = -b - math.sqrt(discriminant)
      Some((t * rx / sphereRadius, t * ry / sphereRadius, (cameraDistance + t * rz) / sphereRadius))

  /** CPU irradiance at normal n: sum over env texels of L * max(0, n.d) * solid angle. */
  private def referenceIrradiance(env: EnvMap, n: (Double, Double, Double)): Double =
    val texelWidth = 2.0 * math.Pi / mapWidth
    val texelHeight = math.Pi / mapHeight
    val terms = for
      y <- 0 until mapHeight
      x <- 0 until mapWidth
    yield
      val theta = (y + 0.5) / mapHeight * math.Pi
      val phi = ((x + 0.5) / mapWidth - 0.5) * 2.0 * math.Pi
      val (dx, dy, dz) = (math.sin(theta) * math.cos(phi), math.cos(theta), math.sin(theta) * math.sin(phi))
      val cosine = math.max(0.0, n._1 * dx + n._2 * dy + n._3 * dz)
      env(x, y).toDouble * cosine * math.sin(theta) * texelWidth * texelHeight
    terms.sum

  private def sphereNormals(region: TestUtilities.Region): Seq[(Pixel, (Double, Double, Double))] =
    for
      y <- region.y0 until region.y1
      x <- region.x0 until region.x1
      n <- normalAt(x, y)
    yield ((x, y), n)

  private def mean(values: Seq[Double]): Double = values.sum / values.length

  /** (measured E, reference E) averaged over the sphere pixels of `region`. */
  private def irradiance(env: EnvMap, lit: Array[Byte], ambientOnly: Array[Byte], region: TestUtilities.Region)
      : (Double, Double) =
    val pixels = sphereNormals(region)
    pixels should not be empty
    def red(image: Array[Byte], p: Pixel): Double =
      ImageValidation.getRGBAt(image, imageSize, p._1, p._2).r / 255.0
    val measured = mean(pixels.map((p, _) => (red(lit, p) - red(ambientOnly, p)) / diffuseBlend))
    val expected = mean(pixels.map((_, n) => referenceIrradiance(env, n)))
    info(f"E measured = $measured%.4f, reference = $expected%.4f")
    (measured, expected)

  private def shouldMatchReference(env: EnvMap, regions: Seq[TestUtilities.Region]): Unit =
    val (lit, ambientOnly) = (render(env, ibl = true), render(env, ibl = false))
    regions.foreach: region =>
      val (measured, expected) = irradiance(env, lit, ambientOnly, region)
      if expected < ambientOnlyTolerance then measured should be <= ambientOnlyTolerance
      else measured shouldBe expected +- (relativeTolerance * expected)

  "IBL on a uniform environment" should "light top, middle and bottom with the irradiance pi * L" in:
    val env = uniform(0.25f)
    shouldMatchReference(env, Seq(top, middle, bottom))

  "IBL under a sky above the horizon and black ground" should "light the top by pi * L and the bottom not at all" in:
    shouldMatchReference(upperSky(0.25f), Seq(top, middle, bottom))

  "IBL with a bright patch straight up" should "light the top and leave the lower half ambient-only" in:
    shouldMatchReference(plusY, Seq(top, middle, bottom))

  "IBL with a bright patch toward the camera" should "light the camera-facing sphere" in:
    shouldMatchReference(plusZ, Seq(top, middle, bottom))

  "IBL with a bright patch behind the sphere" should "leave every visible pixel ambient-only" in:
    shouldMatchReference(minusZ, Seq(top, middle, bottom))
