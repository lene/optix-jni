package io.github.lene.optix.caustics

import io.github.lene.optix.ImageValidation
import io.github.lene.optix.RendererFixture
import io.github.lene.optix.TestUtilities
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Light
import menger.common.Material
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** What a flat pane of glass gives back to the floor: the one caustic whose answer is known
  * without a reference renderer (menger#150).
  *
  * A plane-parallel slab does not focus: the light it lets through arrives at the floor as it
  * left the light, only shifted, and weakened by the Fresnel loss of its two surfaces (about 10%
  * at 45 degrees, multiple reflections included). So under a slab, with caustics on, the floor
  * must get back that share of the direct light the slab blocks: T = (glass - opaque) / (bare -
  * opaque) is about 0.9, per pixel and on average.
  *
  * Three measurements follow from it:
  *  - T well below 0.9 is missing photon energy: the caustic is not in the units of the direct
  *    light it replaces (0.39 where 0.9 is right, before the fix: the direct light is shaded as
  *    albedo * I * cos * 0.7 with no 1/pi, the caustic as albedo / pi * E).
  *  - T well above 0 with next to no photons is the direct light passing through the glass: with
  *    caustics on, the shadow of the glass must be opaque, or light is counted twice.
  *  - T near 0 through a stack of slabs is photons cut off after too few bounces.
  */
class CausticsFlatGlassSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(160, 120)
  private val floorY = -2.0f
  // A 4 x 0.2 x 4 slab at the origin; light at 45 degrees along +x puts its shadow at x 0..4.
  private val slabTransform: Array[Float] =
    Array(4f, 0f, 0f, 0f, 0f, 0.2f, 0f, 0f, 0f, 0f, 4f, 0f)
  private val lightDirection = Vector[3](1f, -1f, 0f)
  // The camera looks at the shadow from beyond the slab's far edge: no ray in the frame meets the
  // slab (the top of the frame reaches the floor at x 1.3 and passes y = 0 at x 2.9, past the
  // slab's edge at 2), so every pixel shows the floor.
  private val cameraEye = Vector[3](6f, 4f, 0f)
  private val cameraTarget = Vector[3](3.5f, floorY, 0f)
  private val cameraFov = 30.0f

  // Fresnel transmission of a slab of n = 1.5 at 45 degrees, both surfaces and the reflections
  // between them: T = (1 - R)^2 / (1 - R^2), R = 0.0505.
  private val slabTransmission = 0.90

  private val identityTransform: Array[Float] =
    Array(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)

  private val opaque = Material(Color(0f, 0f, 0f, 1f), ior = 1.0f)
  private val glass = Material.Glass

  // Six slabs as ONE mesh, each 0.3 higher and 0.3 further against the light's drift than the one
  // below, so their shadows coincide. One instance, because photons are aimed per instance and
  // instances that overlap along the light would be counted once each.
  private val slabsInStack = 6
  private def slabStack(): menger.common.TriangleMeshData =
    val cube = TestUtilities.createUnitCubeMesh()
    val stride = cube.vertexStride
    val vertices = for
      k <- 0 until slabsInStack
      v <- 0 until cube.numVertices
      c <- 0 until stride
    yield
      val x = cube.vertices(v * stride + c)
      c match
        case 0 => x * 4f - 0.3f * k
        case 1 => x * 0.2f + 0.3f * k
        case 2 => x * 4f
        case _ => x
    val indices = for k <- 0 until slabsInStack; i <- cube.indices yield i + k * cube.numVertices
    menger.common.TriangleMeshData(vertices.toArray, indices.toArray, stride)

  private def buildScene(slab: Option[Material], stack: Boolean = false): Unit =
    renderer.clearAllInstances()
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, floorY)
    renderer.setTriangleMesh(if stack then slabStack() else TestUtilities.createUnitCubeMesh())
    val transform = if stack then identityTransform else slabTransform
    slab.foreach(renderer.addTriangleMeshInstance(transform, _))
    renderer.setLights(Array(Light.Directional(lightDirection, intensity = 1.0f)))
    renderer.setCamera(cameraEye, cameraTarget, Vector[3](0f, 1f, 0f), cameraFov)
    renderer.setShadows(true)
    renderer.setTransparentShadows(true)  // what menger's --caustics implies
    renderer.setAmbient(io.github.lene.optix.AmbientModel.Constant)

  private def render(
    slab: Option[Material], photons: Option[(Int, Int)], stack: Boolean = false
  ): Array[Double] =
    buildScene(slab, stack)
    photons match
      case Some((perIteration, iterations)) => renderer.enableCaustics(perIteration, iterations, 0.15f)
      case None                             => renderer.disableCaustics()
    val image = renderer.renderWithStats(imageSize).get.image
    Array.tabulate(imageSize.width * imageSize.height): i =>
      ImageValidation.getRGB(image, i).brightness.toDouble

  private def median(values: Seq[Double]): Double = values.sorted.apply(values.length / 2)

  /** T per pixel in the shadow's core: where the opaque slab blocks at least 90% of the deepest
    * shadow, a region the photon gather's blur of the shadow's edge does not reach. */
  private def transmissionInShadowCore(bare: Array[Double], opaqueSlab: Array[Double],
                                       glassSlab: Array[Double]): Seq[Double] =
    val depth = bare.indices.map(i => bare(i) - opaqueSlab(i))
    val deepest = depth.max
    withClue(s"the opaque slab casts no shadow (deepest $deepest); the scene is wrong. ") {
      deepest should be > 20.0
    }
    bare.indices.filter(depth(_) >= 0.9 * deepest).map(i => (glassSlab(i) - opaqueSlab(i)) / depth(i))

  private def transmissionWith(photons: (Int, Int), stack: Boolean = false): Seq[Double] =
    val bare = render(None, None)
    val opaqueSlab = render(Some(opaque), None, stack)
    val glassSlab = render(Some(glass), Some(photons), stack)
    transmissionInShadowCore(bare, opaqueSlab, glassSlab)

  behavior of "Caustics under a flat glass slab"

  it should "block the direct light with the slab's shadow when caustics carry it through" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    // One photon: its few deposits leave the median of the shadow core untouched, so what is
    // left is the direct light, which must not pass the glass.
    val transmission = median(transmissionWith((1, 1)))
    info(f"median T with one photon: $transmission%.3f")
    withClue(s"median T with no photon energy to speak of = $transmission; the direct light " +
      "reaches the floor through the glass, and the photons carry it again. ") {
      transmission should be < 0.1
    }

  it should "give back the light the slab's Fresnel loss leaves, no more and no less" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val transmission = median(transmissionWith((200000, 4)))
    info(f"median T through one slab: $transmission%.3f")
    withClue(s"median T = $transmission, expected about $slabTransmission (flat glass does not " +
      "focus, so it returns what it lets through). ") {
      transmission should (be > 0.8 and be < 1.0)
    }

  it should "still give back light that crossed twelve surfaces" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    // Six stacked slabs of a lossless glass return T = 1 / (1 + 6 r / (1 - r)) = 0.61 of the light
    // (r = 0.096 the reflectance of one slab). A photon needs twelve surface interactions to get
    // through; a cap of ten photon bounces cut every one of them off.
    val transmission = median(transmissionWith((200000, 4), stack = true))
    info(f"median T through six slabs: $transmission%.3f")
    withClue(s"median T = $transmission, expected about 0.6 through six slabs. ") {
      transmission should (be > 0.4 and be < 0.8)
    }

end CausticsFlatGlassSuite
