package io.github.lene.optix

import scala.util.Using

import io.github.lene.optix.testing.OptiXTestHooks
import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** optix-jni#66: `setInstanceTransform/Visible/Material/Coverage` were documented as cheap but
  * each forced a full IAS rebuild with five reallocations. They now update the IAS in place
  * (instance records re-uploaded, the IAS refit; materials re-uploaded only). Each change must
  * render the same image as a renderer that built the final scene from scratch, and must not
  * count as a full build; adding an instance still rebuilds.
  */
class IasInPlaceUpdateSuite extends AnyFlatSpec with Matchers:

  private val imageSize = ImageSize(160, 120)
  private val red = Material(Color(0.9f, 0.1f, 0.1f, 1f), 1.0f)
  private val blue = Material(Color(0.1f, 0.2f, 0.9f, 1f), 1.0f)

  private def at(x: Float, y: Float, z: Float): Array[Float] =
    Array(0.5f, 0f, 0f, x, 0f, 0.5f, 0f, y, 0f, 0f, 0.5f, z)

  /** Two spheres in a fresh renderer. */
  private def scene(r: OptiXRenderer): (Int, Int) =
    r.initialize() shouldBe true
    r.setCamera(Vector[3](0f, 0f, 4f), Vector[3](0f, 0f, 0f), Vector[3](0f, 1f, 0f), 60f)
    r.setLight(Vector[3](-0.5f, -0.5f, -1f), 1.0f)
    (r.addSphereInstance(at(-0.7f, 0f, 0f), red), r.addSphereInstance(at(0.7f, 0f, 0f), red))

  private def counts(r: OptiXRenderer): (Long, Long) =
    val c = OptiXTestHooks.iasUpdateCounts(r)
    (c.fullBuilds, c.inPlaceUpdates)

  private def maxDiff(a: Array[Byte], b: Array[Byte]): Int =
    a.length shouldBe b.length
    a.indices.map(i => math.abs((a(i) & 0xff) - (b(i) & 0xff))).max

  /** Renders `change` applied after a first render (in place) and applied before the first
    * render (built from scratch); returns both images and the in-place renderer's counts. */
  private def compare(change: (OptiXRenderer, (Int, Int)) => Unit)
      : (Array[Byte], Array[Byte], (Long, Long)) =
    Using.resource(new OptiXRenderer()): updated =>
      val ids = scene(updated)
      val before = updated.render(imageSize)
      change(updated, ids)
      val inPlace = updated.render(imageSize)
      withClue("the change must be visible, or the comparison proves nothing: "):
        maxDiff(before, inPlace) should be > 40
      val c = counts(updated)
      Using.resource(new OptiXRenderer()): fresh =>
        val freshIds = scene(fresh)
        change(fresh, freshIds)
        (inPlace, fresh.render(imageSize), c)

  "Moving an instance" should "refit in place and render like a scene built with it there" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    val (inPlace, fresh, c) = compare((r, ids) => r.setInstanceTransform(ids._2, at(0.4f, 0.6f, -0.5f)))
    c shouldBe (1L, 1L)
    maxDiff(inPlace, fresh) should be <= 2

  "Hiding an instance" should "update in place and render like a scene built with it hidden" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    val (inPlace, fresh, c) = compare((r, ids) => r.setInstanceVisible(ids._1, false))
    c shouldBe (1L, 1L)
    maxDiff(inPlace, fresh) should be <= 2

  "Changing a material" should "re-upload the materials only and render like the built scene" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    val (inPlace, fresh, c) = compare((r, ids) =>
      r.setInstanceMaterial(ids._1, blue.color.r, blue.color.g, blue.color.b, 1f, 1.0f))
    c shouldBe (1L, 1L)
    maxDiff(inPlace, fresh) should be <= 2

  "Adding an instance" should "still rebuild the IAS" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    Using.resource(new OptiXRenderer()): r =>
      scene(r)
      r.render(imageSize)
      r.addSphereInstance(at(0f, -0.8f, 0f), blue)
      r.render(imageSize)
      counts(r) shouldBe (2L, 0L)
