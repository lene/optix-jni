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

  // The table of the diagnosis (optix-jni#118) without the cutoff: the rays of 10 layers at 64x36.
  private val OpaqueRaysWithoutCutoff = 41472L
  private val NearlyOpaqueMetalRaysWithoutCutoff = 735118848L
  private val HalfMetalRaysWithoutCutoff = 735118848L
  private val ThinDielectricRaysWithoutCutoff = 370944L

  private def rays(row: Row, minRayWeight: Option[Float]): Long =
    minRayWeight.foreach(renderer.setMinRayWeight)
    val r = render(row)
    logRow(row, r)
    r.totalRays

  "Ten layered quads with the default cutoff" should
    "need at most 10 times the rays of the opaque stack at alpha 0.98, metallic 0.98" taggedAs Slow in:
    val opaque = rays(Opaque, None)
    rays(NearlyOpaqueMetal, None) should be <= 10 * opaque

  it should "need at most 100 times the rays of the opaque stack at alpha 0.5, metallic 0.5" taggedAs Slow in:
    val opaque = rays(Opaque, None)
    rays(HalfMetal, None) should be <= 100 * opaque

  "With the cutoff switched off, ten layered quads" should
    "reproduce the ray counts measured before the cutoff existed (within 1 %)" taggedAs Slow in:
    List(
      Opaque -> OpaqueRaysWithoutCutoff,
      NearlyOpaqueMetal -> NearlyOpaqueMetalRaysWithoutCutoff,
      HalfMetal -> HalfMetalRaysWithoutCutoff,
      ThinDielectric -> ThinDielectricRaysWithoutCutoff
    ).foreach: (row, expected) =>
      withClue(s"$row: "):
        rays(row, Some(0f)).toDouble shouldBe (expected.toDouble +- expected * 0.01)

  // A glass sphere and a glass cube on a checker floor.
  private def glassScene(): Unit =
    TestScenario.glassSphere().withSpherePosition(Vector[3](-0.8f, 0f, 0f)).applyTo(renderer)
    renderer.clearPlanes()
    renderer.addPlaneCheckerColors(1, true, -1f, 0.9f, 0.9f, 0.9f, 0.1f, 0.1f, 0.1f)
    val _ = renderer.addTriangleMesh(cube)
    val _ = renderer.addTriangleMeshInstance(
      Vector[3](0.8f, 0f, 0f),
      Material(Color(0.9f, 1.0f, 0.9f, 0.3f), 1.5f),
      TextureIndex.NoTexture
    )

  private def cube: TriangleMeshData =
    val h = 0.5f
    val faces = Seq(
      (Seq((-1, -1, 1), (1, -1, 1), (1, 1, 1), (-1, 1, 1)), (0f, 0f, 1f)),
      (Seq((1, -1, -1), (-1, -1, -1), (-1, 1, -1), (1, 1, -1)), (0f, 0f, -1f)),
      (Seq((1, -1, 1), (1, -1, -1), (1, 1, -1), (1, 1, 1)), (1f, 0f, 0f)),
      (Seq((-1, -1, -1), (-1, -1, 1), (-1, 1, 1), (-1, 1, -1)), (-1f, 0f, 0f)),
      (Seq((-1, 1, 1), (1, 1, 1), (1, 1, -1), (-1, 1, -1)), (0f, 1f, 0f)),
      (Seq((-1, -1, -1), (1, -1, -1), (1, -1, 1), (-1, -1, 1)), (0f, -1f, 0f))
    )
    val vertices = faces.flatMap((corners, n) =>
      corners.flatMap((x, y, z) => Seq(x * h, y * h, z * h, n._1, n._2, n._3)))
    val indices = faces.indices.flatMap(f => Seq(0, 1, 2, 0, 2, 3).map(_ + 4 * f))
    TriangleMeshData(vertices.toArray, indices.toArray, 6)

  "The default cutoff" should "change no channel of a glass sphere and a glass cube by more than 3 of 255" taggedAs Slow in:
    glassScene()
    val withCutoff = renderImage(ImageSize(320, 240))
    renderer.setMinRayWeight(0f)
    val withoutCutoff = renderImage(ImageSize(320, 240))
    val largestDifference = withCutoff.zip(withoutCutoff).map((a, b) => math.abs((a & 0xff) - (b & 0xff))).max
    info(s"largest channel difference: $largestDifference")
    largestDifference should be <= 3

  "setMinRayWeight" should "reject a weight outside [0, 1]" in:
    an[IllegalArgumentException] should be thrownBy renderer.setMinRayWeight(-0.1f)
    an[IllegalArgumentException] should be thrownBy renderer.setMinRayWeight(1.5f)
    an[IllegalArgumentException] should be thrownBy renderer.setMinRayWeight(Float.NaN)

  it should "accept the ends of the range" in:
    noException should be thrownBy renderer.setMinRayWeight(0f)
    noException should be thrownBy renderer.setMinRayWeight(1f)

  "Layered quads" should "show the ray cost of each material (diagnosis table, cutoff switched off)" taggedAs Slow in:
    renderer.setMinRayWeight(0f)
    val results = List(Opaque, NearlyOpaqueMetal, HalfMetal, ThinDielectric).map(row => row -> render(row))
    results.foreach((row, r) => logRow(row, r))
    val opaqueRays = results.head._2.totalRays.toDouble
    results.foreach: (row, r) =>
      info(f"alpha ${row.alpha}%.2f metallic ${row.metallic}%.2f: ${r.totalRays / opaqueRays}%.1f x the rays of the opaque row")
    results.foreach((_, r) => r.totalRays should be > 0L)
