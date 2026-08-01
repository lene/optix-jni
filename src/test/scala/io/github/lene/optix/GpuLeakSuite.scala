package io.github.lene.optix
import com.typesafe.scalalogging.LazyLogging
import io.github.lene.optix.Slow
import menger.common.Color
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** CR-13 (Task 2.6): GPU leak-assertion suite.
  *
  * The pre-existing lifecycle tests asserted only correctness, so the CR-5 GPU-buffer leaks (freed
  * on no path across scene reloads / renderer lifetimes) passed them silently. These tests read
  * device free memory via [[OptiXRenderer.freeGpuMemoryBytes]] (cudaMemGetInfo) and assert it
  * returns to ~baseline across many reload and create/dispose cycles. A per-iteration leak of even
  * a single GAS/instance buffer grows well past the tolerance; the tolerance only absorbs one-time
  * lazy allocations (context/module/cached GAS) and driver granularity.
  *
  * Exercises the instance + gas_registry path that Task 2.3 fixed, so a regression there fails here.
  */
class GpuLeakSuite extends AnyFlatSpec with Matchers with LazyLogging {

  private val ToleranceBytes = 48L * 1024 * 1024 // 48 MB — see class comment
  private val Iterations = 20
  private val Width = 256
  private val Height = 256

  private def setupCamera(r: OptiXRenderer): Unit =
    r.setCamera(
      Vector[3](0.0f, 0.0f, 5.0f),
      Vector[3](0.0f, 0.0f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      45.0f
    )

  private def addSpheresRenderClear(r: OptiXRenderer): Unit = {
    for (i <- 0 until 4)
      r.addSphereInstance(Vector[3](i.toFloat - 1.5f, 0.0f, 0.0f), Color(1.0f, 0.5f, 0.2f, 1.0f), 1.5f)
    r.render(Width, Height)
    r.clearAllInstances()
  }

  "clear -> re-add reload loop" should "not leak GPU memory across scene reloads" taggedAs (Slow) in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    val r = new OptiXRenderer()
    try {
      r.initialize() should be (true)
      setupCamera(r)
      // Warm up once so lazy context/module/GAS allocation is out of the way, then baseline.
      addSpheresRenderClear(r)
      val baseline = r.freeGpuMemoryBytes()
      for (_ <- 1 to Iterations) addSpheresRenderClear(r)
      val after = r.freeGpuMemoryBytes()
      val leaked = baseline - after
      logger.info(s"reload leak check: baseline=$baseline after=$after leaked=$leaked over $Iterations iters")
      leaked should be <= ToleranceBytes
    } finally r.dispose()
  }

  "create/render/dispose loop" should "not leak GPU memory across renderer lifetimes" taggedAs (Slow) in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    // Baseline measured from a fresh live context so it is comparable to the post-loop probe.
    val warm = new OptiXRenderer()
    warm.initialize() should be (true)
    setupCamera(warm)
    addSpheresRenderClear(warm)
    val baseline = warm.freeGpuMemoryBytes()
    warm.dispose()

    for (_ <- 1 to Iterations) {
      val r = new OptiXRenderer()
      try {
        r.initialize() should be (true)
        setupCamera(r)
        addSpheresRenderClear(r)
      } finally r.dispose()
    }

    val probe = new OptiXRenderer()
    try {
      probe.initialize() should be (true)
      val after = probe.freeGpuMemoryBytes()
      val leaked = baseline - after
      logger.info(s"lifetime leak check: baseline=$baseline after=$after leaked=$leaked over $Iterations iters")
      leaked should be <= ToleranceBytes
    } finally probe.dispose()
  }
}
