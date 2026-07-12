package io.github.lene.optix.caustics

import io.github.lene.optix.RendererFixture
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Material
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Phase 2 Task 1: caustics deposit onto a SECOND enabled plane (a back wall, axis != Y),
  * not only planes[0] (the floor). Uses CausticsStats.photonsDeposited (a deterministic native
  * counter) rather than pixel deltas — a pixel-delta comparison between two DIFFERENT scene
  * configurations (floor-only vs floor+wall) carries ~10% quantization/composition noise from
  * the wall's own visible shading alone, which swamped a tight threshold even under unmodified
  * (pre-Task-1) code. photonsDeposited is unaffected by that: pre-Task-1, deposit seeding and
  * targeting only ever reads planes[0], so adding a second enabled plane cannot change the
  * count at all (bit-identical) — a clean, non-noisy RED. Post-fix, the wall must receive its
  * own deposits, strictly increasing the count.
  */
class CausticsWallReceiverSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val runningUnderSanitizer: Boolean =
    sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  private val imageSize: ImageSize = ImageSize(200, 150)
  private val sphereRadius = 1.0f
  private val photonsPerIter = 100000
  private val causticIterations = 4
  private val lightIntensity = 500.0f

  private val sphereTransform: Array[Float] =
    Array(sphereRadius, 0f, 0f, 0f, 0f, sphereRadius, 0f, 0f, 0f, 0f, sphereRadius, 0f)

  // Sphere between camera and a back wall (Z axis, negative side) so a light angled toward
  // the wall casts a caustic onto it. Floor (planes[0]) is always enabled; the wall (planes[1])
  // is toggled by includeWall to isolate its contribution.
  private def setupScene(material: Material, includeWall: Boolean): Unit =
    renderer.clearAllInstances()
    renderer.addSphereInstance(sphereTransform, material)
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, Const.defaultFloorPlaneY)   // floor, planes[0]
    if includeWall then renderer.addPlane(2, positive = false, -4.0f) // back wall, planes[1]
    renderer.setCamera(
      Vector[3](0.0f, 2.0f, 8.0f),
      Vector[3](0.0f, 0.0f, -2.0f),
      Vector[3](0.0f, -1.0f, 0.0f),
      45.0f
    )
    renderer.setLight(Vector[3](0.3f, -0.3f, -1.0f), lightIntensity)

  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def photonsDeposited(material: Material, includeWall: Boolean): Long =
    setupScene(material, includeWall)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    renderer.renderWithStats(imageSize).get
    renderer.getCausticsStats.photonsDeposited

  behavior of "Multi-plane caustic deposit"

  it should "deposit more photons with the wall enabled than with the floor alone" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val clearGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass)
    val floorOnlyDeposits = photonsDeposited(clearGlass, includeWall = false)
    val floorPlusWallDeposits = photonsDeposited(clearGlass, includeWall = true)
    withClue(s"photonsDeposited floor-only=$floorOnlyDeposits, floor+wall=$floorPlusWallDeposits; " +
      "enabling a second plane (the wall) must increase the deposited-photon count beyond the " +
      "floor alone — proves the wall genuinely receives its own deposit, not just the floor. ") {
      floorPlusWallDeposits should be > floorOnlyDeposits
    }

end CausticsWallReceiverSuite
