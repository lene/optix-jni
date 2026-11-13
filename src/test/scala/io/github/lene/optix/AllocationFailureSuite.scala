package io.github.lene.optix

import io.github.lene.optix.testing.OptiXTestHooks
import menger.common.Color
import menger.common.ImageSize
import menger.common.TriangleMeshData
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** optix-jni#96, #87, #81: a failed device allocation must leave the renderer as it was, or as a
  * state the next call repairs; it must never leave a freed buffer in use. The failures are
  * injected ([[OptiXTestHooks.failNextDeviceAllocations]]): a real out-of-memory is not
  * reproducible on a shared GPU. An injected failure is thrown before any CUDA call, so any
  * `OptiXContextLostException` in these tests is a real bug: a launch with a freed buffer.
  *
  * Several cases rely on the allocations being counted, so each loops over the n-th allocation
  * of the operation until one no longer fails, and checks the recovery after every one.
  */
class AllocationFailureSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val small = ImageSize(320, 240)
  private val large = ImageSize(640, 480)
  // More allocations than any operation here makes; the loops stop earlier.
  private val MaxAllocations = 12

  override def afterEach(): Unit =
    try
      OptiXTestHooks.failNextDeviceAllocations(renderer, 0)
      OptiXTestHooks.clearContextLost(renderer)
    finally super.afterEach()

  private def failNext(n: Int): Unit = OptiXTestHooks.failNextDeviceAllocations(renderer, n)

  private def maxDiff(a: Array[Byte], b: Array[Byte]): Int =
    a.length shouldBe b.length
    a.indices.map(i => math.abs((a(i) & 0xff) - (b(i) & 0xff))).max

  /** The failure the injection must cause: a plain `OptiXException`, never a lost context. */
  private def failsNormally(body: => Any): Unit =
    val e = the[OptiXException] thrownBy body
    e should not be a[OptiXContextLostException]
    e.getMessage should include("injected allocation failure")

  private def renders(size: ImageSize): Array[Byte] = renderImage(size)

  // --- #96: image buffer -------------------------------------------------------------------------

  "A failed image-buffer allocation" should "leave the renderer usable at the old and the new size" in:
    val before = renders(small)
    failNext(1)
    failsNormally(renderer.render(large))
    // The size the failed buffer used to have: the cached size said "allocated", the buffer was empty.
    maxDiff(renders(small), before) shouldBe 0
    val first = renders(large)
    maxDiff(renders(large), first) shouldBe 0
    maxDiff(renders(small), before) shouldBe 0

  // --- #96: denoise buffers ----------------------------------------------------------------------

  "A failed denoise-buffer allocation" should "leave the renderer usable at the old and the new size" in:
    renderer.setDenoisingEnabled(true)
    val before = renders(small)
    // image buffer, then the first denoise buffer, then the second: the third allocation fails
    failNext(3)
    failsNormally(renderer.render(large))
    maxDiff(renders(small), before) shouldBe 0
    val first = renders(large)
    maxDiff(renders(large), first) shouldBe 0
    maxDiff(renders(small), before) shouldBe 0

  // --- #96: denoiser setup -----------------------------------------------------------------------

  "A failed denoiser setup" should "force a full setup before the denoiser is used again" in:
    renderer.setDenoisingEnabled(true)
    val denoisedSmall = renders(small)
    // image buffer (1), five denoise buffers (2-6), the denoiser's state (7) and scratch (8): the
    // denoiser catches the failure and the frame comes back undenoised
    failNext(8)
    val undenoisedLarge = renders(large)
    // The state buffer is at the new size, but was never set up for it: the old setup size must not count.
    maxDiff(renders(small), denoisedSmall) shouldBe 0
    val denoisedLarge = renders(large)
    withClue("the injected failure must have hit the denoiser, so the frame is undenoised: "):
      maxDiff(denoisedLarge, undenoisedLarge) should be > 0

  // --- #87: mesh update --------------------------------------------------------------------------

  private val identity = Array(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
  private val red = Material(Color(0.9f, 0.1f, 0.1f, 1f), 1.0f)

  private def quad(half: Float, z: Float): TriangleMeshData =
    TriangleMeshData(
      Array[Float](
        -half, -half, z, 0f, 0f, 1f, 0f, 0f,
        half, -half, z, 0f, 0f, 1f, 1f, 0f,
        half, half, z, 0f, 0f, 1f, 1f, 1f,
        -half, half, z, 0f, 0f, 1f, 0f, 1f
      ),
      Array(0, 1, 2, 0, 2, 3),
      8
    )

  "A failed mesh update" should "keep the old mesh, and a later update must work" in:
    renderer.clearAllInstances()
    renderer.clearTriangleMesh()
    val slot = renderer.addTriangleMesh(quad(0.8f, 0.5f))
    val _ = renderer.addTriangleMeshInstance(identity, red)
    val before = renders(small)
    val replacement = quad(0.3f, 0.5f)
    val failures = failingAllocations((), renderer.updateCpuTriangleMesh(slot, replacement)): k =>
      withClue(s"after the update failed at allocation $k: "):
        maxDiff(renders(small), before) shouldBe 0
    // the injection of allocation failures+1 did not fail: the update went through
    withClue("the update must make at least two allocations (vertices, indices): ")(failures should be >= 2)
    val updated = renders(small)
    withClue("the new mesh must show: ")(maxDiff(updated, before) should be > 40)
    maxDiff(renders(small), updated) shouldBe 0

  /** True when the `n`-th allocation of `body` failed (normally); false when `body` made fewer. */
  private def failsAt(n: Int, body: => Any): Boolean =
    failNext(n)
    val failed =
      try
        body
        false
      catch case e: OptiXException =>
        e should not be a[OptiXContextLostException]
        true
    failNext(0)
    failed

  /** Runs `body` with its 1st, 2nd, ... allocation failing, `setup` before each, until one does not
    * fail (the body made fewer allocations); `recovered(k)` checks the state after the k-th failure.
    * Returns how many allocations failed.
    */
  private def failingAllocations(setup: => Unit, body: => Any)(recovered: Int => Unit): Int =
    Iterator.from(1).takeWhile(_ <= MaxAllocations).takeWhile { k =>
      setup
      val failed = failsAt(k, body)
      if failed then recovered(k)
      failed
    }.size

  // --- #81: IAS rebuild --------------------------------------------------------------------------

  private def at(x: Float): Array[Float] = Array(0.4f, 0f, 0f, x, 0f, 0.4f, 0f, 0f, 0f, 0f, 0.4f, 0f)

  private def twoSpheres(): Unit =
    renderer.clearAllInstances()
    val _ = renderer.addSphereInstance(at(-1.0f), red)
    val _ = renderer.addSphereInstance(at(0.0f), red)

  "A failed IAS rebuild" should "leave the renderer able to render every instance" in:
    twoSpheres()
    val two = renders(small)
    val _ = renderer.addSphereInstance(at(1.0f), red)
    val three = renders(small)
    withClue("the third instance must show: ")(maxDiff(three, two) should be > 40)
    val allocations = failingAllocations(
      { twoSpheres(); renders(small); val _ = renderer.addSphereInstance(at(1.0f), red) },
      renderer.render(small)
    ): k =>
      withClue(s"after the rebuild failed at allocation $k: "):
        maxDiff(renders(small), three) shouldBe 0
    withClue("a full IAS rebuild makes at least four allocations (instances, materials, temp, output): ")(
      allocations should be >= 4
    )

  // --- no leak -----------------------------------------------------------------------------------

  private def processGpuMemoryBytes(): Long =
    val pid = ProcessHandle.current().pid().toString
    val out = scala.sys.process.Process(Seq(
      "nvidia-smi", "--query-compute-apps=pid,used_memory", "--format=csv,noheader,nounits"
    )).!!
    out.linesIterator.map(_.split(",").map(_.trim)).collectFirst {
      case Array(p, mib) if p == pid => mib.toLong * 1024 * 1024
    }.getOrElse(0L)

  /** A cycle of failures: image buffer, a large mesh update, an IAS of many instances. */
  private def failureCycle(slot: MeshSlot, big: TriangleMeshData, other: TriangleMeshData): Unit =
    failNext(1)
    an[OptiXException] should be thrownBy renderer.render(large)
    renders(small)
    renders(large)
    renders(small)
    failNext(1)
    an[OptiXException] should be thrownBy renderer.updateCpuTriangleMesh(slot, other)
    renderer.updateCpuTriangleMesh(slot, big)
    renders(small)
    val extra = renderer.addSphereInstance(at(1.5f), red)
    failNext(2)
    an[OptiXException] should be thrownBy renderer.render(small)
    failNext(0)
    renderer.removeInstance(extra)
    renders(small)

  "Failed allocations" should "not leak device memory" in:
    assume(scala.util.Try(processGpuMemoryBytes()).isSuccess, "nvidia-smi not available")
    val MiB = 1024L * 1024
    val side = 150
    // a grid of side x side quads: about 90k vertices, a few MB, so a leaked copy shows at MiB resolution
    val verts = for
      i <- 0 to side
      j <- 0 to side
    yield Array(i.toFloat / side, j.toFloat / side, 0f, 0f, 0f, 1f, 0f, 0f)
    val idx = for
      i <- 0 until side
      j <- 0 until side
      v = i * (side + 1) + j
      t <- Seq(v, v + 1, v + side + 1, v + 1, v + side + 2, v + side + 1)
    yield t
    val big = TriangleMeshData(verts.flatten.toArray, idx.toArray, 8)
    val other = quad(0.3f, 0.5f)
    renderer.clearAllInstances()
    renderer.clearTriangleMesh()
    val slot = renderer.addTriangleMesh(other)
    val _ = renderer.addTriangleMeshInstance(identity, red)
    val _ = renderer.addSphereInstance(at(-1.0f), red)
    renderer.updateCpuTriangleMesh(slot, big)
    failureCycle(slot, big, other)
    val baseline = processGpuMemoryBytes()
    (1 to 10).foreach(_ => failureCycle(slot, big, other))
    renders(small)
    val after = processGpuMemoryBytes()
    info(s"process GPU memory: baseline ${baseline / MiB} MiB, after ${after / MiB} MiB")
    after should be <= (baseline + 2 * MiB)
