package io.github.lene.optix

import scala.util.Using

import io.github.lene.optix.testing.OptiXTestHooks
import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** optix-jni#73: after a sticky CUDA error the context is unusable for the whole process, and
  * `cudaGetLastError()` was used as if it cleared it. The first one is now latched and every
  * render throws `OptiXContextLostException` naming it. A real sticky error would poison this
  * test JVM for every later suite, so the latch is set and cleared through test hooks; the
  * latching logic itself is covered natively (ContextLostTest.cpp).
  */
class ContextLostSuite extends AnyFlatSpec with Matchers with BeforeAndAfterEach:

  private val imageSize = ImageSize(64, 48)

  private def renderer(): OptiXRenderer =
    val r = new OptiXRenderer()
    r.initialize() shouldBe true
    r.setCamera(Vector[3](0f, 0f, 4f), Vector[3](0f, 0f, 0f), Vector[3](0f, 1f, 0f), 60f)
    r.addSphereInstance(Vector[3](0f, 0f, 0f), Material(Color(1f, 0f, 0f, 1f), 1.0f))
    r

  override def afterEach(): Unit =
    try Using.resource(new OptiXRenderer())(OptiXTestHooks.clearContextLost(_))
    finally super.afterEach()

  "A lost CUDA context" should "make every render throw OptiXContextLostException naming it" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    Using.resource(renderer()): r =>
      r.render(imageSize) should not be null
      OptiXTestHooks.simulateContextLost(r, "CUDA call 'x' failed: an illegal memory access (700)")
      val e = the[OptiXContextLostException] thrownBy r.renderWithStats(64, 48)
      e.getMessage should include("illegal memory access (700)")
      e shouldBe an[OptiXException] // existing broad handlers keep working

  it should "hold for every renderer in the process, and every call, not only render" in:
    // optix-jni#73: only render() checked the latch; setters ran into the lost context.
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    Using.resource(renderer())(OptiXTestHooks.simulateContextLost(_, "launch failure (719)"))
    Using.resource(new OptiXRenderer()): other =>
      other.initialize() shouldBe true
      an[OptiXContextLostException] should be thrownBy
        other.setCamera(Vector[3](0f, 0f, 4f), Vector[3](0f, 0f, 0f), Vector[3](0f, 1f, 0f), 60f)
      an[OptiXContextLostException] should be thrownBy
        other.addSphereInstance(Vector[3](0f, 0f, 0f), Material(Color(1f, 0f, 0f, 1f), 1.0f))
      an[OptiXContextLostException] should be thrownBy other.setShadows(true)
      an[OptiXContextLostException] should be thrownBy other.renderWithStats(64, 48)

  "A context without a sticky error" should "render normally" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    Using.resource(renderer())(_.render(imageSize) should not be null)
