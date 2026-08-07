package io.github.lene.optix
import java.nio.file.Files
import java.nio.file.Path

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
  * Covers every GAS-owning geometry kind, not only spheres — see [[addMixedGeometryRenderClear]].
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

  /** One instance of every GAS-owning geometry kind, not just spheres.
    *
    * Spheres are the only kind whose GAS lives solely in `gas_registry`; cylinder, cone, plane
    * and curve GAS each live in a per-type vector as well. Covering only spheres (as this suite
    * originally did) leaves those four ownership paths untested — which is how the CR-5
    * double-free reached a release. Every kind here must be freed exactly once per clear.
    */
  private def addMixedGeometryRenderClear(r: OptiXRenderer): Unit = {
    val material = Color(1.0f, 0.5f, 0.2f, 1.0f)
    for (i <- 0 until 4)
      r.addSphereInstance(Vector[3](i.toFloat - 1.5f, 0.0f, 0.0f), material, 1.5f)
    r.addCylinderInstance(Vector[3](-2.0f, -1.0f, 0.0f), Vector[3](-2.0f, 1.0f, 0.0f), 0.3f, material, 1.5f)
    r.addConeInstance(Vector[3](2.0f, 1.0f, 0.0f), Vector[3](2.0f, -1.0f, 0.0f), 0.4f, material, 1.5f)
    r.addPlaneInstance(Vector[3](0.0f, 1.0f, 0.0f), -2.0f, material, 1.5f)
    // Cubic B-spline needs >= 4 control points, widths > 0, one per point.
    val points = Array(0.0f, -1.0f, 1.0f, 0.3f, 0.0f, 1.0f, 0.6f, 1.0f, 1.0f, 0.9f, 2.0f, 1.0f)
    r.addCurveInstance(points, Array.fill(4)(0.1f), Material(material, 1.5f))
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
      addMixedGeometryRenderClear(r)
      val baseline = r.freeGpuMemoryBytes()
      for (_ <- 1 to Iterations) addMixedGeometryRenderClear(r)
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
    addMixedGeometryRenderClear(warm)
    val baseline = warm.freeGpuMemoryBytes()
    warm.dispose()

    for (_ <- 1 to Iterations) {
      val r = new OptiXRenderer()
      try {
        r.initialize() should be (true)
        setupCamera(r)
        addMixedGeometryRenderClear(r)
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

  /** Source fitness check — one owner per GAS buffer. Needs no GPU.
    *
    * cylinder/cone/plane/curve GAS is owned by its per-type vector (`cylinder_gas_buffers` &c).
    * CR-5 additionally wrote an alias into `gas_registry` under a negative per-instance key, so
    * `clearAllInstances` freed every one of those buffers twice — the second `cudaFree` returned
    * `cudaErrorInvalidValue`, logged as "CUDA error after cleanup: invalid argument" on every
    * teardown. The leak assertions above are structurally blind to it: a double free leaks
    * nothing, so free memory returns to baseline either way. Hence a source-level guard, in the
    * same spirit as `JniErrorSurfaceSuite`.
    */
  "gas_registry" should "never alias a GAS buffer owned by a per-type vector" in {
    val source = Files.readString(Path.of("src", "main", "native", "OptiXWrapper.cpp"))
    val alias = "gas_registry[static_cast<GeometryType>(-(instanceId + 1))]"
    // Assert on the boolean, not via `should not include`: that matcher prints the whole
    // 3600-line source into the failure output, burying the actual finding.
    withClue(s"OptiXWrapper.cpp contains '$alias' — that aliases a vector-owned GAS into the " +
      "registry, so clearAllInstances frees it twice. Key the registry by GeometryType only. "):
      source.contains(alias) shouldBe false
  }
}
