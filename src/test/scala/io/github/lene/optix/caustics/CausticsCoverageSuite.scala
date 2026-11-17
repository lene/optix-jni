package io.github.lene.optix.caustics

import io.github.lene.optix.RendererFixture
import io.github.lene.optix.ImageValidation
import io.github.lene.optix.CausticsStats
import io.github.lene.optix.RenderResult
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Material
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Coverage net (Phase 1): characterization tests locking shipped caustic *deposit* behaviors.
  * These assert current behavior end-to-end (the physics calculations are already covered by
  * CausticsValidationSuite). A failure here means a real regression, not a test to relax.
  */
class CausticsCoverageSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(200, 150)
  private val sphereRadius = 1.0f
  private val photonsPerIter = 100000
  private val causticIterations = 4
  // A dim light makes the caustic quantize to 0 in 8-bit RGBA (on/off delta identically zero).
  // 500 is the proven-visible value from CausticsReferenceSuite.ReferenceScene.
  private val lightIntensity = 500.0f

  // NOT a physical energy-conservation bound. energyConservationError compares
  // totalFluxDeposited (raw, un-normalized: full photon flux summed into EVERY matched hit point
  // in the PPM density-estimate search, before the radiance kernel's pi*R^2*iterations division)
  // against totalFluxEmitted (once per photon) -- apples to oranges by construction, not a shader
  // bug. Observed ratio ~628x for the canonical scene. This ceiling is a REGRESSION GUARD on that
  // raw ratio (catches future accounting drift), not a claim that energy is conserved. Proper fix
  // (normalize total_flux_deposited at accumulation) tracked as menger Sprint 35 Task 35.12.
  private val MaxEnergyConservationErrorRatio: Double = 780.0

  // 4x3 row-major transform: uniform scale r, centered at origin.
  private val sphereTransform: Array[Float] =
    Array(sphereRadius, 0f, 0f, 0f, 0f, sphereRadius, 0f, 0f, 0f, 0f, sphereRadius, 0f)

  // Known-good VISIBLE-caustic framing, copied verbatim from CausticsReferenceSuite.ReferenceScene
  // (which asserts causticBrightness > 0.01 in the rendered image, proving renderWithStats
  // composites the caustic into the pixels): radius-1 glass sphere over the floor, directional light
  // straight down at intensity 500, camera set back and up looking at the sphere centre, up-vector
  // inverted for correct orientation. `setCamera`'s 2nd arg is a look-at POINT;
  // `setLight(direction, intensity)` is a DIRECTIONAL light. This exact framing is required — an
  // invented camera or a dim light leaves the caustic out of frame / below 8-bit quantization,
  // making the on/off delta identically zero.
  private def setupGlassScene(material: Material): Unit =
    renderer.clearAllInstances()
    renderer.addSphereInstance(sphereTransform, material)
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, Const.defaultFloorPlaneY)
    renderer.setCamera(
      Vector[3](0.0f, 4.0f, 8.0f),    // eye
      Vector[3](0.0f, 0.0f, 0.0f),    // look-at the sphere centre
      Vector[3](0.0f, -1.0f, 0.0f),   // up (inverted for correct orientation, per ReferenceScene)
      45.0f
    )
    renderer.setLight(Vector[3](0.0f, -1.0f, 0.0f), lightIntensity)  // directional down

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def renderGlassCaustic(material: Material): (CausticsStats, RenderResult) =
    setupGlassScene(material)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    val result = renderer.renderWithStats(imageSize).get
    val stats = renderer.getCausticsStats
    if stats == null then fail("caustics produced no stats") // scalafix:ok DisableSyntax.null
    (stats, result)

  private def wholeImageChannelMeans(result: RenderResult): (Double, Double, Double) =
    val pixels = for
      y <- 0 until imageSize.height
      x <- 0 until imageSize.width
    yield ImageValidation.getRGBAt(result.image, imageSize, x, y)
    (
      pixels.map(_.r).sum.toDouble / pixels.length,
      pixels.map(_.g).sum.toDouble / pixels.length,
      pixels.map(_.b).sum.toDouble / pixels.length
    )

  /** Per-channel caustic contribution: whole-image mean(caustics on) − mean(caustics off) for the
    * same scene. Everything but the photon deposit is identical between the two renders, so the
    * delta isolates the caustic itself — no dependence on where it projects on screen. */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def causticChannelDelta(material: Material): (Double, Double, Double) =
    setupGlassScene(material)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    val (onR, onG, onB) = wholeImageChannelMeans(renderer.renderWithStats(imageSize).get)
    setupGlassScene(material)
    renderer.disableCaustics()
    val (offR, offG, offB) = wholeImageChannelMeans(renderer.renderWithStats(imageSize).get)
    (onR - offR, onG - offG, onB - offB)

  /** How coloured the light the caustic adds is: over the pixels the caustic brightens visibly
    * and that no channel clips, the channel spread of the added light (on minus off) over its
    * mean, summed over the pixels. A relative measure of single pixels: the means of each channel
    * over the whole image, which this suite's other tests use, say how bright the caustic is as
    * much as how coloured (a caustic of twice the light doubles the gap between channel means
    * whatever its colour), and at this suite's light 500, 350 times past white, most pixels clip
    * (menger#150: the caustic got 2.2 times brighter and the old comparison of channel means
    * flipped, though the dispersive caustic was no less coloured). The light is dimmed to 3 so a
    * caustic can be seen without saturating. */
  private def causticRelativeChroma(material: Material): Double =
    val dimLight = 3.0f
    def pixels(result: RenderResult) =
      for
        y <- 0 until imageSize.height
        x <- 0 until imageSize.width
      yield ImageValidation.getRGBAt(result.image, imageSize, x, y)
    def render(withCaustics: Boolean) =
      setupGlassScene(material)
      renderer.setLight(Vector[3](0.0f, -1.0f, 0.0f), dimLight)
      if withCaustics then renderer.enableCaustics(photonsPerIter, causticIterations)
      else renderer.disableCaustics()
      pixels(renderer.renderWithStats(imageSize).get)
    val on = render(withCaustics = true)
    val off = render(withCaustics = false)
    val added = on.zip(off).collect:
      case (a, b) if a.max < 250 && (a.r + a.g + a.b) - (b.r + b.g + b.b) >= 6 =>
        (a.r - b.r, a.g - b.g, a.b - b.b)
    withClue("no pixel is brightened visibly by the caustic without clipping; the scene is wrong. ") {
      added.length should be > 50
    }
    added.map((r, g, b) => (math.max(r, math.max(g, b)) - math.min(r, math.min(g, b))).toDouble).sum /
      added.map((r, g, b) => (r + g + b) / 3.0).sum

  behavior of "Caustics deposit coverage net"

  it should "tint the deposited caustic toward a red glass's colour" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val redGlass = Material(Color(1.0f, 0.2f, 0.2f, 0.5f), ior = Const.iorGlass)
    val (dr, dg, db) = causticChannelDelta(redGlass)
    withClue(s"caustic contribution (on−off) R=$dr G=$dg B=$db; a red glass's caustic must add " +
      "more red than green/blue, and must actually be present (R delta > 0). ") {
      dr should be > 0.0
      dr should be > dg
      dr should be > db
    }

  it should "deposit some reflected-photon flux for a glass caustic (reflective path active)" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val clearGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass)
    val (stats, _) = renderGlassCaustic(clearGlass)
    withClue(s"totalFluxReflected=${stats.totalFluxReflected}; the P2 Fresnel-reflect path must " +
      "carry non-zero flux (photons Russian-roulette between reflect and refract). ") {
      stats.totalFluxReflected should be > 0.0
    }

  it should "spread the caustic chromatically when dispersion is on (PPM spectral deposit)" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val baseColor = Color(0.95f, 0.95f, 1.0f, 0.5f)
    val plain = Material(baseColor, ior = Const.iorGlass, dispersion = 0.0f)
    val dispersive = Material(baseColor, ior = Const.iorGlass, dispersion = 1.0f)

    val plainChroma = causticRelativeChroma(plain)
    val dispersiveChroma = causticRelativeChroma(dispersive)

    info(f"relative chroma of the added light: plain $plainChroma%.3f, dispersive $dispersiveChroma%.3f")
    withClue(s"relative chroma of the added light: dispersion-off=$plainChroma dispersion-on=$dispersiveChroma; " +
      "the dispersive photon caustic must be more chromatic than the achromatic one. ") {
      dispersiveChroma should be > plainChroma
    }

  it should "report a bounded, deterministic caustic energy-conservation error" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val clearGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass)
    val (statsA, _) = renderGlassCaustic(clearGlass)
    val (statsB, _) = renderGlassCaustic(clearGlass)
    info(s"energyConservationError run A=${statsA.energyConservationError} " +
      s"B=${statsB.energyConservationError}")
    statsA.totalFluxEmitted should be > 0.0
    statsA.energyConservationError shouldBe statsB.energyConservationError +- 1e-6
    statsA.energyConservationError should be < MaxEnergyConservationErrorRatio

end CausticsCoverageSuite
