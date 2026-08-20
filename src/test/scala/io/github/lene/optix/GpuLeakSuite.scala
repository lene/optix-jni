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
  * lazy allocations (context/module/cached GAS) and driver granularity — measured per test run
  * (Sprint 36 D3) via [[measureToleranceBytes]] rather than a guessed flat constant.
  *
  * Exercises the instance + gas_registry path that Task 2.3 fixed, so a regression there fails here.
  * Covers every GAS-owning geometry kind, not only spheres — see [[addMixedGeometryRenderClear]].
  */
class GpuLeakSuite extends AnyFlatSpec with Matchers with LazyLogging {

  private val Iterations = 20
  private val Width = 256
  private val Height = 256

  // Sprint 36 D3: tolerance derived from measured variance instead of a guessed flat
  // constant (previously 48 MB). k idle freeGpuMemoryBytes() reads, with no allocation
  // activity between them, characterize cudaMemGetInfo's own reporting noise (driver
  // bookkeeping jitter, other processes on a shared card) independent of any real leak —
  // this is exactly the "-20 MB pure noise on re-run" QA_STRATEGY.md's O4 cites.
  private val IdleCalibrationIterations = 10
  private val ToleranceStddevMultiplier = 5.0
  private val MinimumToleranceBytes = 1L * 1024 * 1024 // guards a degenerate all-identical-reads case

  // Investigated empirically (Sprint 36 D3, 2026-08-09): unlike same-renderer idle reads
  // (near-zero stddev, real leak noise there), the lifetime-loop comparison's baseline
  // (from `warm`, disposed) vs. after (from a freshly created `probe`, post 20 other
  // create/dispose cycles) reproduced an exact, deterministic -20 MB delta across three
  // consecutive full runs — a fixed cross-CUDA-context driver-allocator effect, not
  // random variance. A same-process k-sample calibration structurally cannot see it: the
  // calibration and the real measurement share the same driver session, so both observe
  // the identical deterministic value, and stddev-across-samples reads as ~0 either way.
  // This is exactly the "-20 MB pure noise" QA_STRATEGY.md's O4 already documented from
  // prior investigation. Floor set from that real, reproduced number plus margin — not a
  // guess, but honestly not derived from same-run variance either.
  private val MinimumCrossContextToleranceBytes = 32L * 1024 * 1024

  private def toleranceFromReads(reads: Seq[Double], minimumBytes: Long = MinimumToleranceBytes): Long = {
    val mean = reads.sum / reads.length
    val stddev = math.sqrt(reads.map(v => math.pow(v - mean, 2)).sum / reads.length)
    (stddev * ToleranceStddevMultiplier).toLong.max(minimumBytes)
  }

  /** Repeated freeGpuMemoryBytes() reads with nothing happening between them — measures
    * the noise floor on the given, already-initialized renderer. Call right after the
    * existing warmup + baseline read, before the real leak-inducing work, so the noise
    * reflects only steady-state driver jitter, not first-call lazy allocation. Use this
    * when baseline and after are read from the SAME live renderer (the reload-loop test).
    */
  private def measureToleranceBytes(r: OptiXRenderer): Long =
    toleranceFromReads((1 to IdleCalibrationIterations).map(_ => r.freeGpuMemoryBytes().toDouble))

  /** Like [[measureToleranceBytes]], but for a comparison whose baseline and after
    * readings come from DIFFERENT renderer instances (the lifetime-loop test: baseline
    * from `warm`, disposed, `after` from a freshly created `probe`). Cross-CUDA-context
    * allocator behavior is a materially larger, different noise source than same-context
    * repeated reads — measured on this machine: ~0 bytes same-renderer vs. ~20 MB
    * across a dispose/recreate, exactly the "-20 MB pure noise" QA_STRATEGY.md's O4
    * cites — so it needs its own k independent create/init/read/dispose samples.
    */
  private def measureToleranceBytesAcrossRenderers(): Long =
    toleranceFromReads(
      (1 to IdleCalibrationIterations).map { _ =>
        val r = new OptiXRenderer()
        try {
          r.initialize()
          setupCamera(r)
          addMixedGeometryRenderClear(r)
          r.freeGpuMemoryBytes().toDouble
        } finally r.dispose()
      },
      minimumBytes = MinimumCrossContextToleranceBytes
    )

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
      val tolerance = measureToleranceBytes(r)
      logger.info(s"measured noise-floor tolerance: $tolerance bytes over $IdleCalibrationIterations idle reads")
      for (_ <- 1 to Iterations) addMixedGeometryRenderClear(r)
      val after = r.freeGpuMemoryBytes()
      val leaked = baseline - after
      logger.info(s"reload leak check: baseline=$baseline after=$after leaked=$leaked over $Iterations iters")
      math.abs(leaked) should be <= tolerance
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
    val tolerance = measureToleranceBytesAcrossRenderers()
    logger.info(s"measured noise-floor tolerance: $tolerance bytes over $IdleCalibrationIterations create/dispose cycles")

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
      math.abs(leaked) should be <= tolerance
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
