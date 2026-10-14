package io.github.lene.optix

import io.github.lene.optix.ColorConstants.OPAQUE_RED
import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import menger.common.Color
import menger.common.Material
import menger.common.TriangleMeshData
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Slow

/** Per-instance visibility: a hidden instance stays in the scene, but no ray hits it. Renders a
  * scene in alternative versions without rebuilding it -- menger renders the two levels of a
  * fractional glass sponge separately and mixes the images (lene/menger#69). */
class InstanceVisibilitySuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  // One square facing the camera, in front of a matte wall (the quad scaled 6x, at z = -5),
  // lit at an angle so the square also casts a shadow on the wall.
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
  private val blue = matteGrey.copy(color = Color(0.1f, 0.1f, 0.9f, 1f))
  private val centre = TestUtilities.Region.centered(
    TEST_IMAGE_SIZE.width / 2, TEST_IMAGE_SIZE.height / 2, TEST_IMAGE_SIZE.height / 16
  )
  // Mean per-channel difference, 0..255, between two renders of the same scene.
  private val SameImage = 0.5
  // The square must change the image clearly, or "hidden looks like absent" proves nothing.
  private val MinVisibleChange = 5.0

  private def meanDifference(a: Array[Byte], b: Array[Byte]): Double =
    a.indices.map(i => math.abs((a(i) & 0xff) - (b(i) & 0xff))).sum.toDouble / a.length

  private def centreColour(image: Array[Byte]): (Double, Double, Double) =
    val pixels = for
      y <- centre.y0 until centre.y1
      x <- centre.x0 until centre.x1
    yield ImageValidation.getRGBAt(image, TEST_IMAGE_SIZE, x, y)
    def mean(channel: ImageValidation.RGB => Int) = pixels.map(channel).sum.toDouble / pixels.size
    (mean(_.r), mean(_.g), mean(_.b))

  /** The wall, and the square in front of it unless `square` is None; the square's id goes to
    * `square` so a test can hide or show it before rendering. */
  private def render(square: Option[Int => Unit]): Array[Byte] =
    renderer.setLight(Vector[3](0.3f, -0.3f, -1f), 1.0f)
    renderer.clearAllInstances()
    renderer.clearTriangleMesh()
    val _ = renderer.addTriangleMesh(quad)
    val _ = renderer.addTriangleMeshInstance(wallTransform, matteGrey)
    square.foreach(change => change(renderer.addTriangleMeshInstance(identity, blue)))
    renderImage(TEST_IMAGE_SIZE)

  "setInstanceVisible" should "render a hidden instance as if it were absent, shadow included" taggedAs Slow in:
    val absent = render(None)
    val shown = render(Some(_ => ()))
    val hidden = render(Some(id => renderer.setInstanceVisible(id, visible = false) shouldBe 0))
    meanDifference(shown, absent) should be > MinVisibleChange
    meanDifference(hidden, absent) should be < SameImage

  it should "render an instance shown again exactly as before it was hidden" taggedAs Slow in:
    val shown = render(Some(_ => ()))
    val again = render(Some { id =>
      renderer.setInstanceVisible(id, visible = false) shouldBe 0
      val _ = renderImage(TEST_IMAGE_SIZE)
      renderer.setInstanceVisible(id, visible = true) shouldBe 0
    })
    meanDifference(again, shown) should be < SameImage

  it should "return -1 for an unknown instance id" in:
    renderer.clearAllInstances()
    renderer.setInstanceVisible(12345, visible = false) shouldBe -1
    renderer.setInstanceVisible(-1, visible = true) shouldBe -1

  // A recursive-IAS sponge predicts its material slot from the instances added before it. A
  // hidden instance keeps its slot (removeInstance would drop it and shift the sponge's).
  it should "keep the material of a recursive-IAS sponge added after a hidden instance" taggedAs Slow in:
    def spongeCentre(hideEarlier: Boolean): (Double, Double, Double) =
      renderer.setLight(Vector[3](0.3f, -0.3f, -1f), 1.0f)
      renderer.clearAllInstances()
      renderer.clearTriangleMesh()
      val _ = renderer.addTriangleMesh(quad)
      val square = renderer.addTriangleMeshInstance(identity, blue)
      if hideEarlier then renderer.setInstanceVisible(square, visible = false) shouldBe 0
      renderer.setTriangleMesh(TestUtilities.createUnitCubeMesh())
      val sponge = renderer.addRecursiveIASSpongeInstance(
        level = 1, position = Vector[3](0f, 0f, 0f), color = OPAQUE_RED, ior = 1.0f)
      sponge should be >= 0
      if !hideEarlier then renderer.setInstanceVisible(square, visible = false) shouldBe 0
      centreColour(renderImage(TEST_IMAGE_SIZE))
    val (r, g, b) = spongeCentre(hideEarlier = true)
    info(f"sponge centre: r $r%.1f, g $g%.1f, b $b%.1f")
    r should be > (g + MinVisibleChange)
    r should be > (b + MinVisibleChange)
    spongeCentre(hideEarlier = false) shouldBe (r, g, b)
