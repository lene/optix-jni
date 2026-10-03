package io.github.lene.optix

import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import menger.common.Color
import menger.common.Material
import menger.common.TriangleMeshData
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Slow

/** Per-instance coverage: how much of a triangle instance is present, separate from its
  * material's alpha. A refractive material's alpha is Beer-Lambert absorption, so scaling it
  * could not fade a glass hole cap of a fractional sponge: the caps kept their full Fresnel
  * reflection and refraction at every fractional level (menger#56). Coverage c renders
  * c * (the instance as usual) + (1 - c) * (what lies behind it), for every material. */
class InstanceCoverageSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  // One square facing the camera, in front of a matte wall (the quad scaled 6x, at z = -2):
  // primary and continuation rays both see the wall.
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
  private val wallTransform = Array(6f, 0f, 0f, 0f, 0f, 6f, 0f, 0f, 0f, 0f, 6f, -5f)
  private val matteGrey = Material(Color(0.7f, 0.7f, 0.7f, 1f), 1.0f)
  private val centre = TestUtilities.Region.centered(
    TEST_IMAGE_SIZE.width / 2, TEST_IMAGE_SIZE.height / 2, TEST_IMAGE_SIZE.height / 16
  )
  // Brightness units are 0..255 per channel; a few units absorb sampling noise.
  private val Tolerance = 6.0
  // A blend is only meaningful if the instance and what lies behind it differ clearly.
  private val MinContrast = 20.0

  /** Centre brightness with the quad absent (None) or present with `material` and `coverage`
    * (None = never set, i.e. the default). */
  private def centreBrightness(material: Option[Material], coverage: Option[Float]): Double =
    renderer.setLight(Vector[3](0.3f, -0.3f, -1f), 1.0f)
    renderer.clearAllInstances()
    renderer.clearTriangleMesh()
    val _ = renderer.addTriangleMesh(quad)
    val _ = renderer.addTriangleMeshInstance(wallTransform, matteGrey)
    material.foreach { m =>
      val id = renderer.addTriangleMeshInstance(identity, m)
      coverage.foreach(c => renderer.setInstanceCoverage(id, c) shouldBe 0)
    }
    TestUtilities.regionBrightness(renderImage(TEST_IMAGE_SIZE), TEST_IMAGE_SIZE, centre)

  private def blendsHalfway(material: Material) =
    val full = centreBrightness(Some(material), None)
    val behind = centreBrightness(None, None)
    val half = centreBrightness(Some(material), Some(0.5f))
    info(f"full $full%.1f, behind $behind%.1f, half $half%.1f")
    math.abs(full - behind) should be > MinContrast
    half shouldBe ((full + behind) / 2 +- Tolerance)

  "setInstanceCoverage" should "blend an opaque instance halfway with what lies behind it" taggedAs Slow in:
    blendsHalfway(matteGrey.copy(color = Color(0.1f, 0.1f, 0.9f, 1f)))

  it should "blend a refractive instance halfway with what lies behind it" taggedAs Slow in:
    blendsHalfway(Material.Diamond)

  // Hole caps used to fade by material alpha; for a non-refractive material coverage must give
  // the identical image, shadow on the wall included.
  it should "render a non-refractive instance exactly like its material at alpha = coverage" taggedAs Slow in:
    def image(material: Material, coverage: Option[Float]): Array[Byte] =
      renderer.setLight(Vector[3](0.3f, -0.3f, -1f), 1.0f)
      renderer.clearAllInstances()
      renderer.clearTriangleMesh()
      val _ = renderer.addTriangleMesh(quad)
      val _ = renderer.addTriangleMeshInstance(wallTransform, matteGrey)
      val id = renderer.addTriangleMeshInstance(identity, material)
      coverage.foreach(c => renderer.setInstanceCoverage(id, c) shouldBe 0)
      renderImage(TEST_IMAGE_SIZE)
    val blue = Color(0.1f, 0.1f, 0.9f, 1f)
    val byAlpha = image(matteGrey.copy(color = blue.copy(a = 0.4f)), None)
    val byCoverage = image(matteGrey.copy(color = blue), Some(0.4f))
    byCoverage shouldEqual byAlpha

  it should "make an instance with coverage 0 disappear" taggedAs Slow in:
    val behind = centreBrightness(None, None)
    centreBrightness(Some(Material.Diamond), Some(0f)) shouldBe (behind +- Tolerance)

  it should "render coverage 1 like an instance whose coverage was never set" taggedAs Slow in:
    val default = centreBrightness(Some(Material.Diamond), None)
    centreBrightness(Some(Material.Diamond), Some(1f)) shouldBe (default +- 1.0)

  it should "return -1 for an unknown instance id" in:
    renderer.setInstanceCoverage(7, 0.5f) shouldBe -1
    renderer.setInstanceCoverage(-1, 0.5f) shouldBe -1

  it should "reject a coverage outside [0, 1]" in:
    val _ = renderer.addTriangleMesh(quad)
    val id = renderer.addTriangleMeshInstance(identity, matteGrey)
    an[IllegalArgumentException] should be thrownBy renderer.setInstanceCoverage(id, 1.5f)
    an[IllegalArgumentException] should be thrownBy renderer.setInstanceCoverage(id, -0.1f)
