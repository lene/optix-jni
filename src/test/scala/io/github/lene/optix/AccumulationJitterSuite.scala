package io.github.lene.optix

import java.security.MessageDigest
import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** optix-jni#116: accumulating frames must also smooth silhouettes. Before the fix every
  * accumulated frame sent the primary ray through the pixel centre, so the frames agreed at
  * every edge pixel and accumulation left the stair-steps untouched.
  *
  * The scene is a white sphere before a black background, lit so strongly from the camera's side
  * that every pixel the sphere covers saturates. A pixel is then either background or sphere,
  * and a row through the sphere's silhouette shows only those two brightness values, unless
  * accumulated frames average partial coverage into the edge pixels.
  */
class AccumulationJitterSuite extends AnyFlatSpec with Matchers with RendererFixture:

  private val imageSize = ImageSize(200, 200)
  private val accumulatedFrames = 16
  /** The image row through the sphere's centre, crossing the silhouette on both sides. */
  private val silhouetteRow = 100
  /** Strong enough that the sphere saturates everywhere but on a sub-pixel rim. */
  private val saturatingLightIntensity = 100.0f
  /** Brightness of a pixel not covered by the sphere. */
  private val backgroundBrightness = 0
  /** Brightness values an aliased edge may show: background and saturated sphere. */
  private val aliasedDistinctValues = 2

  private def render(frames: Int): Array[Byte] =
    renderer.clearPlanes()
    renderer.setBackgroundColor(0.0f, 0.0f, 0.0f)
    renderer.setSphereColor(Color(1.0f, 1.0f, 1.0f))
    renderer.setLight(Vector[3](0.0f, 0.0f, -1.0f), saturatingLightIntensity)
    renderer.setAntialiasing(enabled = false, maxDepth = 2, threshold = 0.1f)
    renderer.setAccumulationFrames(frames)
    renderImage(imageSize)

  private def distinctBrightnessOnRow(image: Array[Byte]): Set[Int] =
    (0 until imageSize.width)
      .map(x => ImageValidation.getRGBAt(image, imageSize, x, silhouetteRow).brightness)
      .toSet

  private def digest(image: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(image).map(b => f"${b & 0xFF}%02x").mkString.take(16)

  behavior of "Accumulation of frames with antialiasing off"

  it should "show an aliased silhouette with one frame" in:
    val values = distinctBrightnessOnRow(render(1))
    info(s"1 frame: ${values.size} distinct brightness values on row $silhouetteRow: ${values.toSeq.sorted}")
    values should contain(backgroundBrightness)
    values.size should be <= aliasedDistinctValues

  it should "smooth the silhouette with 16 frames" in:
    val values = distinctBrightnessOnRow(render(accumulatedFrames))
    info(s"$accumulatedFrames frames: ${values.size} distinct brightness values on row $silhouetteRow: ${values.toSeq.sorted}")
    values.size should be > aliasedDistinctValues

  it should "render the same image twice with one frame" in:
    val first = render(1)
    info(s"1 frame image digest: ${digest(first)}")
    render(1) shouldBe first
