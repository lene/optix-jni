package io.github.lene.optix.caustics

import io.github.lene.optix.ImageValidation
import io.github.lene.optix.RendererFixture
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Material
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** optix-jni#117: every accumulated frame must trace its own caustic photons. Before the fix all
  * frames traced the same photons, so accumulating N frames cost N times as much and left the
  * photon noise exactly where it was.
  *
  * The scene is a glass sphere over a matte floor under a downward light, seen from above and
  * behind. The caustic lies in the sphere's shadow on the floor; the camera's up vector is
  * inverted (as in the other caustics suites), so the shadow is in the upper part of the image.
  * The light is dim enough that the floor does not saturate the 8-bit output. The photon
  * count is very low so that photon noise dominates the patch: with thousands of photons the
  * other per-frame noise sources already average down, and the test would not tell the fix from
  * its absence.
  */
class CausticsAccumulationSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize = ImageSize(200, 150)
  private val photonsPerIteration = 20
  private val iterations = 1
  private val lightIntensity = 2.0f
  private val accumulatedFrames = 16
  /** Maximum ratio of the accumulated region's standard deviation to the single frame's. */
  private val maxNoiseRatio = 0.6

  /** A patch inside the caustic on the floor: pixel columns xs, rows ys. */
  private val causticXs = 92 until 108
  private val causticYs = 38 until 44

  private def setupScene(photons: Int, iters: Int): Unit =
    renderer.clearAllInstances()
    renderer.addSphereInstance(
      Array(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f),
      Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass))
    renderer.clearPlanes()
    renderer.addPlaneInstance(
      Vector[3](0f, 1f, 0f), Const.defaultFloorPlaneY, Material.matte(Color(0.5f, 0.5f, 0.5f)))
    renderer.setCamera(
      Vector[3](0.0f, 4.0f, 8.0f), Vector[3](0.0f, 0.0f, 0.0f), Vector[3](0.0f, -1.0f, 0.0f), 45.0f)
    renderer.setLight(Vector[3](0.0f, -1.0f, 0.0f), lightIntensity)
    renderer.enableCaustics(photons, iters)

  private def render(
      frames: Int, photons: Int = photonsPerIteration, iters: Int = iterations): Array[Byte] =
    setupScene(photons, iters)
    renderer.setAccumulationFrames(frames)
    renderer.renderWithStats(imageSize).get.image

  private def causticPatch(image: Array[Byte]): Seq[Double] =
    for y <- causticYs; x <- causticXs
    yield ImageValidation.getRGBAt(image, imageSize, x, y).brightness.toDouble

  private def stdDev(values: Seq[Double]): Double =
    val mean = values.sum / values.size
    math.sqrt(values.map(v => (v - mean) * (v - mean)).sum / values.size)

  behavior of "Caustic photons under accumulation"

  it should "show less noise in the caustic with 16 accumulated frames than with 1" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val single = stdDev(causticPatch(render(1)))
    val accumulated = stdDev(causticPatch(render(accumulatedFrames)))
    info(f"caustic patch brightness std dev: 1 frame $single%.3f, $accumulatedFrames frames $accumulated%.3f")
    single should be > 0.0
    accumulated should be < maxNoiseRatio * single

  it should "render the same image twice with one frame" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    render(1) shouldBe render(1)
