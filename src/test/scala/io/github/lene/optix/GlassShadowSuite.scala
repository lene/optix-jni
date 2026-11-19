package io.github.lene.optix

import menger.common.Color
import menger.common.ImageSize
import menger.common.Light
import menger.common.Material
import menger.common.Vector
import menger.common.x
import menger.common.y
import menger.common.z
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The shadow of glass without caustics (optix-jni#132).
  *
  * A shadow ray is a straight line, but glass bends the light it crosses, so a glass object's
  * shadow stays darker than the Fresnel and absorption losses along that line say: the share
  * the glass bends out of the shadow lands elsewhere, where only photons could put it. Without
  * caustics the shadow ray to a light is therefore charged `GlassShadowConstants::RETENTION`
  * of what it would let through. pbrt-v4 lets a glass sponge's shadow return 0.39 of the light
  * an opaque one blocks (menger's `scripts/caustics-validation`); a straight line let through 0.97
  * (plain shadows) or 0.65 (transparent shadows).
  *
  * T = (glass - opaque) / (bare - opaque) is the fraction of the blocked light the glass lets
  * through, measured on the floor where the opaque sphere's shadow is deepest (at least 90% of
  * its maximum): the camera sees no sphere pixel, and the floor is linear in the light.
  */
class GlassShadowSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val imageSize: ImageSize = ImageSize(160, 120)
  private val floorY = -2.5f
  // Light at 45 degrees along +x: the unit sphere's shadow falls at x about 2.5 on the floor.
  private val lightDirection = Vector[3](1f, -1f, 0f)
  // The camera looks at the shadow from beyond the sphere: its rays to the floor pass y = 0 at
  // x 3.8 or more, clear of the sphere, so every pixel shows the floor.
  private val cameraEye = Vector[3](6f, 4f, 0f)
  private val cameraTarget = Vector[3](2.5f, floorY, 0f)
  private val cameraFov = 30.0f

  // The unit sphere's object frame onto [0,1]^3 (as menger sets it): 0.5 * world + 0.5.
  private val unitSphereFrame = Array(0.5f, 0f, 0f, 0.5f, 0f, 0.5f, 0f, 0.5f, 0f, 0f, 0.5f, 0.5f)

  private val opaque = Material(Color(0f, 0f, 0f, 1f), ior = 1.0f)
  private val glass = Material.Glass
  // A tinted film, not refractive (IOR 1): its shadow is its opacity, glass or not.
  private val halfOpaqueFilm = Material(Color(0f, 0f, 0f, 0.5f), ior = 1.0f)

  /** A sphere of `radius`, or a cube of edge 2 * radius (a triangle mesh), at `position`. */
  private def addOccluder(position: Vector[3], radius: Float, material: Material, mesh: Boolean): Unit =
    val transform =
      Array(radius, 0f, 0f, position.x, 0f, radius, 0f, position.y, 0f, 0f, radius, position.z)
    val id =
      if mesh then renderer.addTriangleMeshInstance(transform, material)
      else renderer.addSphereInstance(transform, material)
    renderer.setObjectFrame(id, unitSphereFrame)

  /** The floor's (r, g, b) per pixel with the given occluders, shadows on, caustics off. */
  private def renderRgb(occluders: Seq[(Vector[3], Float, Material)], transparentShadows: Boolean,
                     mesh: Boolean = false): Array[(Double, Double, Double)] =
    renderer.clearAllInstances()
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, floorY)
    if mesh then renderer.setTriangleMesh(TestUtilities.createUnitCubeMesh())
    occluders.foreach { case (position, radius, material) =>
      addOccluder(position, radius, material, mesh)
    }
    renderer.setLights(Array(Light.Directional(lightDirection, intensity = 1.0f)))
    renderer.setCamera(cameraEye, cameraTarget, Vector[3](0f, 1f, 0f), cameraFov)
    renderer.setShadows(true)
    renderer.setTransparentShadows(transparentShadows)
    renderer.setAmbient(AmbientModel.Constant)
    renderer.disableCaustics()
    val image = renderer.renderWithStats(imageSize).get.image
    Array.tabulate(imageSize.width * imageSize.height): i =>
      val rgb = ImageValidation.getRGB(image, i)
      (rgb.r.toDouble, rgb.g.toDouble, rgb.b.toDouble)

  private def render(occluders: Seq[(Vector[3], Float, Material)], transparentShadows: Boolean,
                     mesh: Boolean = false): Array[Double] =
    renderRgb(occluders, transparentShadows, mesh).map((r, g, b) => (r + g + b) / 3)

  private def median(values: Seq[Double]): Double = values.sorted.apply(values.length / 2)

  private val origin = Vector[3](0f, 0f, 0f)

  /** Median T in the core of the opaque unit sphere's (or cube's) shadow, for `material` in its place. */
  private def transmission(material: Material, transparentShadows: Boolean,
                           mesh: Boolean = false): Double =
    val bare = render(Seq.empty, transparentShadows)
    val opaqueSphere = render(Seq((origin, 1f, opaque)), transparentShadows, mesh)
    val sphere = render(Seq((origin, 1f, material)), transparentShadows, mesh)
    val depth = bare.indices.map(i => bare(i) - opaqueSphere(i))
    val deepest = depth.max
    withClue(s"the opaque sphere casts no shadow (deepest $deepest); the scene is wrong. ") {
      deepest should be > 20.0
    }
    median(bare.indices.filter(depth(_) >= 0.9 * deepest)
      .map(i => (sphere(i) - opaqueSphere(i)) / depth(i)))

  behavior of "The shadow of glass without caustics"

  it should "let through neither all the light nor none, with plain shadows" in:
    val t = transmission(glass, transparentShadows = false)
    info(f"median T of a glass sphere, plain shadows: $t%.3f")
    withClue(s"median T = $t; a straight shadow ray let 0.97 through, an opaque shadow 0. ") {
      t should (be > 0.3 and be < 0.8)
    }

  it should "be the same with transparent shadows on" in:
    val plain = transmission(glass, transparentShadows = false)
    val transparent = transmission(glass, transparentShadows = true)
    info(f"median T of a glass sphere: plain $plain%.3f, transparent shadows $transparent%.3f")
    transparent shouldBe plain +- 0.03

  it should "cast the same shadow from a triangle mesh as from a sphere primitive" in:
    for transparentShadows <- Seq(false, true) do
      val t = transmission(glass, transparentShadows, mesh = true)
      info(f"median T of a glass cube, transparent shadows $transparentShadows: $t%.3f")
      t should (be > 0.3 and be < 0.8)

  it should "be coloured only with transparent shadows" in:
    // Red glass: absorbs green and blue, passes red. The plain shadow is the same in every channel.
    val redGlass = Material(Color(1f, 0.1f, 0.1f, 0.1f), ior = 1.5f)
    def redOverGreen(transparentShadows: Boolean): Double =
      val bare = renderRgb(Seq.empty, transparentShadows)
      val opaqueSphere = renderRgb(Seq((origin, 1f, opaque)), transparentShadows)
      val sphere = renderRgb(Seq((origin, 1f, redGlass)), transparentShadows)
      val deepest = bare.indices.map(i => bare(i)._2 - opaqueSphere(i)._2).max
      val core = bare.indices.filter(i => bare(i)._2 - opaqueSphere(i)._2 >= 0.9 * deepest)
      def passed(channel: ((Double, Double, Double)) => Double): Double =
        median(core.map(i => (channel(sphere(i)) - channel(opaqueSphere(i))) /
          (channel(bare(i)) - channel(opaqueSphere(i)))))
      passed(_._1) / passed(_._2)
    val plain = redOverGreen(transparentShadows = false)
    val coloured = redOverGreen(transparentShadows = true)
    info(f"red / green of the light red glass lets through: plain $plain%.2f, transparent shadows $coloured%.2f")
    plain shouldBe 1.0 +- 0.05
    coloured should be > 1.3

  it should "keep the opacity of a film that does not refract" in:
    // Retention is for light glass bends; a tinted film at IOR 1 bends none.
    for transparentShadows <- Seq(false, true) do
      val t = transmission(halfOpaqueFilm, transparentShadows)
      info(f"median T of a half-opaque film, transparent shadows $transparentShadows: $t%.3f")
      t shouldBe 0.5 +- 0.05

  it should "let an opaque occluder behind the glass shadow all the light" in:
    // The any-hit program passes the glass on; the opaque sphere in the light's path must still
    // be found behind it (closest hit), not skipped with the glass.
    val opaqueBehind = Seq((Vector[3](1.5f, -1.5f, 0f), 1f, opaque))
    val bare = render(Seq.empty, transparentShadows = false)
    val opaqueAlone = render(opaqueBehind, transparentShadows = false)
    val behindGlass = render((origin, 1f, glass) +: opaqueBehind, transparentShadows = false)
    val depth = bare.indices.map(i => bare(i) - opaqueAlone(i))
    val deepest = depth.max
    val core = bare.indices.filter(depth(_) >= 0.9 * deepest)
    val ratio = median(core.map(i => (behindGlass(i) - opaqueAlone(i)) / depth(i)))
    info(f"median T of an opaque sphere behind a glass one: $ratio%.3f")
    ratio should be < 0.1

end GlassShadowSuite
