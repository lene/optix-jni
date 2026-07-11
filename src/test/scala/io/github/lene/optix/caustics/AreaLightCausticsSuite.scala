package io.github.lene.optix.caustics

import io.github.lene.optix.RendererFixture
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Light
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression test for soft caustics from area lights (F-CAUSTICS-AREA, 0.1.16).
  *
  * A point light emits every photon from a single origin, so the caustic focuses to a sharp,
  * high-peak spot. An area light of the *same* position, intensity and target emits each photon
  * from a random point on its disk; the spread of origins blurs the caustic into a penumbra. At
  * matched total flux the energy is conserved, so a wider footprint means a **lower peak**.
  *
  * The discriminating observable is `CausticsStats.maxCausticBrightness`: for an area light it must
  * be measurably below the point-light peak. Before `emitAreaPhoton` existed, an AREA light fell
  * through to the point-light emitter (single origin at `light.position`), producing an identically
  * sharp caustic — so this test fails until the disk-origin emission path is implemented.
  */
class AreaLightCausticsSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(200, 150)
  private val sphereRadius = 0.5f
  private val glassColor: Color = Color(0.95f, 0.95f, 1.0f, 0.05f)
  private val photonsPerIter = 60000
  private val causticIterations = 2

  private val lightPosition: Vector[3] = Vector[3](0.0f, 4.0f, 0.0f)
  private val lightIntensity = 1.0f
  private val areaLightRadius = 2.0f

  // The area-light peak must sit at most this fraction of the point-light peak: a real softening,
  // not just render noise. Renders are deterministically seeded (no stochastic jitter between the
  // two lights), so any consistent drop is signal; a radius-2 disk 4 units above a radius-0.5 sphere
  // clears this margin comfortably.
  private val softnessCeiling = 0.9

  // 4x3 row-major transform: uniform scale r, translate (cx, cy, cz).
  private def sphereTransform(cx: Float, cy: Float, cz: Float, r: Float): Array[Float] =
    Array(r, 0f, 0f, cx, 0f, r, 0f, cy, 0f, 0f, r, cz)

  private val sphere = sphereTransform(0f, 0f, 0f, sphereRadius)

  private val pointLight: Light = Light.Point(lightPosition, intensity = lightIntensity)
  private val areaLight: Light = Light.Area(
    lightPosition,
    Vector[3](0.0f, -1.0f, 0.0f), // disk faces down, toward the scene
    areaLightRadius,
    intensity = lightIntensity
  )

  private def setupSceneBase(light: Light): Unit =
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, Const.defaultFloorPlaneY)
    renderer.setCamera(
      Vector[3](0.0f, 3.0f, 3.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      45.0f
    )
    renderer.setLights(Array(light))

  /** Peak caustic brightness for a single-sphere caustics render under the given light. */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def peakCausticBrightness(light: Light): Float =
    renderer.clearAllInstances()
    renderer.addSphereInstance(sphere, glassColor, Const.iorGlass)
    setupSceneBase(light)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    renderer.renderWithStats(imageSize).get
    val stats = renderer.getCausticsStats
    if stats == null then fail("caustics produced no stats") // scalafix:ok DisableSyntax.null
    stats.maxCausticBrightness

  behavior of "Area-light caustics (soft-caustic photon emission)"

  it should "produce a softer (lower-peak) caustic than a point light at matched flux" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")

    val pointPeak = peakCausticBrightness(pointLight)
    val areaPeak = peakCausticBrightness(areaLight)

    withClue(s"Point-light caustic peak = $pointPeak; a caustic must actually form. ") {
      pointPeak should be > 0.0f
    }
    withClue(s"Area-light caustic peak = $areaPeak; a caustic must actually form. ") {
      areaPeak should be > 0.0f
    }
    withClue(
      s"Area-light peak = $areaPeak must be below ${softnessCeiling}× the point-light peak = " +
      s"$pointPeak. An area light spreads photon origins over its disk, softening the caustic into " +
      "a lower-peak penumbra; a single-origin emission would keep the point-light's sharp peak."
    ) {
      areaPeak.toDouble should be < (pointPeak.toDouble * softnessCeiling)
    }

end AreaLightCausticsSuite
