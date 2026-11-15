package io.github.lene.optix

import io.github.lene.optix.testing.CausticTarget
import io.github.lene.optix.testing.OptiXTestHooks
import menger.common.Color
import menger.common.TriangleMeshData
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** optix-jni#63: caustics aimed every triangle-mesh instance at one renderer-wide box, the
  * bounds of the most recently uploaded mesh, and kept it when a mesh's vertices changed. */
class CausticTargetSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val glass = Color(1f, 1f, 1f, 0.1f)
  private val Ior = 1.5f
  private val Tolerance = 1e-4f
  // The bounding sphere of a cube with edge `edge`: half its space diagonal.
  private def cubeRadius(edge: Float): Float = 0.5f * edge * math.sqrt(3.0).toFloat

  private def cube(edge: Float): TriangleMeshData =
    val unit = TestUtilities.createUnitCubeMesh()
    val scaled = unit.vertices.zipWithIndex.map((v, i) => if i % unit.vertexStride < 3 then v * edge else v)
    TriangleMeshData(scaled, unit.indices, unit.vertexStride)

  private def only(targets: Array[CausticTarget]): CausticTarget =
    targets should have size 1
    targets.head

  "causticTargets" should "bound each refractive mesh instance by its own mesh" in:
    val _ = renderer.addTriangleMesh(cube(0.2f))
    val _ = renderer.addTriangleMeshInstance(Vector[3](0f, 0f, 0f), glass, Ior)
    val _ = renderer.addTriangleMesh(cube(4f))
    val _ = renderer.addTriangleMeshInstance(Vector[3](10f, 0f, 0f), glass, Ior)
    val targets = OptiXTestHooks.causticTargets(renderer)
    targets should have size 2
    targets(0).radius shouldBe cubeRadius(0.2f) +- Tolerance
    targets(0).x shouldBe 0f +- Tolerance
    targets(1).radius shouldBe cubeRadius(4f) +- Tolerance
    targets(1).x shouldBe 10f +- Tolerance

  it should "follow a mesh whose vertices were replaced" in:
    val slot = renderer.addTriangleMesh(cube(1f))
    val _ = renderer.addTriangleMeshInstance(Vector[3](0f, 0f, 0f), glass, Ior)
    renderer.updateCpuTriangleMesh(slot, cube(3f))
    only(OptiXTestHooks.causticTargets(renderer)).radius shouldBe cubeRadius(3f) +- Tolerance

  it should "follow a 4D mesh re-projected on the GPU" in:
    // One square at w = 0, projected with scale (eyeW - screenW) / eyeW (project4d.cu,
    // perspective_w): 0.5 at screenW 1.5, 1 at screenW 0.
    val square = Array(-0.5f, -0.5f, 0f, 0f, 0.5f, -0.5f, 0f, 0f, 0.5f, 0.5f, 0f, 0f, -0.5f, 0.5f, 0f, 0f)
    val slot = renderer.setProjectedMesh(square, 4, null, 3f, 1.5f, 0f, 0f, 0f) // scalafix:ok DisableSyntax.null
    val _ = renderer.addTriangleMeshInstance(Vector[3](0f, 0f, 0f), glass, Ior)
    val before = only(OptiXTestHooks.causticTargets(renderer)).radius
    renderer.updateMesh4DProjection(slot, 3f, 0f, 0f, 0f, 0f)
    only(OptiXTestHooks.causticTargets(renderer)).radius shouldBe (2f * before) +- Tolerance
