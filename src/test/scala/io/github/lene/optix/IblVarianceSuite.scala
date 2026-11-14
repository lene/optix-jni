package io.github.lene.optix

import java.nio.file.Files

import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A surface lit mostly by sky, under a low sun that carries most of the env map's power, is
  * lit with far less noise when the cosine-weighted samples are combined with the env-map samples
  * (optix-jni#112).
  *
  * Sampling by env-map luminance alone sends most samples to the sun. The sun is a few degrees
  * above the horizon, so for a horizontal surface those samples carry cos about 0.1, while the
  * rare sky samples carry large weights: a high-variance estimate of an irradiance that is mostly
  * sky. The cosine-weighted strategy samples the sky evenly where the surface can see it.
  *
  * The reference is exact, not measured: the variance of the env-only estimator on this env map is
  * computed on the CPU from its discrete distribution. The measured pixel noise (standard
  * deviation over a flat white plane, which has the same irradiance everywhere, so only the
  * sampling varies) must be a stated factor below it at the same sample count. The mean must still
  * match the irradiance E = integral of L cos dw, which `IblIrradianceSuite` checks over more
  * normals.
  */
class IblVarianceSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val imageSize = ImageSize(256, 256)
  private val region = TestUtilities.Region(64, 64, 192, 192)
  private val mapWidth = 128
  private val mapHeight = 64
  private val iblSamples = 4
  private val diffuseBlend = 0.7  // pixel = ambient 0.3 + DIFFUSE_BLEND_FACTOR * E / pi
  private val ambient = 0.3
  private val skyRadiance = 0.05f
  private val sunShareOfPower = 0.8  // of the Kiara sunrise: 79 %
  private val sunColumns = 80 until 82
  private val sunRows = 29 until 31  // elevations 7.0 and 4.2 degrees
  private val relativeMeanTolerance = 0.10
  private val requiredVarianceReduction = 20.0

  private def isSun(x: Int, y: Int): Boolean = sunColumns.contains(x) && sunRows.contains(y)

  private def texelSolidAngle(y: Int): Double =
    val theta = (y + 0.5) / mapHeight * math.Pi
    math.sin(theta) * (2.0 * math.Pi / mapWidth) * (math.Pi / mapHeight)

  private def isSky(y: Int): Boolean = (y + 0.5) / mapHeight < 0.5

  private val skyPower: Double =
    (for y <- 0 until mapHeight if isSky(y); x <- 0 until mapWidth yield skyRadiance * texelSolidAngle(y)).sum
  private val sunSolidAngle: Double =
    (for y <- sunRows; _ <- sunColumns yield texelSolidAngle(y)).sum
  private val sunRadiance: Float =
    (sunShareOfPower / (1.0 - sunShareOfPower) * skyPower / sunSolidAngle).toFloat

  /** Sky above the horizon, black ground, and a low sun that carries `sunShareOfPower`. */
  private def env(x: Int, y: Int): Float =
    if isSun(x, y) then sunRadiance else if isSky(y) then skyRadiance else 0.0f

  /** (texel probability, estimate value) per texel for the env-only estimator of E at normal +y. */
  private val envOnlyTexels: Seq[(Double, Double)] =
    val power = (for y <- 0 until mapHeight; x <- 0 until mapWidth yield env(x, y) * texelSolidAngle(y)).sum
    for
      y <- 0 until mapHeight
      x <- 0 until mapWidth
      if env(x, y) > 0.0f
    yield
      val theta = (y + 0.5) / mapHeight * math.Pi
      val probability = env(x, y) * texelSolidAngle(y) / power
      val cosine = math.max(0.0, math.cos(theta))
      (probability, env(x, y) * cosine * texelSolidAngle(y) / probability)

  private val irradiance: Double = envOnlyTexels.map((p, v) => p * v).sum
  private val envOnlyVariancePerSample: Double =
    envOnlyTexels.map((p, v) => p * v * v).sum - irradiance * irradiance

  private def renderPlane(ibl: Boolean): Array[Byte] =
    renderer.clearPlanes()
    renderer.addPlaneSolidColor(1, true, 0.0f, 1f, 1f, 1f)
    renderer.setSphere(Vector[3](50f, 50f, 50f), 0.5f)
    renderer.setLight(Vector[3](0f, -1f, 0f), 0f)
    renderer.setShadows(false)
    renderer.setAmbient(AmbientModel.Constant)
    renderer.setCamera(Vector[3](0f, 3f, 0f), Vector[3](0f, 0f, 0f), Vector[3](0f, 0f, -1f), 60f)
    val tmp = Files.createTempFile("ibl_variance_", ".hdr")
    try
      TestUtilities.writeHdr(tmp, mapWidth, mapHeight, (x, y) =>
        val l = env(x, y)
        (l, l, l))
      renderer.setEnvironmentMap(renderer.uploadTextureFromFile(tmp.toString))
    finally Files.deleteIfExists(tmp)
    renderer.setIBL(ibl, 1.0f, iblSamples)
    renderImage(imageSize)

  private def redValues(image: Array[Byte]): Seq[Double] =
    for y <- region.y0 until region.y1; x <- region.x0 until region.x1
    yield ImageValidation.getRGBAt(image, imageSize, x, y).r / 255.0

  "The test env map" should "put most of its power in a sun that lights a horizontal surface at a grazing angle" in:
    val sunPower = sunRadiance * sunSolidAngle
    sunPower / (sunPower + skyPower) shouldBe sunShareOfPower +- 0.01
    // the env-only estimator is noisy here: its relative standard deviation is above 1
    math.sqrt(envOnlyVariancePerSample) / irradiance should be > 1.0

  "IBL on a horizontal surface under a sky with a low sun" should "have the irradiance of the env map" in:
    val lit = redValues(renderPlane(ibl = true))
    val ambientOnly = redValues(renderPlane(ibl = false))
    val measured = math.Pi * (lit.sum - ambientOnly.sum) / lit.length / diffuseBlend
    info(f"E measured = $measured%.4f, reference = $irradiance%.4f")
    measured shouldBe irradiance +- (relativeMeanTolerance * irradiance)

  it should "be several times less noisy than sampling the env map alone at the same sample count" in:
    val lit = redValues(renderPlane(ibl = true))
    val mean = lit.sum / lit.length
    val measuredVariance = lit.map(v => (v - mean) * (v - mean)).sum / lit.length
    val pixelPerIrradiance = diffuseBlend / math.Pi
    val envOnlyVariance = pixelPerIrradiance * pixelPerIrradiance * envOnlyVariancePerSample / iblSamples
    info(f"mean pixel = $mean%.4f (ambient $ambient%.1f)")
    info(f"pixel std = ${math.sqrt(measuredVariance) * 255}%.2f levels, env-only estimator = ${math.sqrt(envOnlyVariance) * 255}%.2f levels")
    info(f"variance reduction = ${envOnlyVariance / measuredVariance}%.1f (required $requiredVarianceReduction%.1f)")
    measuredVariance should be < envOnlyVariance / requiredVarianceReduction
