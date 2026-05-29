package io.github.lene.optix

import com.typesafe.scalalogging.LazyLogging
import menger.common.Vector
import io.github.lene.optix.ColorConstants.OPAQUE_RED
import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Slow

/** Smoke tests for the recursive-IAS Menger sponge (Sprint 18.4 Cut A2).
  *
  * These tests prove the end-to-end native + JNI + Scala wiring works:
  * a unit-cube triangle mesh is uploaded, wrapped in N nested IAS layers,
  * and rendered. Correctness vs reference image is deferred to Cut C.
  */
class RecursiveIASSpongeSuite extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private def addUnitCubeLeaf(): Unit =
    renderer.setTriangleMesh(TestUtilities.createUnitCubeMesh())

  "addRecursiveIASSpongeInstance" should "reject level < 1" in:
    addUnitCubeLeaf()
    an[IllegalArgumentException] should be thrownBy:
      renderer.addRecursiveIASSpongeInstance(
        level = 0, position = Vector[3](0f, 0f, 0f), color = OPAQUE_RED, ior = 1.0f)

  it should "reject level > 14" in:
    addUnitCubeLeaf()
    an[IllegalArgumentException] should be thrownBy:
      renderer.addRecursiveIASSpongeInstance(
        level = 15, position = Vector[3](0f, 0f, 0f), color = OPAQUE_RED, ior = 1.0f)

  it should "fail when no leaf mesh has been uploaded" in:
    val id = renderer.addRecursiveIASSpongeInstance(
      level = 1, position = Vector[3](0f, 0f, 0f), color = OPAQUE_RED, ior = 1.0f)
    id should be < 0

  it should "register a single instance at level 1" taggedAs (Slow) in:
    addUnitCubeLeaf()
    val id = renderer.addRecursiveIASSpongeInstance(
      level = 1, position = Vector[3](0f, 0f, 0f), color = OPAQUE_RED, ior = 1.0f)
    id should be >= 0
    renderer.getInstanceCount() shouldBe 1
    renderer.isIASMode() shouldBe true

  it should "render a level-2 sponge to non-uniform pixels" taggedAs (Slow) in:
    addUnitCubeLeaf()
    val id = renderer.addRecursiveIASSpongeInstance(
      level = 2, position = Vector[3](0f, 0f, 0f), color = OPAQUE_RED, ior = 1.0f)
    id should be >= 0

    val img = renderer.render(TEST_IMAGE_SIZE)
    img should not be null // scalafix:ok DisableSyntax.null
    val pixels = img
    pixels.length shouldBe ImageValidation.imageByteSize(TEST_IMAGE_SIZE)

    // Non-uniform proof: at least one pixel must differ from pixel 0 by more
    // than a noise threshold. RenderHealth would flag a fully-uniform frame.
    val r0 = pixels(0) & 0xFF
    val g0 = pixels(1) & 0xFF
    val b0 = pixels(2) & 0xFF
    val hasVariation = pixels.grouped(4).exists { px =>
      val dr = math.abs((px(0) & 0xFF) - r0)
      val dg = math.abs((px(1) & 0xFF) - g0)
      val db = math.abs((px(2) & 0xFF) - b0)
      dr + dg + db > 8
    }
    hasVariation shouldBe true

  it should "render a level-3 sponge without errors" taggedAs (Slow) in:
    addUnitCubeLeaf()
    val id = renderer.addRecursiveIASSpongeInstance(
      level = 3, position = Vector[3](0f, 0f, 0f), color = OPAQUE_RED, ior = 1.0f)
    id should be >= 0
    val img = renderer.render(TEST_IMAGE_SIZE)
    img should not be null // scalafix:ok DisableSyntax.null
