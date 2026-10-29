package io.github.lene.optix

import scala.util.Using

import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** optix-jni#85: `renderMix` renders several visibility passes in one native call and mixes
  * them. Its image must equal, byte for byte, the same mix computed from separate renders (the
  * rounding menger's level mix uses), and its stats must be the passes' sums. */
class RenderMixSuite extends AnyFlatSpec with Matchers:

  private val imageSize = ImageSize(96, 72)

  private def renderer(): (OptiXRenderer, Array[InstanceId]) =
    val r = new OptiXRenderer()
    r.initialize() shouldBe true
    r.setCamera(Vector[3](0f, 0f, 4f), Vector[3](0f, 0f, 0f), Vector[3](0f, 1f, 0f), 60f)
    r.setLight(Vector[3](-0.5f, -0.5f, -1f), 1.0f)
    val a = r.addSphereInstance(Vector[3](-0.6f, 0f, 0f), Material(Color(0.9f, 0.1f, 0.1f, 1f), 1.0f))
    val b = r.addSphereInstance(Vector[3](0.6f, 0f, 0f), Material(Color(0.1f, 0.2f, 0.9f, 1f), 1.0f))
    (r, Array(a, b))

  private val passes = Array(
    MixPass(Array(true, false), 0.3),
    MixPass(Array(false, true), 0.5),
    MixPass(Array(true, true), 0.2)
  )

  "renderMix" should "equal the mix of the passes rendered one by one" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    val (r, ids) = renderer()
    Using.resource(r): _ =>
      val separate = passes.map { pass =>
        ids.zip(pass.visible).foreach((id, v) => r.setInstanceVisible(id, v))
        (r.renderWithStats(imageSize).get, pass.weight)
      }
      val expected = new Array[Double](separate.head._1.image.length)
      separate.foreach((result, w) => expected.indices.foreach(i => expected(i) += (result.image(i) & 0xff) * w))
      val mixed = r.renderMix(imageSize, ids, passes).get
      mixed.image.map(_ & 0xff).toSeq shouldBe expected.map(v => math.max(0L, math.min(255L, math.round(v))).toInt).toSeq
      mixed.totalRays shouldBe separate.map(_._1.totalRays).sum

  it should "reject bad input" in:
    assume(OptiXRenderer.isLibraryLoaded, "OptiX native library not loaded")
    val (r, ids) = renderer()
    Using.resource(r): _ =>
      an[IllegalArgumentException] should be thrownBy r.renderMix(imageSize, ids, Array.empty[MixPass])
      an[IllegalArgumentException] should be thrownBy
        r.renderMix(imageSize, ids, Array(MixPass(Array(true), 1.0)))
      an[IllegalArgumentException] should be thrownBy
        r.renderMix(imageSize, Array(ids(0), InstanceId(9999)), Array(MixPass(Array(true, true), 1.0)))
      an[IllegalArgumentException] should be thrownBy
        r.renderMix(imageSize, ids, Array.fill(65)(MixPass(Array(true, true), 1.0 / 65)))
