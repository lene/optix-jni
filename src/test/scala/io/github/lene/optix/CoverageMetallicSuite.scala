package io.github.lene.optix

import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import menger.common.Color
import menger.common.Material
import menger.common.TriangleMeshData
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Slow

/** A partly covering (coverage-alpha) instance must shade like the same material at full
  * coverage, blended with what lies behind it. The coverage path used plain diffuse shading
  * and ignored `metallic`, so the fading hole caps of a fractional gold sponge came out
  * brighter than the opaque metal around them (menger#55 follow-up, 2026-10-02). */
class CoverageMetallicSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  // One square facing the camera: no back face for the continuation ray to hit again.
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
  private val identity = Array(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
  // A matte wall behind the quad (the quad scaled 6x, its plane moved to z = -2): primary and
  // continuation rays both see it, whereas a missed secondary ray sees a different background.
  private val wallTransform = Array(6f, 0f, 0f, 0f, 0f, 6f, 0f, 0f, 0f, 0f, 6f, -5f)
  private val matteGrey = Material(Color(0.7f, 0.7f, 0.7f, 1f), 1.0f)
  private val centre = TestUtilities.Region.centered(
    TEST_IMAGE_SIZE.width / 2, TEST_IMAGE_SIZE.height / 2, TEST_IMAGE_SIZE.height / 16
  )
  // Brightness units are 0..255 per channel; a few units absorb sampling noise.
  private val Tolerance = 6.0

  private def centreBrightness(material: Option[Material]): Double =
    renderer.clearAllInstances()
    renderer.clearTriangleMesh()
    val _ = renderer.addTriangleMesh(quad)
    val _ = renderer.addTriangleMeshInstance(wallTransform, matteGrey)
    material.foreach(m => renderer.addTriangleMeshInstance(identity, m))
    TestUtilities.regionBrightness(renderImage(TEST_IMAGE_SIZE), TEST_IMAGE_SIZE, centre)

  "A half-covering gold instance" should "blend the opaque gold with what lies behind it" taggedAs Slow in:
    // Light the quad's front face, so diffuse shading would differ clearly from the metal's
    // reflection of the background.
    renderer.setLight(Vector[3](0.3f, -0.3f, -1f), 1.0f)
    val opaque = centreBrightness(Some(Material.Gold))
    val behind = centreBrightness(None)
    val half = centreBrightness(Some(Material.Gold.copy(color = Material.Gold.color.copy(a = 0.5f))))
    info(f"opaque $opaque%.1f, behind $behind%.1f, half $half%.1f")
    half shouldBe ((opaque + behind) / 2 +- Tolerance)
