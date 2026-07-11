package io.github.lene.optix.caustics

import io.github.lene.optix.RendererFixture
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Light
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Regression test for per-instance photon emission (F-CAUSTICS-MULTITARGET, 0.1.15).
  *
  * The bug: with several separated refractive objects the old caustics pipeline aimed all photons
  * at a single *merged* bounding target sitting in the empty gap between the objects, so most
  * photons flew through the middle and never hit any object. The fix aims photons at one target
  * *per* refractive instance.
  *
  * The discriminating observable is the photon-sphere hit rate (refraction events), read straight
  * from `CausticsStats`. It measures exactly what the fix changes — whether photons reach the
  * separated objects — and is immune to the confound that a fixed photon budget split across N
  * targets leaves total deposited energy roughly constant (each caustic just gets dimmer). Adding a
  * second, well-separated sphere must NOT collapse the refraction rate.
  *
  * Checked for both light types — per-instance targeting lives in both `emitPointPhoton` and
  * `emitDirectionalPhoton`:
  *   Point, old merged: two-sphere ≈ 5.5k vs single ≈ 72k → collapses. New: ≈ 220k ≈ 219k.
  *   Directional, old merged: two-sphere photons spread over the merged bounding disk mostly miss
  *   the separated spheres. New: each photon is aimed at one sphere's own disk.
  */
class MultiObjectCausticsSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(200, 150)
  private val sphereRadius = 0.5f
  private val sphereSeparation = 1.5f
  private val glassColor: Color = Color(0.95f, 0.95f, 1.0f, 0.05f)
  private val photonsPerIter = 60000
  private val causticIterations = 2

  // Fraction of the single-sphere refraction rate the two-sphere scene must retain. Per-instance
  // emission keeps ~all of it (both spheres targeted); the old merged target keeps < 10%.
  private val retainFraction = 0.5

  // 4x3 row-major transform: uniform scale r, translate (cx, cy, cz).
  private def sphereTransform(cx: Float, cy: Float, cz: Float, r: Float): Array[Float] =
    Array(r, 0f, 0f, cx, 0f, r, 0f, cy, 0f, 0f, r, cz)

  private val leftSphere  = sphereTransform(-sphereSeparation, 0f, 0f, sphereRadius)
  private val rightSphere = sphereTransform(sphereSeparation, 0f, 0f, sphereRadius)

  private val pointLight: Light = Light.Point(Vector[3](0.0f, 4.0f, 0.0f), intensity = 1.0f)
  private val directionalLight: Light = Light.Directional(Vector[3](0.0f, -1.0f, 0.0f))

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

  /** Photon refraction events for a caustics render of the given sphere instances. */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def refractionEvents(instances: Array[Array[Float]], light: Light): Long =
    renderer.clearAllInstances()
    instances.foreach(t => renderer.addSphereInstance(t, glassColor, Const.iorGlass))
    setupSceneBase(light)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    renderer.renderWithStats(imageSize).get
    val stats = renderer.getCausticsStats
    if stats == null then fail("caustics produced no stats") // scalafix:ok DisableSyntax.null
    stats.refractionEvents

  private def assertHitRateRetained(light: Light): Unit =
    val singleRefractions = refractionEvents(Array(leftSphere), light)
    val twoRefractions = refractionEvents(Array(leftSphere, rightSphere), light)

    withClue(s"Single-sphere refraction events = $singleRefractions; a caustic must actually form. ") {
      singleRefractions should be > 0L
    }

    withClue(
      s"Two-sphere refraction events = $twoRefractions must be at least ${retainFraction}× the " +
      s"single-sphere count = $singleRefractions. Old merged-target emission aims photons at the " +
      "empty centre gap, so two separated spheres are barely hit; per-instance emission targets each."
    ) {
      twoRefractions.toDouble should be > (singleRefractions.toDouble * retainFraction)
    }

  behavior of "Multi-object caustics (per-instance photon emission)"

  it should "keep the photon-sphere hit rate when a second separated sphere is added (point light)" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    assertHitRateRetained(pointLight)

  it should "keep the photon-sphere hit rate when a second separated sphere is added (directional)" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    assertHitRateRetained(directionalLight)

end MultiObjectCausticsSuite
