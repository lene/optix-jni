package io.github.lene.optix

import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import menger.common.Color
import menger.common.Material
import menger.common.TriangleMeshData
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Slow

/** A thin film is a sheet, not a solid (optix-jni#113): it looks the same from both sides, whatever
  * the winding or normal direction of its triangles, and light passes straight through it.
  * Projected 4D faces have arbitrary winding, so a film cube used to differ between two rotations. */
class FilmSidesSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val identity = Array(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)
  private val wallScale = 6f
  private val wallDistance = 2f
  private val matteGrey = Material(Color(0.7f, 0.7f, 0.7f, 1f), 1.0f, specular = 0.0f)
  private val plastic = Material(Color(0.2f, 0.4f, 0.8f, 1f), 1.5f, roughness = 0.3f)
  private val film = Material.Film

  // Differences of up to this many units per channel (0..255) count as the same pixel.
  private val PixelTolerance = 2
  private val MaxDifferingFraction = 0.005

  /** A unit square at height z whose vertex normals point to `normalZ`. `reversed` flips the
    * winding and the normal: the same square as a mesh built the other way round. */
  private def square(z: Float, facing: Float, reversed: Boolean) =
    val nz = if reversed then -facing else facing
    val corners = Seq((-0.5f, -0.5f, 0f, 0f), (0.5f, -0.5f, 1f, 0f), (0.5f, 0.5f, 1f, 1f), (-0.5f, 0.5f, 0f, 1f))
    val vertices = corners.flatMap((x, y, u, v) => Seq(x, y, z, 0f, 0f, nz, u, v))
    val indices = if reversed then Array(0, 2, 1, 0, 3, 2) else Array(0, 1, 2, 0, 2, 3)
    TriangleMeshData(vertices.toArray, indices, 8)

  /** Film (or other) quad at z = side * 0.5 in front of a matte wall at z = -side * wallDistance,
    * seen by a camera at z = side * 3. side = 1 is the usual view, side = -1 the same scene turned
    * by 180 degrees about the y axis, except that the quad keeps its normals: it is seen from the back. */
  private def render(material: Material, reversed: Boolean, side: Float = 1f): Array[Byte] =
    renderer.setSphere(Vector[3](0f, 0f, -50f), 0.01f)
    renderer.setLight(Vector[3](0.3f * side, -0.3f, -side), 1.0f)
    setCameraEye(Vector[3](0f, 0.5f, 3f * side))
    renderer.clearAllInstances()
    renderer.clearTriangleMesh()
    val wallMesh = square(-side * wallDistance / wallScale, side, reversed = false)
    val wallTransform = Array(wallScale, 0f, 0f, 0f, 0f, wallScale, 0f, 0f, 0f, 0f, wallScale, 0f)
    val _ = renderer.addTriangleMesh(wallMesh)
    val _ = renderer.addTriangleMeshInstance(wallTransform, matteGrey)
    val _ = renderer.addTriangleMesh(square(side * 0.5f, 1f, reversed))
    val _ = renderer.addTriangleMeshInstance(identity, material)
    renderImage(TEST_IMAGE_SIZE)

  private def differingFraction(a: Array[Byte], b: Array[Byte]): Double =
    val pixels = a.length / 4
    val differing = (0 until pixels).count: p =>
      (0 until 3).exists(c => math.abs((a(4 * p + c) & 0xff) - (b(4 * p + c) & 0xff)) > PixelTolerance)
    differing.toDouble / pixels

  "A film quad" should "look the same with reversed winding and normals" taggedAs Slow in:
    val fraction = differingFraction(render(film, reversed = false), render(film, reversed = true))
    info(f"differing pixels: ${fraction * 100}%.2f %%")
    fraction should be <= MaxDifferingFraction

  it should "look the same from the back, in a symmetric scene" taggedAs Slow in:
    val fraction = differingFraction(render(film, reversed = false), render(film, reversed = false, side = -1f))
    info(f"differing pixels: ${fraction * 100}%.2f %%")
    fraction should be <= MaxDifferingFraction

  "A plastic quad" should "look the same with reversed winding and normals (regression guard)" taggedAs Slow in:
    differingFraction(render(plastic, reversed = false), render(plastic, reversed = true)) should
      be <= MaxDifferingFraction

  // A red opaque quad off to the side, behind a film quad that fills the view. Light that passes
  // straight through the film shows the red quad where it is without the film. (A film quad as the
  // far object would only be visible by its faint reflections: a film has no body that tints.)
  private val redMatte = Material(Color(0.9f, 0.1f, 0.1f, 1f), 1.0f, specular = 0.0f)

  private def redQuadCentroid(withNearFilm: Boolean, reversedNear: Boolean): (Double, Double) =
    renderer.setSphere(Vector[3](0f, 0f, -50f), 0.01f)
    renderer.setLight(Vector[3](0.3f, -0.3f, -1f), 1.0f)
    setCameraEye(Vector[3](0f, 0.5f, 3f))
    renderer.clearAllInstances()
    renderer.clearTriangleMesh()
    val wallTransform = Array(wallScale, 0f, 0f, 0f, 0f, wallScale, 0f, 0f, 0f, 0f, wallScale, 0f)
    val _ = renderer.addTriangleMesh(square(-wallDistance / wallScale, 1f, reversed = false))
    val _ = renderer.addTriangleMeshInstance(wallTransform, matteGrey)
    val farTransform = Array(1f, 0f, 0f, 0.8f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, -1f)
    val _ = renderer.addTriangleMesh(square(0f, 1f, reversed = false))
    val _ = renderer.addTriangleMeshInstance(farTransform, redMatte)
    if withNearFilm then
      val nearTransform = Array(3f, 0f, 0f, 0f, 0f, 3f, 0f, 0f, 0f, 0f, 1f, 0.5f)
      val _ = renderer.addTriangleMesh(square(0f, 1f, reversedNear))
      val _ = renderer.addTriangleMeshInstance(nearTransform, film)
    val image = renderImage(TEST_IMAGE_SIZE)
    val red = (0 until TEST_IMAGE_SIZE.width * TEST_IMAGE_SIZE.height).filter: p =>
      val rgb = ImageValidation.getRGB(image, p)
      rgb.r - (rgb.g + rgb.b) / 2 > 40
    info(s"red pixels: ${red.size}")
    red.nonEmpty shouldBe true
    (red.map(_ % TEST_IMAGE_SIZE.width).sum.toDouble / red.size,
      red.map(_ / TEST_IMAGE_SIZE.width).sum.toDouble / red.size)

  "A film quad in front of an opaque quad" should "show the opaque quad where it is without the film" taggedAs Slow in:
    val without = redQuadCentroid(withNearFilm = false, reversedNear = false)
    List(false, true).foreach: reversed =>
      withClue(s"film reversed = $reversed: "):
        val (x, y) = redQuadCentroid(withNearFilm = true, reversedNear = reversed)
        info(f"red quad centroid without film (${without._1}%.1f, ${without._2}%.1f), with ($x%.1f, $y%.1f)")
        x shouldBe (without._1 +- 1.5)
        y shouldBe (without._2 +- 1.5)
