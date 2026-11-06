package io.github.lene.optix

import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Ambient occlusion as the default ambient light (optix-jni#106, option A).
  *
  * Most scenes switch the direct light off (intensity 0), so a pixel is albedo times the ambient
  * fill alone and the assertions are about the ambient term. The sponge scenes keep their light.
  */
class AmbientOcclusionSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val imageSize = ImageSize(800, 600)
  private val ambientFill = 0.3f

  // ---- camera projection, to find the pixels that show a given world point ----
  private final case class P3(x: Double, y: Double, z: Double):
    def +(o: P3): P3 = P3(x + o.x, y + o.y, z + o.z)
    def -(o: P3): P3 = P3(x - o.x, y - o.y, z - o.z)
    def *(s: Double): P3 = P3(x * s, y * s, z * s)
    def dot(o: P3): Double = x * o.x + y * o.y + z * o.z
    def cross(o: P3): P3 = P3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    def norm: P3 = this * (1.0 / math.sqrt(dot(this)))
    def v3: Vector[3] = Vector[3](x.toFloat, y.toFloat, z.toFloat)

  /** Mirrors SceneParameters::setCamera: u = up x w, v = w x u, horizontal fov. */
  private final case class Cam(eye: P3, at: P3, fov: Float):
    private val w = (at - eye).norm
    private val u = P3(0, 1, 0).cross(w).norm
    private val v = w.cross(u)
    private val halfWidth = math.tan(math.toRadians(fov / 2.0))
    private val halfHeight = halfWidth * imageSize.height / imageSize.width
    def project(p: P3): (Double, Double) =
      val d = p - eye
      val depth = d.dot(w)
      (imageSize.width / 2.0 * (1 + d.dot(u) / depth / halfWidth),
       imageSize.height / 2.0 * (1 - d.dot(v) / depth / halfHeight))
    def ray(px: Int, py: Int): P3 =
      (u * halfWidth * (2 * (px + 0.5) / imageSize.width - 1)
        + v * halfHeight * (1 - 2 * (py + 0.5) / imageSize.height) + w).norm

  private final case class Region(x0: Int, y0: Int, x1: Int, y1: Int):
    def pixels: Seq[(Int, Int)] = for y <- y0 until y1; x <- x0 until x1 yield (x, y)

  /** The bounding box of the projected corners, shrunk by 25% on each side so it stays inside. */
  private def region(cam: Cam, corners: Seq[P3]): Region =
    val ps = corners.map(cam.project)
    val (xa, xb) = (ps.map(_._1).min, ps.map(_._1).max)
    val (ya, yb) = (ps.map(_._2).min, ps.map(_._2).max)
    val (mx, my) = ((xb - xa) * 0.25, (yb - ya) * 0.25)
    Region((xa + mx).toInt, (ya + my).toInt, (xb - mx).toInt, (yb - my).toInt)

  /** The corners of a square of half-width h around c, flat in y ('y') or at constant z. */
  private def square(c: P3, h: Double, axis: Char): Seq[P3] = axis match
    case 'y' => for a <- Seq(-h, h); b <- Seq(-h, h) yield c + P3(a, 0, b)
    case _   => for a <- Seq(-h, h); b <- Seq(-h, h) yield c + P3(a, b, 0)

  /** A patch of the image around the pixel that shows the world point p. */
  private def patchAround(cam: Cam, p: P3, halfPixels: Int = 4): Region =
    val (cx, cy) = cam.project(p)
    Region(cx.toInt - halfPixels, cy.toInt - halfPixels, cx.toInt + halfPixels, cy.toInt + halfPixels)

  private def level(img: Array[Byte], x: Int, y: Int, channel: Int = 0): Int =
    img((y * imageSize.width + x) * 4 + channel) & 0xff
  private def brightness(img: Array[Byte], x: Int, y: Int): Double =
    (0 to 2).map(level(img, x, y, _)).sum / 3.0
  private def values(img: Array[Byte], r: Region): Seq[Double] =
    r.pixels.map { case (x, y) => brightness(img, x, y) }
  private def mean(img: Array[Byte], r: Region): Double = values(img, r).sum / r.pixels.size
  private def stddev(img: Array[Byte], r: Region): Double =
    val vs = values(img, r)
    val m = vs.sum / vs.size
    math.sqrt(vs.map(v => (v - m) * (v - m)).sum / vs.size)

  // ---- scenes ----
  private val floorAlbedo = 0.7f

  private def addFloor(y: Float): Unit =
    renderer.addPlaneInstance(Vector[3](0f, 1f, 0f), y, Material.matte(Color(floorAlbedo, floorAlbedo, floorAlbedo)))

  private def lookAt(cam: Cam): Unit =
    renderer.clearPlanes()  // the fixture's default floor at y = -2
    renderer.updateImageDimensions(imageSize)
    renderer.setCamera(cam.eye.v3, cam.at.v3, Vector[3](0f, 1f, 0f), cam.fov)

  private def render(model: AmbientModel, accumulate: Int = 1): Array[Byte] =
    renderer.setAmbient(model)
    renderer.setAccumulationFrames(accumulate)
    renderer.renderWithStats(imageSize).get().image

  /** A slab 1 above a floor at y = 0 (x from 0 to 8); the camera sees the floor at x = 1, under
    * the slab, past the slab's front edge. The direct light is off. */
  private val slabCam = Cam(P3(-3, 1, 0), P3(1, 0, 0), 60f)
  private val underSlab = P3(1, 0, 0)

  private def slabScene(slab: Material): Unit =
    lookAt(slabCam)
    renderer.setLight(Vector[3](0f, -1f, 0f), 0.0f)
    addFloor(0f)
    renderer.setTriangleMesh(TestUtilities.createUnitCubeMesh())
    renderer.addTriangleMeshInstance(
      Array(8f, 0f, 0f, 4f, 0f, 0.2f, 0f, 1.1f, 0f, 0f, 8f, 0f), slab, TextureIndex.NoTexture)

  /** Not refractive, so only its surface model attenuates: half its light is passed. */
  private val translucent = Material(Color(0.3f, 0.3f, 0.3f, 0.5f), 1.0f)
  private val opaqueSlab = Material.matte(Color(0.5f, 0.5f, 0.5f))

  // ---- open surfaces ----
  "A floor with nothing above it" should "get exactly the constant fill by default" in:
    lookAt(Cam(P3(0, 1.5, 3), P3(0, 0, 0), 60f))
    renderer.setLight(Vector[3](0f, -1f, 0f), 0.0f)
    addFloor(0f)
    val occluded = render(AmbientModel.Occlusion())
    val constant = render(AmbientModel.Constant)
    occluded shouldBe constant
    // The fill is 0.3 of the albedo, as before the occlusion weight existed.
    val expected = ambientFill * floorAlbedo * 255
    level(constant, imageSize.width / 2, imageSize.height - 20) should (be >= expected.toInt - 1 and be <= expected.toInt + 1)

  "An occluder beyond the radius" should "not darken" in:
    slabScene(opaqueSlab)
    val constant = render(AmbientModel.Constant)
    render(AmbientModel.Occlusion(radius = 0.5f)) shouldBe constant

  it should "darken once the radius reaches it, the more the farther the radius reaches" in:
    slabScene(opaqueSlab)
    val patch = patchAround(slabCam, underSlab)
    val constant = mean(render(AmbientModel.Constant), patch)
    val near = mean(render(AmbientModel.Occlusion(radius = 1.5f)), patch)
    val unlimited = mean(render(AmbientModel.Occlusion()), patch)
    info(f"floor under the slab: constant $constant%.1f, radius 1.5 $near%.1f, unlimited $unlimited%.1f")
    near should be < constant - 5
    unlimited should be < near - 5

  "A transparent occluder" should "darken by its shadow attenuation, not fully" in:
    renderer.setTransparentShadows(true)
    val patch = patchAround(slabCam, underSlab)
    slabScene(translucent)
    val constant = mean(render(AmbientModel.Constant), patch)
    val throughTranslucent = mean(render(AmbientModel.Occlusion()), patch)
    renderer.clearAllInstances()
    slabScene(opaqueSlab)
    val throughStone = mean(render(AmbientModel.Occlusion()), patch)
    info(f"floor under the slab: constant $constant%.1f, translucent $throughTranslucent%.1f, opaque $throughStone%.1f")
    throughTranslucent should be < constant - 1
    throughTranslucent should be > throughStone + 10

  // ---- cavities ----
  "The floor of a square shaft" should "get the analytic cosine-weighted visibility" in:
    val camFov = 1.0f
    renderer.updateImageDimensions(imageSize)
    // Looking straight down: the up vector must not be parallel to the view axis.
    renderer.setCamera(Vector[3](0f, 8f, 0f), Vector[3](0f, 0f, 0f), Vector[3](0f, 0f, -1f), camFov)
    renderer.setLight(Vector[3](0f, -1f, 0f), 0.0f)
    renderer.setShadows(false)
    renderer.addPlaneInstance(Vector[3](0f, 1f, 0f), 0f, Material.matte(Color(1f, 1f, 1f)))
    renderer.setTriangleMesh(TestUtilities.createUnitCubeMesh())
    val wall = Material.matte(Color(0.5f, 0.5f, 0.5f))
    // inner square [-0.5, 0.5]^2, walls 0.5 thick, height 1 (y from 0 to 1); the opening is at y = 1
    Seq((-0.75f, 0f, 0.5f, 2f), (0.75f, 0f, 0.5f, 2f), (0f, -0.75f, 1f, 0.5f), (0f, 0.75f, 1f, 0.5f))
      .foreach { case (x, z, sx, sz) =>
        renderer.addTriangleMeshInstance(
          Array(sx, 0f, 0f, x, 0f, 1f, 0f, 0.5f, 0f, 0f, sz, z), wall, TextureIndex.NoTexture)
      }
    val img = render(AmbientModel.Occlusion(samples = 64))

    // The camera sees a patch of the floor 8 below it: half-extents at the floor plane.
    val halfX = 8.0 * math.tan(math.toRadians(camFov / 2.0))
    val halfZ = halfX * imageSize.height / imageSize.width
    /** (1/pi) times the integral over the opening of h^2 / r^4 dA, h = 1: the share of the
      * cosine-weighted hemisphere above (px, pz) that the opening leaves open. */
    def visibility(px: Double, pz: Double): Double =
      val n = 200
      (for i <- 0 until n; j <- 0 until n yield
        val dx = -0.5 + (i + 0.5) / n - px
        val dz = -0.5 + (j + 0.5) / n - pz
        val r2 = dx * dx + dz * dz + 1.0
        1.0 / (n * n) / (r2 * r2)).sum / math.Pi
    val grid = for i <- 0 to 6; j <- 0 to 6 yield (halfX * (2.0 * i / 6 - 1) * 0.98, halfZ * (2.0 * j / 6 - 1) * 0.98)
    val analytic = grid.map { case (x, z) => visibility(x, z) }.sum / grid.size
    // The shader truncates to a byte: add half a level back before dividing out the fill.
    val observed = (for y <- 0 until imageSize.height; x <- 0 until imageSize.width yield level(img, x, y).toDouble)
      .sum / (imageSize.width * imageSize.height)
    val estimated = (observed + 0.5) / (ambientFill * 255)
    info(f"analytic visibility $analytic%.4f, rendered $estimated%.4f")
    math.abs(estimated - analytic) should be < 0.01

  // ---- the sponge ----
  private val spongeCam = Cam(P3(-1.6, 1.4, 2.2), P3(0, -0.1, 0), 60f)
  private val floorY = -0.5f

  private def spongeScene(lightTravel: P3): Unit =
    lookAt(spongeCam)
    renderer.setLight(lightTravel.norm.v3, 1.0f)
    renderer.setShadows(true)
    addFloor(floorY)
    renderer.setTriangleMesh(TestUtilities.createUnitCubeMesh())
    val cube = 1f / 3f
    for xx <- -1 to 1; yy <- -1 to 1; zz <- -1 to 1 if math.abs(xx) + math.abs(yy) + math.abs(zz) > 1 do
      renderer.addTriangleMeshInstance(
        Array(cube, 0f, 0f, xx * cube, 0f, cube, 0f, yy * cube, 0f, 0f, cube, zz * cube),
        Material.plastic(Color(0.6f, 0.6f, 0.6f)), TextureIndex.NoTexture)

  /** Mean brightness of a solid cell of the +z face, minus that of the through-hole at its centre. */
  private def faceMinusHole(img: Array[Byte]): Double =
    val solidCell = region(spongeCam, square(P3(1.0 / 3, 0, 0.5), 0.08, 'z'))
    val hole = region(spongeCam, square(P3(0, 0, 0.5), 0.08, 'z'))
    mean(img, solidCell) - mean(img, hole)

  // The +z face is turned from both lights, so it gets the ambient fill alone.
  private val defaultLight = P3(1, -1, 1)
  private val backlight = P3(-0.5, -0.2, 0.81)

  "The holes of a level-1 sponge" should "be darker than the unlit face around them" in:
    spongeScene(defaultLight)
    val contrast = faceMinusHole(render(AmbientModel.Occlusion()))
    info(f"face - hole: $contrast%.1f levels")
    contrast should be > 10.0

  they should "be visible when the sponge is backlit" in:
    spongeScene(backlight)
    val contrast = faceMinusHole(render(AmbientModel.Occlusion()))
    info(f"face - hole: $contrast%.1f levels")
    contrast should be > 10.0

  "The constant model" should "keep the holes as bright as the face, as before" in:
    spongeScene(defaultLight)
    math.abs(faceMinusHole(render(AmbientModel.Constant))) should be < 3.0

  // ---- sampling ----
  "Rendering the same frame twice" should "give the same image" in:
    slabScene(opaqueSlab)
    render(AmbientModel.Occlusion()) shouldBe render(AmbientModel.Occlusion())

  "Accumulated frames" should "average independent samples, so the noise falls" in:
    slabScene(opaqueSlab)
    val patch = patchAround(slabCam, underSlab)
    val model = AmbientModel.Occlusion(samples = 2)
    val single = stddev(render(model), patch)
    val accumulated = stddev(render(model, accumulate = 16), patch)
    info(f"std-dev in the patch: one frame $single%.2f, 16 frames $accumulated%.2f")
    accumulated should be < single * 0.7

  // ---- settings ----
  "Ambient settings out of range" should "be rejected naming the value and the range" in:
    val tooFew = intercept[IllegalArgumentException](renderer.setAmbient(AmbientModel.Occlusion(samples = 0)))
    tooFew.getMessage should (include("ambient samples") and include("[1, 64]") and include("0"))
    val tooMany = intercept[IllegalArgumentException](renderer.setAmbient(AmbientModel.Occlusion(samples = 65)))
    tooMany.getMessage should (include("[1, 64]") and include("65"))
    val negative = intercept[IllegalArgumentException](renderer.setAmbient(AmbientModel.Occlusion(radius = -1f)))
    negative.getMessage should (include("ambient occlusion radius") and include("-1"))
    intercept[IllegalArgumentException](renderer.setAmbient(AmbientModel.Occlusion(radius = Float.NaN)))

  they should "leave the setting as it was" in:
    slabScene(opaqueSlab)
    val constant = render(AmbientModel.Constant)
    intercept[IllegalArgumentException](renderer.setAmbient(AmbientModel.Occlusion(samples = 65)))
    renderer.renderWithStats(imageSize).get().image shouldBe constant

  "The limits of the ambient settings" should "be accepted" in:
    noException should be thrownBy renderer.setAmbient(AmbientModel.Occlusion(samples = 1))
    noException should be thrownBy renderer.setAmbient(AmbientModel.Occlusion(samples = 64, radius = 0.01f))
    noException should be thrownBy renderer.setAmbient(AmbientModel.Constant)
