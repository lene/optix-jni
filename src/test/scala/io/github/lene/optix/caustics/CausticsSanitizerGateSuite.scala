package io.github.lene.optix.caustics

import io.github.lene.optix.RendererFixture
import io.github.lene.optix.Slow
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Light
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** CR-1 (Task 2.7) release gate + regression guard.
  *
  * `__raygen__hitpoints` runs one thread per camera pixel and `atomicAdd`s the shared hit-point
  * counter. A full-HD frame is 1920×1080 = 2,073,600 pixels, which exceeds `MAX_HIT_POINTS`
  * (2,000,000); with a floor plane filling the view every ray seeds a hit point, so the counter
  * overflows the cap. Before the CR-1 fix the count/scatter/radiance kernels launched at that
  * unclamped width and read past the 2M `hit_points` array — the July out-of-bounds corruption.
  *
  * This test drives exactly that overflow path, so it is the CR-1 regression guard, and it is
  * written to run UNDER compute-sanitizer (no `runningUnderSanitizer` cancel): a clean memcheck
  * run of this suite is the release gate. Tagged `Slow` — a 2M-pixel caustics render is heavy.
  */
class CausticsSanitizerGateSuite extends AnyFlatSpec with Matchers with RendererFixture:

  // 1920×1080 = 2,073,600 camera pixels > MAX_HIT_POINTS (2,000,000): forces the counter overflow.
  private val imageSize: ImageSize = ImageSize(1920, 1080)
  private val glassColor: Color = Color(0.95f, 0.95f, 1.0f, 0.05f)

  // Uniform-scale-r, translate-(cx,cy,cz) 4x3 row-major transform (as in MultiObjectCausticsSuite).
  private def sphereTransform(cx: Float, cy: Float, cz: Float, r: Float): Array[Float] =
    Array(r, 0f, 0f, cx, 0f, r, 0f, cy, 0f, 0f, r, cz)

  behavior of "Caustics hit-point counter overflow (CR-1)"

  it should "render full-HD caustics without out-of-bounds access when hit points exceed the cap" taggedAs (Slow) in {
    renderer.clearAllInstances()
    renderer.addSphereInstance(sphereTransform(0f, 0f, 0f, 0.5f), glassColor, Const.iorGlass)
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, Const.defaultFloorPlaneY)
    // Camera looks straight down at the floor plane so every camera ray strikes it — the whole
    // frame seeds hit points, guaranteeing num_hit_points ≈ 2.07M > MAX_HIT_POINTS.
    renderer.setCamera(
      Vector[3](0.0f, 5.0f, 0.0f),   // eye above the plane
      Vector[3](0.0f, 0.0f, 0.0f),   // look-at (straight down)
      Vector[3](0.0f, 0.0f, 1.0f),   // up (not parallel to the view direction)
      45.0f
    )
    renderer.setLights(Array(Light.Point(Vector[3](0.0f, 4.0f, 0.0f), intensity = 1.0f)))
    renderer.enableCaustics(60000, 2)

    val result = renderer.renderWithStats(imageSize)
    withClue("full-HD caustics render returned no frame (native failure): ") {
      result.isPresent should be(true)
    }
  }

end CausticsSanitizerGateSuite
