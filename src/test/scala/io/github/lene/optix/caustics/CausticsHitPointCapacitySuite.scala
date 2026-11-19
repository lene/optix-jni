package io.github.lene.optix.caustics

import io.github.lene.optix.HitPointStats
import io.github.lene.optix.RendererFixture
import io.github.lene.optix.Slow
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Light
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The caustics hit-point buffer follows the render resolution (lene/optix-jni#129).
  *
  * The hit-point pass launches one thread per pixel and each thread takes at most one slot, so the
  * buffer holds width x height hit points. It used to hold a fixed 2,000,000, fewer than the
  * 2,073,600 pixels of a 1080p frame: when every pixel hit a diffuse surface, the last 73,600
  * got no caustics and the renderer logged an error.
  *
  * The scene looks straight down on a floor plane, so every camera ray seeds exactly one hit
  * point; `collected` must equal the pixel count and `dropped` must be zero.
  */
class CausticsHitPointCapacitySuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val fullHd: ImageSize = ImageSize(1920, 1080)
  private val small: ImageSize = ImageSize(64, 48)
  private val glassColor: Color = Color(0.95f, 0.95f, 1.0f, 0.05f)

  // The hit-point pass does not depend on the photon budget: keep Phase 2 as short as it gets.
  private val photonsPerIter = 1000
  private val causticIterations = 1

  private def pixels(size: ImageSize): Long = size.width.toLong * size.height.toLong

  private def setUpFloorSeenFromAbove(): Unit =
    renderer.clearAllInstances()
    renderer.addSphereInstance(sphereTransform(0f, 0f, 0f, 0.5f), glassColor, Const.iorGlass)
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, Const.defaultFloorPlaneY)
    renderer.setCamera(
      Vector[3](0.0f, 5.0f, 0.0f),
      Vector[3](0.0f, 0.0f, 0.0f),
      Vector[3](0.0f, 0.0f, 1.0f),
      45.0f
    )
    renderer.setLights(Array(Light.Point(Vector[3](0.0f, 4.0f, 0.0f), intensity = 1.0f)))
    renderer.enableCaustics(photonsPerIter, causticIterations)

  private def renderStats(size: ImageSize): HitPointStats =
    withClue(s"caustics render at ${size.width}x${size.height} returned no frame: ") {
      renderer.renderWithStats(size).isPresent should be(true)
    }
    withClue("hit-point stats of a caustics render: ") {
      renderer.getHitPointStats should not be empty
    }
    renderer.getHitPointStats.getOrElse(HitPointStats(-1L, -1L))

  behavior of "Caustics hit-point capacity (#129)"

  it should "give every pixel of a full-HD frame its hit point" taggedAs (Slow) in {
    setUpFloorSeenFromAbove()
    val stats = renderStats(fullHd)
    withClue("hit points that found the buffer full (#129: 2,000,000 < 2,073,600 pixels): ") {
      stats.dropped should be(0L)
    }
    stats.collected should be(pixels(fullHd))
  }

  it should "follow the resolution when it shrinks and grows between renders" in {
    setUpFloorSeenFromAbove()
    val sizes = Seq(small, ImageSize(200, 150), small)
    sizes.foreach { size =>
      val stats = renderStats(size)
      withClue(s"at ${size.width}x${size.height}: ") {
        stats.collected should be(pixels(size))
        stats.dropped should be(0L)
      }
    }
  }

end CausticsHitPointCapacitySuite
