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
  * second, well-separated sphere must NOT collapse the refraction rate:
  *
  *   Old (merged target): two-sphere refractions ≈ 5.5k vs single-sphere ≈ 72k (photons aimed at
  *   the gap miss both spheres) → FAILS.
  *   New (per-instance): two-sphere refractions ≈ 220k ≈ single-sphere 219k → PASSES.
  *
  * Uses a POINT light: per-instance emission lives in `emitPointPhoton`; the directional emitter
  * still aims at the single merged target, so a directional light would exercise the unchanged path
  * and detect nothing.
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

  private def setupSceneBase(): Unit =
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, Const.defaultFloorPlaneY)
    renderer.setCamera(
      Vector[3](0.0f, 3.0f, 3.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      45.0f
    )
    // Point light above the spheres: exercises the per-instance emitPointPhoton path.
    renderer.setLights(Array(Light.Point(Vector[3](0.0f, 4.0f, 0.0f), intensity = 1.0f)))

  /** Photon refraction events for a caustics render of the given sphere instances. */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def refractionEvents(instances: Array[Array[Float]]): Long =
    renderer.clearAllInstances()
    instances.foreach(t => renderer.addSphereInstance(t, glassColor, Const.iorGlass))
    setupSceneBase()
    renderer.enableCaustics(photonsPerIter, causticIterations)
    renderer.renderWithStats(imageSize).get
    val stats = renderer.getCausticsStats
    if stats == null then fail("caustics produced no stats") // scalafix:ok DisableSyntax.null
    stats.refractionEvents

  behavior of "Multi-object caustics (per-instance photon emission)"

  it should "keep the photon-sphere hit rate when a second separated sphere is added" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")

    val singleRefractions = refractionEvents(Array(leftSphere))
    val twoRefractions = refractionEvents(Array(leftSphere, rightSphere))

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

end MultiObjectCausticsSuite
