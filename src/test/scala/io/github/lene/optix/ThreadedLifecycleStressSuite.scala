package io.github.lene.optix

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

import scala.util.Using

import com.typesafe.scalalogging.LazyLogging
import io.github.lene.optix.Slow
import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** CR-13 (Sprint 35 Task 3.5): threaded lifecycle stress.
  *
  * MultiInstanceSuite and GpuLeakSuite drive multiple renderers but only single-threaded, so a
  * handle race in the create/dispose/reinitialize path would pass them silently. This runs many
  * threads, each owning its own renderer, concurrently through the full lifecycle — initialize,
  * add an instance, render, reinitialize (the atomic handle swap, CR-6), render again, and
  * AutoCloseable `close`. Each renderer is thread-confined (OptiX contexts are not safe for
  * concurrent launches on a shared handle), so the test validates that independent lifecycles do
  * not corrupt one another. Run under compute-sanitizer (pre-push) it fails on any use-after-free
  * or handle race at the native seam.
  */
class ThreadedLifecycleStressSuite extends AnyFlatSpec with Matchers with LazyLogging:

  private val imageSize: ImageSize = ImageSize(160, 120)

  /** Configure a trivial scene and render once; returns the rendered byte count. */
  private def renderOnce(renderer: OptiXRenderer): Int =
    renderer.setCamera(
      Vector[3](0f, 0f, 3f), Vector[3](0f, 0f, 0f), Vector[3](0f, 1f, 0f), 60f)
    renderer.addSphereInstance(Vector[3](0f, 0f, 0f), Material(Color(1f, 0f, 0f, 1f), 1.0f))
    val frame = renderer.render(imageSize)
    if frame == null then 0 else frame.length // scalafix:ok DisableSyntax.null

  "AutoCloseable OptiXRenderer" should "release its native handle on close()" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    val renderer = new OptiXRenderer()
    Using.resource(renderer): r =>
      r.initialize() shouldBe true
      r.nativeHandle should not be 0L
    renderer.nativeHandle shouldBe 0L  // close() delegated to dispose()

  "reinitialize" should "swap the native handle without leaving it uninitialized" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    Using.resource(new OptiXRenderer()): r =>
      r.initialize() shouldBe true
      val before = r.nativeHandle
      r.reinitialize(32) shouldBe true
      r.nativeHandle should not be 0L
      r.nativeHandle should not be before

  "Concurrent renderers" should "survive many parallel create/render/close cycles" taggedAs Slow in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    val threads = 6
    val cyclesPerThread = 5
    val pool = Executors.newFixedThreadPool(threads)
    val failure = new AtomicReference[Throwable]()
    val completed = new AtomicInteger(0)
    try
      (0 until threads).foreach: _ =>
        pool.execute: () =>
          try
            (0 until cyclesPerThread).foreach: _ =>
              if failure.get() == null then
                Using.resource(new OptiXRenderer()): renderer =>
                  renderer.initialize() shouldBe true
                  renderOnce(renderer) should be > 0
                  renderer.reinitialize(32) shouldBe true  // atomic swap mid-life
                  renderOnce(renderer) should be > 0
                  completed.incrementAndGet()
          catch case t: Throwable => failure.compareAndSet(null, t)
      pool.shutdown()
      pool.awaitTermination(5, TimeUnit.MINUTES) shouldBe true
    finally
      pool.shutdownNow()
    Option(failure.get()).foreach(t => fail(s"concurrent lifecycle failed: ${t.getMessage}", t))
    completed.get() shouldBe threads * cyclesPerThread
