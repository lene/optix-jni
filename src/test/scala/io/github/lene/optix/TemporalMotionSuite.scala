package io.github.lene.optix

import menger.common.Color
import menger.common.ImageSize
import menger.common.Material
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Phase 4 Task 1: native cross-frame instance-transform tracking. `getInstanceMotionDelta`
  * returns identity (no motion) on the very first frame (nothing to compare against) and on any
  * topology mismatch (instance count changed since the last frame) — both are safe, non-crashing
  * fallbacks. Once a second frame renders with the SAME topology but a moved instance, the delta
  * must reflect the actual applied transform change.
  */
class TemporalMotionSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(64, 64)

  private def identityTransform: Array[Float] =
    Array(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

  private def translatedTransform(dx: Float): Array[Float] =
    Array(1f, 0f, 0f, dx, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

  private val whiteMatte: Material = Material(Color(0.8f, 0.8f, 0.8f, 1.0f))

  behavior of "Cross-frame instance motion tracking"

  it should "report identity motion on the first frame (nothing to compare against)" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    renderer.clearAllInstances()
    val id = renderer.addSphereInstance(identityTransform, whiteMatte)
    renderer.setDenoisingEnabled(true)
    renderer.setTemporalDenoisingEnabled(true)
    renderer.renderWithStats(imageSize).get
    val delta = renderer.getInstanceMotionDelta(id)
    delta shouldBe identityTransform

  it should "report the real delta when an instance moves between two temporal-mode frames" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    renderer.clearAllInstances()
    val id1 = renderer.addSphereInstance(identityTransform, whiteMatte)
    renderer.setDenoisingEnabled(true)
    renderer.setTemporalDenoisingEnabled(true)
    renderer.renderWithStats(imageSize).get
    renderer.clearAllInstances()
    val id2 = renderer.addSphereInstance(translatedTransform(2.0f), whiteMatte)
    renderer.renderWithStats(imageSize).get
    id2 shouldBe id1 // same positional index -> same identity, per this design
    val delta = renderer.getInstanceMotionDelta(id2)
    // delta must map the CURRENT (translated) point back toward the PREVIOUS (origin) point:
    // delta_t.x should be approximately -2.0 (undo the +2.0 shift). Index 3 is tx in the
    // row-major 3x4 OptiX transform layout (row0 = [m00,m01,m02,tx]), matching
    // translatedTransform's own encoding above -- NOT index 9 (that would be a
    // 9-linear+3-translation layout, which is not this codebase's convention).
    delta(3) should be(-2.0f +- 0.01f)

  it should "fall back to identity motion when instance topology changes (no crash)" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    renderer.clearAllInstances()
    val id1 = renderer.addSphereInstance(identityTransform, whiteMatte)
    renderer.setDenoisingEnabled(true)
    renderer.setTemporalDenoisingEnabled(true)
    renderer.renderWithStats(imageSize).get
    renderer.clearAllInstances()
    val idA = renderer.addSphereInstance(identityTransform, whiteMatte)
    val idB = renderer.addSphereInstance(translatedTransform(1.0f), whiteMatte) // topology changed: 1 -> 2 instances
    noException should be thrownBy renderer.renderWithStats(imageSize).get
    renderer.getInstanceMotionDelta(idA) shouldBe identityTransform
    renderer.getInstanceMotionDelta(idB) shouldBe identityTransform

  behavior of "GPU per-pixel flow-vector computation"

  it should "produce nonzero flow, in the geometrically correct direction, at the pixel " +
    "where a moved instance is visible" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    renderer.clearAllInstances()
    val id1 = renderer.addSphereInstance(identityTransform, whiteMatte)
    renderer.setDenoisingEnabled(true)
    renderer.setTemporalDenoisingEnabled(true)
    renderer.renderWithStats(imageSize).get
    renderer.clearAllInstances()
    val id2 = renderer.addSphereInstance(translatedTransform(0.5f), whiteMatte)
    id2 shouldBe id1 // same positional index -> same identity, per this design
    renderer.renderWithStats(imageSize).get

    val flow = renderer.getFlowBuffer(imageSize.width, imageSize.height)
    flow.length shouldBe imageSize.width * imageSize.height * 2

    val pixelCount = imageSize.width * imageSize.height
    val magnitudes = (0 until pixelCount).map { i =>
      val dx = flow(i * 2)
      val dy = flow(i * 2 + 1)
      dx * dx + dy * dy
    }
    val maxIdx = magnitudes.indices.maxBy(i => magnitudes(i))
    magnitudes(maxIdx) should be > 0.0f
    // Sphere moved by +0.5 in world X. This fixture's camera (eye (0,0.5,3), looking at the
    // origin) builds its right vector as u = cross(up, w) (SceneParameters.cpp:38), which for
    // this eye/lookAt/up works out to point toward world -X, not +X -- so the sphere moves LEFT
    // on screen. The reprojected previous-frame position is therefore to the RIGHT of the
    // current pixel, and flow.x (current minus previous, per the OptiX temporal-denoiser
    // convention) must be negative.
    flow(maxIdx * 2) should be < 0.0f

  it should "produce ~(0,0) flow everywhere a primary ray hits geometry in a fully static " +
    "scene (no instance motion, no camera motion)" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    renderer.clearAllInstances()
    renderer.addSphereInstance(identityTransform, whiteMatte)
    renderer.setDenoisingEnabled(true)
    renderer.setTemporalDenoisingEnabled(true)
    renderer.renderWithStats(imageSize).get
    renderer.renderWithStats(imageSize).get // second frame: identical instances + camera

    val flow = renderer.getFlowBuffer(imageSize.width, imageSize.height)
    val pixelCount = imageSize.width * imageSize.height
    val maxMagnitude = (0 until pixelCount).map { i =>
      val dx = flow(i * 2)
      val dy = flow(i * 2 + 1)
      math.sqrt((dx * dx + dy * dy).toDouble)
    }.max
    maxMagnitude should be < 0.05

end TemporalMotionSuite
