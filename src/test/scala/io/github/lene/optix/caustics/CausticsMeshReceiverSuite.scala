package io.github.lene.optix.caustics

import io.github.lene.optix.RendererFixture
import io.github.lene.optix.ImageValidation
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Material
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Phase 2 Task 3: a glass sphere casts a caustic onto a diffuse RECEIVER MESH (ior <= 1.05),
  * not only the analytic floor plane. Whole-image caustics-on-minus-off delta, camera-independent
  * (mirrors CausticsCoverageSuite's delta-isolation approach) so the test doesn't depend on
  * guessing exactly where on the receiver the caustic projects. Unlike Task 1's cross-scene
  * comparison (which carried scene-composition noise), this is a single-scene on/off delta —
  * the same proven-safe pattern CausticsCoverageSuite uses.
  */
class CausticsMeshReceiverSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(200, 150)
  private val casterRadius = 1.0f
  private val photonsPerIter = 100000
  private val causticIterations = 4
  private val lightIntensity = 500.0f

  private val casterTransform: Array[Float] =
    Array(casterRadius, 0f, 0f, 0f, 0f, casterRadius, 0f, 0f, 0f, 0f, casterRadius, 0f)

  // Diffuse receiver: a flattened (oblate) sphere instance below the glass caster, standing in
  // for a "receiver mesh" rather than the analytic floor plane. addSphereInstance takes a
  // general 3x4 affine transform (confirmed from CausticsCoverageSuite's own sphereTransform
  // usage), so a non-uniform scale (wide X/Z, thin Y) turns the unit sphere into a flat diffuse
  // pad — still a real mesh/instance closest-hit, not a plane, which is what this task's
  // instance-deposit branch (__closesthit__photon) needs to exercise. No addBoxInstance/cube
  // helper exists on OptiXRenderer at this level (only sphere/triangleMesh/cylinder/cone/curve
  // instance methods) — a triangle-mesh box would need raw vertex/index buffers, unnecessary
  // complexity for this test.
  private val receiverTransform: Array[Float] =
    Array(6.0f, 0f, 0f, 0f, 0f, 0.1f, 0f, -2.0f, 0f, 0f, 6.0f, 0f)

  private def setupMeshReceiverScene(casterMaterial: Material): Unit =
    renderer.clearAllInstances()
    renderer.addSphereInstance(casterTransform, casterMaterial)
    val receiverMaterial = Material(Color(0.9f, 0.9f, 0.9f, 1.0f), ior = 1.0f)
    renderer.addSphereInstance(receiverTransform, receiverMaterial)
    renderer.clearPlanes()
    renderer.setCamera(
      Vector[3](0.0f, 4.0f, 8.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      45.0f
    )
    renderer.setLight(Vector[3](0.0f, -1.0f, 0.0f), lightIntensity)

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def meshCausticDelta(casterMaterial: Material): (Double, Double, Double) =
    setupMeshReceiverScene(casterMaterial)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    val onResult = renderer.renderWithStats(imageSize).get
    val onPixels = for
      y <- 0 until imageSize.height
      x <- 0 until imageSize.width
    yield ImageValidation.getRGBAt(onResult.image, imageSize, x, y)
    setupMeshReceiverScene(casterMaterial)
    renderer.disableCaustics()
    val offResult = renderer.renderWithStats(imageSize).get
    val offPixels = for
      y <- 0 until imageSize.height
      x <- 0 until imageSize.width
    yield ImageValidation.getRGBAt(offResult.image, imageSize, x, y)
    (
      (onPixels.map(_.r).sum - offPixels.map(_.r).sum).toDouble / onPixels.length,
      (onPixels.map(_.g).sum - offPixels.map(_.g).sum).toDouble / onPixels.length,
      (onPixels.map(_.b).sum - offPixels.map(_.b).sum).toDouble / onPixels.length
    )

  behavior of "Diffuse mesh-instance caustic receiver"

  it should "deposit a caustic onto a diffuse receiver mesh instance (no floor plane present)" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val clearGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass)
    val (dr, dg, db) = meshCausticDelta(clearGlass)
    withClue(s"whole-image caustic delta (on-off), no floor plane, diffuse receiver mesh only: " +
      s"R=$dr G=$dg B=$db; must be nonzero — the receiver mesh is the ONLY diffuse surface in " +
      "the scene, so a zero delta means mesh-instance deposit isn't working. ") {
      (dr + dg + db) should be > 0.0
    }

end CausticsMeshReceiverSuite
