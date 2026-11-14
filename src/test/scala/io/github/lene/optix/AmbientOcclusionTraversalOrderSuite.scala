package io.github.lene.optix

import scala.util.Using

import menger.common.Color
import menger.common.ImageSize
import menger.common.Material
import menger.common.TriangleMeshData
import menger.common.Vector
import menger.common.x
import menger.common.y
import menger.common.z
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** optix-jni#121: the ambient-occlusion estimate must not depend on how the acceleration
  * structure was built. Moving instances in place refits the IAS, a fresh build constructs a new
  * one; the two traverse in different orders, so an occlusion ray whose result depends on the
  * order of its hits renders the same scene differently. Found by menger's in-place 4D edge
  * updates (EdgeRotationFastPathSuite): a film 24-cell with gold edges, moved in place, differed
  * from a fresh build in 125 of 30,000 pixels by up to 252 levels. */
class AmbientOcclusionTraversalOrderSuite extends AnyFlatSpec with Matchers:

  private val Size = ImageSize(200, 150)
  private val FilmFaces = 12
  private val Edges = 12
  private val EdgeRadius = 0.04f
  private val gold = Material(Color(1.0f, 0.78f, 0.34f, 1f), 1.0f, metallic = 1.0f)

  // A unit quad in the xy plane through the origin, normal +z (stride 8: position, normal, uv).
  private val quad = TriangleMeshData(
    Array[Float](
      -0.5f, -0.5f, 0f, 0f, 0f, 1f, 0f, 0f,
      0.5f, -0.5f, 0f, 0f, 0f, 1f, 1f, 0f,
      0.5f, 0.5f, 0f, 0f, 0f, 1f, 1f, 1f,
      -0.5f, 0.5f, 0f, 0f, 0f, 1f, 0f, 1f
    ),
    Array(0, 1, 2, 0, 2, 3),
    8
  )

  /** The quad scaled by 1.6 and turned by `angle` about y: the faces cross at the centre, as the
    * overlapping faces of a projected 4D polytope do. */
  private def faceTransform(angle: Double): Array[Float] =
    val (c, s, k) = (math.cos(angle).toFloat, math.sin(angle).toFloat, 1.6f)
    Array(k * c, 0f, k * s, 0f, 0f, k, 0f, 0f, -k * s, 0f, k * c, 0f)

  /** Edge i as a cylinder from a point on a circle of radius 0.8 at height -0.6 to one at +0.6,
    * turned by `twist`: they pass through and around the film faces. */
  private def edge(i: Int, twist: Double): (Vector[3], Vector[3]) =
    val a = 2 * math.Pi * i / Edges
    def at(angle: Double, y: Float) =
      Vector[3]((0.8 * math.cos(angle)).toFloat, y, (0.8 * math.sin(angle)).toFloat)
    (at(a, -0.6f), at(a + twist, 0.6f))

  private def endpoints(twist: Double): (Array[Float], Array[Float]) =
    val ends = (0 until Edges).map(edge(_, twist))
    (ends.flatMap((p0, _) => Seq(p0.x, p0.y, p0.z)).toArray,
      ends.flatMap((_, p1) => Seq(p1.x, p1.y, p1.z)).toArray)

  /** The film faces and the edges at `twist`, in a renderer with the given ambient model. */
  private def build(r: OptiXRenderer, twist: Double, ambient: AmbientModel): Array[InstanceId] =
    r.initialize() shouldBe true
    r.setCamera(Vector[3](0f, 0.5f, 4f), Vector[3](0f, 0f, 0f), Vector[3](0f, 1f, 0f), 45f)
    r.setLight(Vector[3](0.4f, -0.6f, -0.5f), 1f)
    r.setAmbient(ambient)
    val _ = r.addTriangleMesh(quad)
    (0 until FilmFaces).foreach: i =>
      val _ = r.addTriangleMeshInstance(faceTransform(math.Pi * i / FilmFaces), Material.Film)
    (0 until Edges).map: i =>
      val (p0, p1) = edge(i, twist)
      r.addCylinderInstance(p0, p1, EdgeRadius, gold)
    .toArray

  private def differingPixels(a: Array[Byte], b: Array[Byte]): Int =
    a.length shouldBe b.length
    a.indices.grouped(4).count(_.exists(i => a(i) != b(i)))

  /** Pixels that differ between the scene moved in place (IAS refit) and built fresh there. */
  private def inPlaceVsFresh(ambient: AmbientModel): Int =
    val (before, after) = (0.4, 0.7)
    val (first, moved) = Using.resource(new OptiXRenderer()): updated =>
      val edges = build(updated, before, ambient)
      val first = updated.render(Size)
      val (p0s, p1s) = endpoints(after)
      updated.updateCylinderInstances(edges, p0s, p1s, Array.fill(Edges)(EdgeRadius))
      (first, updated.render(Size))
    val fresh = Using.resource(new OptiXRenderer()): r =>
      val _ = build(r, after, ambient)
      r.render(Size)
    withClue("the move must be visible, or the comparison proves nothing: "):
      differingPixels(first, moved) should be > 500
    val differing = differingPixels(moved, fresh)
    info(s"$ambient, in place vs fresh: $differing of ${Size.width * Size.height} pixels differ")
    differing

  "A scene with ambient occlusion" should "render the same after an in-place move as after a fresh build" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    inPlaceVsFresh(AmbientModel.Occlusion()) shouldBe 0

  // The control: without occlusion rays the two builds always agreed (before optix-jni#106 too).
  "The same scene with the constant ambient" should "render the same after an in-place move" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    inPlaceVsFresh(AmbientModel.Constant) shouldBe 0
