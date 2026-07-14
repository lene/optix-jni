package io.github.lene.optix

import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Material
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Phase 4: the temporal denoiser must produce LESS frame-to-frame caustic-region flicker than
  * the old spatial-only HDR denoiser, for the same deterministic scripted animation (fixed
  * camera, a glass sphere moving a fixed increment per frame, fixed photon-seed schedule --
  * caustics PPM seeding is frame-independent, see Phase 3/roadmap grounding, so this animation
  * is reproducible run-to-run regardless of denoise mode).
  */
class TemporalFlickerSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(200, 150)
  private val frameCount = 5
  private val stepX = 0.3f
  private val photonsPerIter = 100000
  private val causticIterations = 4
  private val lightIntensity = 500.0f

  private def sphereTransformAt(x: Float): Array[Float] =
    Array(1f, 0f, 0f, x, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

  /** Renders `frameCount` frames of a sphere sliding along X, returns the caustic-ROI mean
    * brightness for each frame (floor half of the image, mirrors Phase 3's dilution-avoidance
    * reasoning: restrict to the region that can plausibly change, don't average in static sky). */
  private def renderAnimationRoiSeries(temporal: Boolean): Seq[Double] =
    renderer.setDenoisingEnabled(true)
    renderer.setTemporalDenoisingEnabled(temporal)
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, Const.defaultFloorPlaneY)
    renderer.setCamera(
      Vector[3](0.0f, 4.0f, 8.0f), Vector[3](0.0f, 0.0f, 0.0f), Vector[3](0.0f, -1.0f, 0.0f), 45.0f)
    renderer.setLight(Vector[3](0.0f, -1.0f, 0.0f), lightIntensity)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    val glass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass)
    (0 until frameCount).map { f =>
      renderer.clearAllInstances()
      renderer.addSphereInstance(sphereTransformAt(f * stepX), glass)
      val result = renderer.renderWithStats(imageSize).get
      val roiPixels = for
        y <- imageSize.height / 2 until imageSize.height
        x <- 0 until imageSize.width
      yield ImageValidation.getRGBAt(result.image, imageSize, x, y)
      roiPixels.map(p => (p.r.toDouble + p.g.toDouble + p.b.toDouble) / 3.0).sum / roiPixels.length
    }

  private def meanAbsFrameToFrameDelta(series: Seq[Double]): Double =
    series.sliding(2).map { case Seq(a, b) => math.abs(b - a) }.sum / (series.length - 1)

  behavior of "Temporal denoiser flicker reduction"

  it should "have measurable frame-to-frame flicker with the spatial-only (Final) denoiser [canary]" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val finalSeries = renderAnimationRoiSeries(temporal = false)
    val finalDelta = meanAbsFrameToFrameDelta(finalSeries)
    withClue(s"Final-mode frame-to-frame ROI delta series=$finalSeries meanDelta=$finalDelta; " +
      "this canary must show nonzero flicker today (spatial-only denoising has no cross-frame " +
      "memory) -- if it's ~0, the metric itself is insensitive and the real assertion below " +
      "would be meaningless. ") {
      finalDelta should be > 0.5
    }

  it should "reduce frame-to-frame flicker with the temporal denoiser vs the canary above" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val finalSeries = renderAnimationRoiSeries(temporal = false)
    val finalDelta = meanAbsFrameToFrameDelta(finalSeries)
    val temporalSeries = renderAnimationRoiSeries(temporal = true)
    val temporalDelta = meanAbsFrameToFrameDelta(temporalSeries)
    withClue(s"Final meanDelta=$finalDelta Temporal meanDelta=$temporalDelta " +
      s"(finalSeries=$finalSeries temporalSeries=$temporalSeries); temporal mode must reduce " +
      "flicker relative to the spatial-only baseline on the SAME scripted animation. ") {
      temporalDelta should be < finalDelta
    }

end TemporalFlickerSuite
