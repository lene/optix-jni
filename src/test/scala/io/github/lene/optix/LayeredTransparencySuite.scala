package io.github.lene.optix

import menger.common.Color
import menger.common.ImageSize
import menger.common.Material
import menger.common.TriangleMeshData
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Slow

/** The cost of a stack of semi-transparent layers (optix-jni#118). A face with alpha < 1 and
  * IOR <= 1.05 takes the coverage blend, which traces a pass-through ray (no bounce spent) and,
  * for a metallic face, a reflection ray as well. Through L overlapping faces that multiplies
  * to about L^bounces rays per pixel unless a branch of negligible weight is skipped. */
class LayeredTransparencySuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val Layers = 10
  private val LayerSpacing = 0.05f
  private val FrontZ = 1.0f
  private val QuadScale = 6f
  private val Size = ImageSize(64, 36)

  private val quad = TriangleMeshData(
    Array[Float](
      -0.5f, -0.5f, 0.5f, 0f, 0f, 1f, 0f, 0f,
      0.5f, -0.5f, 0.5f, 0f, 0f, 1f, 1f, 0f,
      0.5f, 0.5f, 0.5f, 0f, 0f, 1f, 1f, 1f,
      -0.5f, 0.5f, 0.5f, 0f, 0f, 1f, 0f, 1f
    ),
    Array(0, 1, 2, 0, 2, 3),
    8
  )

  /** (alpha, metallic) */
  private case class Row(alpha: Float, metallic: Float)
  private val Opaque = Row(1.0f, 1.0f)
  private val NearlyOpaqueMetal = Row(0.98f, 0.98f)
  private val HalfMetal = Row(0.5f, 0.5f)
  private val ThinDielectric = Row(0.3f, 0.0f)

  private def material(row: Row) =
    Material(Color(0.8f, 0.6f, 0.3f, row.alpha), 1.0f, metallic = row.metallic, specular = 0.0f)

  /** Layers quads, each its own instance, parallel to the image plane. The quad mesh is at
    * z = 0.5, scaled by QuadScale, so the translation is the wanted z minus QuadScale / 2. */
  private def buildStack(row: Row): Unit =
    renderer.setSphere(Vector[3](0f, 0f, -50f), 0.01f)
    renderer.clearPlanes()
    renderer.setShadows(false)
    renderer.setLight(Vector[3](0.3f, -0.3f, -1f), 1.0f)
    renderer.clearAllInstances()
    renderer.clearTriangleMesh()
    val _ = renderer.addTriangleMesh(quad)
    (0 until Layers).foreach: i =>
      val z = FrontZ - i * LayerSpacing - QuadScale * 0.5f
      val transform = Array(QuadScale, 0f, 0f, 0f, 0f, QuadScale, 0f, 0f, 0f, 0f, QuadScale, z)
      val _ = renderer.addTriangleMeshInstance(transform, material(row))

  private def render(row: Row): RenderResult =
    buildStack(row)
    renderer.renderWithStats(Size).get

  private def logRow(row: Row, r: RenderResult): Unit =
    info(f"alpha ${row.alpha}%.2f metallic ${row.metallic}%.2f: totalRays ${r.totalRays}%d " +
      f"reflectedRays ${r.reflectedRays}%d maxDepthReached ${r.maxDepthReached}%d ${r.frameMs}%.0f ms")

  "30 layered quads" should "show the ray cost of each material (A1 diagnosis table)" taggedAs Slow in:
    val results = List(Opaque, NearlyOpaqueMetal, HalfMetal, ThinDielectric).map(row => row -> render(row))
    results.foreach((row, r) => logRow(row, r))
    val opaqueRays = results.head._2.totalRays.toDouble
    results.foreach: (row, r) =>
      info(f"alpha ${row.alpha}%.2f metallic ${row.metallic}%.2f: ${r.totalRays / opaqueRays}%.1f x the rays of the opaque row")
    results.foreach((_, r) => r.totalRays should be > 0L)
