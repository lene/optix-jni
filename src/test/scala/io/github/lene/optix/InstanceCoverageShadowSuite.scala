package io.github.lene.optix

import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import menger.common.Color
import menger.common.Material
import menger.common.TriangleMeshData
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Slow

/** The shadow of a partly present instance (setInstanceCoverage) is weaker, but it must not hide
  * what lies behind it from the shadow ray: the hole caps of a fractional sponge fade with
  * 1 - f, and with transparent shadows off (the default for an uncoloured scene) the shadow ray
  * stopped at the cap, charged it its coverage, and never tested the tunnel wall behind. The
  * floor shadow of the sponge was speckled with light that had passed through solid geometry
  * (menger#57). */
class InstanceCoverageShadowSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  // A flat sheet, one quad: a stand-in for a hole cap.
  private val sheet = TriangleMeshData(
    Array[Float](
      -0.5f, 0f, -0.5f, 0f, 1f, 0f, 0f, 0f,
      -0.5f, 0f, 0.5f, 0f, 1f, 0f, 0f, 1f,
      0.5f, 0f, 0.5f, 0f, 1f, 0f, 1f, 1f,
      0.5f, 0f, -0.5f, 0f, 1f, 0f, 1f, 0f
    ),
    Array(0, 1, 2, 0, 2, 3),
    8
  )

  private def scaleAndLift(width: Float, height: Float, y: Float) =
    Array(width, 0f, 0f, 0f, 0f, height, 0f, y, 0f, 0f, width, 0f)

  // The sheet (the stand-in for a hole cap) hangs at y = 0; the slab (the tunnel wall behind it)
  // lies above it and, seen from the floor, is wider than the sheet's whole shadow.
  private val sheetTransform = scaleAndLift(width = 4f, height = 1f, y = 0f)
  private val slabTransform = scaleAndLift(width = 6f, height = 0.2f, y = 0.5f)
  private val matteGrey = Material(Color(0.7f, 0.7f, 0.7f, 1f), 1.0f, specular = 0.0f)
  private val PartlyPresent = 0.5f
  // Brightness units are 0..255 per channel; a few units absorb sampling noise.
  private val Tolerance = 4.0
  // The sheet's half shadow must differ clearly from the slab's full one for the test to mean anything.
  private val MinContrast = 10.0

  /** Mean brightness of a view of the floor, in which the sheet and the slab are not visible,
    * only the shadow they cast. */
  private def floorBrightness(sheetCoverage: Option[Float], withSlab: Boolean): Double =
    renderer.setLight(Vector[3](1f, -1f, 0f), 1.0f)  // travels +x and down: shadows fall to +x
    renderer.setCamera(
      Vector[3](8f, 3f, 0f), Vector[3](2.5f, -2f, 0f), Vector[3](0f, 1f, 0f), 16f)
    renderer.clearPlanes()
    renderer.addPlaneSolidColor(1, true, -2f, 0.8f, 0.8f, 0.8f)
    renderer.clearAllInstances()
    sheetCoverage.foreach { coverage =>
      val _ = renderer.addTriangleMesh(sheet)
      val id = renderer.addTriangleMeshInstance(sheetTransform, matteGrey)
      renderer.setInstanceCoverage(id, coverage)
    }
    if withSlab then
      val _ = renderer.addTriangleMesh(TestUtilities.createUnitCubeMesh())
      val _ = renderer.addTriangleMeshInstance(slabTransform, matteGrey)
    val size = TEST_IMAGE_SIZE
    TestUtilities.regionBrightness(
      renderImage(size), size, TestUtilities.Region(0, 0, size.width, size.height))

  "the shadow of a partly present instance" should
    "be blocked by a solid slab behind it, as the slab's own shadow is" taggedAs Slow in:
    val lit = floorBrightness(None, withSlab = false)
    val slabOnly = floorBrightness(None, withSlab = true)
    val sheetOnly = floorBrightness(Some(PartlyPresent), withSlab = false)
    val sheetAndSlab = floorBrightness(Some(PartlyPresent), withSlab = true)
    info(f"lit $lit%.1f, slab only $slabOnly%.1f, sheet only $sheetOnly%.1f, " +
      f"sheet and slab $sheetAndSlab%.1f")
    lit - slabOnly should be > 2 * MinContrast
    sheetOnly - slabOnly should be > MinContrast
    sheetAndSlab shouldBe (slabOnly +- Tolerance)

  it should "still cast its own weaker shadow when nothing lies behind it" taggedAs Slow in:
    val lit = floorBrightness(None, withSlab = false)
    val slabOnly = floorBrightness(None, withSlab = true)
    val sheetOnly = floorBrightness(Some(PartlyPresent), withSlab = false)
    info(f"lit $lit%.1f, slab only $slabOnly%.1f, sheet only $sheetOnly%.1f")
    sheetOnly should be < (lit - MinContrast)
    sheetOnly should be > (slabOnly + MinContrast)
