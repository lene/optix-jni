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
  * Memory is read per process (2026-10-02): [[processGpuMemoryBytes]], the driver's accounting
  * for this JVM, replaced device-wide free memory (cudaMemGetInfo), which counted every other
  * program's VRAM use as a leak — a video player playing next to the run reported -122 MB.
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

  // The lifetime test used a 32 MB floor for a deterministic -20 MB cross-context effect
  // (Sprint 36 D3) that device-wide free memory showed; per-process reads don't (baseline and
  // after were byte-identical on 2026-10-02), so it uses the same 1 MiB floor as the others.

  private def toleranceFromReads(reads: Seq[Double]): Long = {
    val mean = reads.sum / reads.length
    val stddev = math.sqrt(reads.map(v => math.pow(v - mean, 2)).sum / reads.length)
    (stddev * ToleranceStddevMultiplier).toLong.max(MinimumToleranceBytes)
  }

  /** GPU memory held by this JVM, in bytes, from the driver's per-process accounting
    * (`nvidia-smi --query-compute-apps`, matched by PID; MiB resolution). Only this process's
    * allocations show, so other programs on the same GPU can't look like a leak. 0 when the
    * process holds no CUDA context. */
  private def processGpuMemoryBytes(): Long = {
    val pid = ProcessHandle.current().pid().toString
    val out = scala.sys.process.Process(Seq(
      "nvidia-smi", "--query-compute-apps=pid,used_memory", "--format=csv,noheader,nounits"
    )).!!
    out.linesIterator.map(_.split(",").map(_.trim)).collectFirst {
      case Array(p, mib) if p == pid => mib.toLong * 1024 * 1024
    }.getOrElse(0L)
  }

  private def nvidiaSmiAvailable: Boolean = scala.util.Try(processGpuMemoryBytes()).isSuccess

  /** Repeated processGpuMemoryBytes() reads with nothing happening between them — measures
    * the noise floor with an already-initialized renderer. Call right after the
    * existing warmup + baseline read, before the real leak-inducing work, so the noise
    * reflects only steady-state driver jitter, not first-call lazy allocation. Use this
    * when baseline and after are read from the SAME live renderer (the reload-loop test).
    */
  private def measureToleranceBytes(): Long =
    toleranceFromReads((1 to IdleCalibrationIterations).map(_ => processGpuMemoryBytes().toDouble))

  /** Like [[measureToleranceBytes]], but for a comparison whose baseline and after
    * readings come from DIFFERENT renderer instances (the lifetime-loop test: baseline
    * from `warm`, `after` from a freshly created `probe`), so it samples k independent
    * create/init/read/dispose cycles.
    */
  private def measureToleranceBytesAcrossRenderers(): Long =
    toleranceFromReads(
      (1 to IdleCalibrationIterations).map { _ =>
        val r = new OptiXRenderer()
        try {
          r.initialize()
          setupCamera(r)
          addMixedGeometryRenderClear(r)
          processGpuMemoryBytes().toDouble
        } finally r.dispose()
      }
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
    assume(nvidiaSmiAvailable, "nvidia-smi not available for per-process GPU memory")
    val r = new OptiXRenderer()
    try {
      r.initialize() should be (true)
      setupCamera(r)
      // Warm up once so lazy context/module/GAS allocation is out of the way, then baseline.
      addMixedGeometryRenderClear(r)
      val baseline = processGpuMemoryBytes()
      val tolerance = measureToleranceBytes()
      logger.info(s"measured noise-floor tolerance: $tolerance bytes over $IdleCalibrationIterations idle reads")
      for (_ <- 1 to Iterations) addMixedGeometryRenderClear(r)
      val after = processGpuMemoryBytes()
      val leaked = after - baseline
      logger.info(s"reload leak check: baseline=$baseline after=$after leaked=$leaked over $Iterations iters")
      math.abs(leaked) should be <= tolerance
    } finally r.dispose()
  }

  // Enough faces that the leaked 4D face buffer (64 bytes per quad) of 20 reloads, ~25 MB,
  // stands far above the measured noise floor.
  private val ProjectedQuadCount = 20000

  /** A flat grid of small 4D quads at w = 0: valid input for the projection kernel. */
  private val projectedQuads: Array[Float] =
    (0 until ProjectedQuadCount).toArray.flatMap { i =>
      val x = (i % 200) * 0.01f - 1.0f
      val y = (i / 200) * 0.01f - 0.5f
      Array(x, y, 0f, 0f, x + 0.005f, y, 0f, 0f, x + 0.005f, y + 0.005f, 0f, 0f, x, y + 0.005f, 0f, 0f)
    }

  private def addProjectedMeshRenderClear(r: OptiXRenderer): Unit = {
    r.setProjectedMesh(projectedQuads, 4, null, 3.0f, 1.5f, 0f, 0f, 0f) // scalafix:ok DisableSyntax.null
    r.addTriangleMeshInstance(Vector[3](0.0f, 0.0f, 0.0f), Color(1.0f, 0.5f, 0.2f, 1.0f), 1.5f)
    r.render(Width, Height)
    r.clearAllInstances()
  }

  // Usability review 2026-09, session 2 (F34): clearAllInstances freed a projected mesh's
  // vertices, indices and GAS but not its resident 4D face/UV buffers, so every rebuild of an
  // animated 4D scene leaked them until the window ran out of GPU memory. The mixed-geometry
  // loop above never uploads a projected mesh, so it couldn't see this.
  "clear -> re-add projected 4D mesh loop" should "not leak GPU memory across rebuilds" taggedAs (Slow) in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    assume(nvidiaSmiAvailable, "nvidia-smi not available for per-process GPU memory")
    val r = new OptiXRenderer()
    try {
      r.initialize() should be (true)
      setupCamera(r)
      addProjectedMeshRenderClear(r)
      val baseline = processGpuMemoryBytes()
      val tolerance = measureToleranceBytes()
      for (_ <- 1 to Iterations) addProjectedMeshRenderClear(r)
      val after = processGpuMemoryBytes()
      val leaked = after - baseline
      logger.info(s"projected-mesh leak check: baseline=$baseline after=$after leaked=$leaked over $Iterations iters")
      math.abs(leaked) should be <= tolerance
    } finally r.dispose()
  }

  "create/render/dispose loop" should "not leak GPU memory across renderer lifetimes" taggedAs (Slow) in {
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    assume(nvidiaSmiAvailable, "nvidia-smi not available for per-process GPU memory")
    // Baseline measured from a fresh live context so it is comparable to the post-loop probe.
    val warm = new OptiXRenderer()
    warm.initialize() should be (true)
    setupCamera(warm)
    addMixedGeometryRenderClear(warm)
    val baseline = processGpuMemoryBytes()
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
      setupCamera(probe)
      addMixedGeometryRenderClear(probe)
      val after = processGpuMemoryBytes()
      val leaked = after - baseline
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
