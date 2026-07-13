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

end TemporalMotionSuite
