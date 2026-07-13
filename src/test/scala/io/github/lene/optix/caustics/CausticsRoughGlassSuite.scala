package io.github.lene.optix.caustics

import io.github.lene.optix.RendererFixture
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Material
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Phase 3: GGX-VNDF rough refraction. roughness is read nowhere in the caustics path before
  * this phase, so a rough-glass caustic and a smooth-glass caustic are pixel-for-pixel identical
  * pre-implementation (same seeds, same physics, roughness silently ignored). Post-implementation,
  * the rough caustic must spread its energy over a wider area — measured as more hit points
  * receiving nonzero photon flux than the smooth case (same total photon energy spread over more
  * hit points), using the deterministic hitPointsWithFlux counter from the native caustics
  * pipeline (counts how many scene hit points accumulate nonzero photon flux after all PPM
  * iterations).
  */
class CausticsRoughGlassSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(200, 150)
  private val sphereRadius = 1.0f
  private val photonsPerIter = 100000
  private val causticIterations = 4
  private val lightIntensity = 500.0f

  private val sphereTransform: Array[Float] =
    Array(sphereRadius, 0f, 0f, 0f, 0f, sphereRadius, 0f, 0f, 0f, 0f, sphereRadius, 0f)

  private def setupScene(material: Material): Unit =
    renderer.clearAllInstances()
    renderer.addSphereInstance(sphereTransform, material)
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, Const.defaultFloorPlaneY)
    renderer.setCamera(
      Vector[3](0.0f, 4.0f, 8.0f),
      Vector[3](0.0f, 0.0f, 0.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      45.0f
    )
    renderer.setLight(Vector[3](0.0f, -1.0f, 0.0f), lightIntensity)

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def hitPointsWithFlux(material: Material): Long =
    setupScene(material)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    renderer.renderWithStats(imageSize).get
    val stats = renderer.getCausticsStats
    if stats == null then fail("caustics produced no stats") // scalafix:ok DisableSyntax.null
    stats.hitPointsWithFlux

  behavior of "GGX-VNDF rough refraction"

  it should "spread a rough-glass caustic wider (more hit points w/ flux) than a smooth one" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val smoothGlass =
      Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass, roughness = 0.0f)
    val roughGlass =
      Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass, roughness = 0.4f)
    val smoothHits = hitPointsWithFlux(smoothGlass)
    val roughHits = hitPointsWithFlux(roughGlass)
    withClue(s"hitPointsWithFlux: smooth=$smoothHits rough=$roughHits; a rough (frosted) glass " +
      "caustic must spread its photon energy over a wider area than a smooth one, so the rough " +
      "caustic should touch more distinct hit points on the floor (more nonzero flux points). ") {
      roughHits should be > smoothHits
    }

  // Mirrors CausticsCoverageSuite's regression-guard pattern: energyConservationError is NOT a
  // physical energy-conservation bound (totalFluxDeposited is raw pre-normalization flux, see
  // CausticsCoverageSuite's comment) -- this locks the RATIO so future accounting drift is
  // caught, across a roughness sweep so GGX sampling doesn't introduce its own drift.
  private val MaxEnergyConservationErrorRatio: Double = 780.0

  it should "report a bounded, deterministic energy-conservation error across a roughness sweep" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    for roughness <- Seq(0.0f, 0.2f, 0.5f, 0.8f) do
      val glass = Material(
        Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass, roughness = roughness
      )
      setupScene(glass)
      renderer.enableCaustics(photonsPerIter, causticIterations)
      val statsA = { renderer.renderWithStats(imageSize).get; renderer.getCausticsStats }
      setupScene(glass)
      renderer.enableCaustics(photonsPerIter, causticIterations)
      val statsB = { renderer.renderWithStats(imageSize).get; renderer.getCausticsStats }
      withClue(
        s"roughness=$roughness energyConservationError A=${statsA.energyConservationError} " +
        s"B=${statsB.energyConservationError}; must be bounded and deterministic. "
      ) {
        statsA.totalFluxEmitted should be > 0.0
        statsA.energyConservationError shouldBe statsB.energyConservationError +- 1e-6
        statsA.energyConservationError should be < MaxEnergyConservationErrorRatio
      }

end CausticsRoughGlassSuite
