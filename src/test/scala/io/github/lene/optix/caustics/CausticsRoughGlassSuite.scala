package io.github.lene.optix.caustics

import io.github.lene.optix.RendererFixture
import io.github.lene.optix.ImageValidation
import io.github.lene.optix.CausticsStats
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
  * the rough caustic must spread its energy over a wider area — measured as a LOWER standard
  * deviation of caustic-ROI pixel brightness than the smooth case (spread energy = flatter
  * distribution), not a raw pixel diff (CausticsWallReceiverSuite's own comment on ~10%
  * cross-scene composition noise applies here too; stddev of the SAME scene's own pixels sidesteps
  * that entirely, since both renders share identical geometry/camera/light).
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
  private def causticRoiStddev(material: Material): Double =
    setupScene(material)
    renderer.enableCaustics(photonsPerIter, causticIterations)
    val result = renderer.renderWithStats(imageSize).get
    // Floor spans the lower half of the frame at this camera framing (CausticsCoverageSuite's
    // proven-visible setup); restrict the stddev to that band so background/sphere pixels
    // (identical between the two renders regardless of roughness) don't dilute the signal.
    val roiPixels = for
      y <- imageSize.height / 2 until imageSize.height
      x <- 0 until imageSize.width
    yield ImageValidation.getRGBAt(result.image, imageSize, x, y)
    val brightness = roiPixels.map(p => (p.r.toDouble + p.g.toDouble + p.b.toDouble) / 3.0)
    val mean = brightness.sum / brightness.length
    math.sqrt(brightness.map(b => (b - mean) * (b - mean)).sum / brightness.length)

  behavior of "GGX-VNDF rough refraction"

  it should "spread a rough-glass caustic wider (lower ROI stddev) than a smooth-glass caustic" in:
    if runningUnderSanitizer then cancel("Skipped under compute-sanitizer (too slow)")
    val smoothGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass, roughness = 0.0f)
    val roughGlass = Material(Color(0.95f, 0.95f, 1.0f, 0.5f), ior = Const.iorGlass, roughness = 0.4f)
    val smoothStddev = causticRoiStddev(smoothGlass)
    val roughStddev = causticRoiStddev(roughGlass)
    withClue(s"caustic ROI brightness stddev: smooth=$smoothStddev rough=$roughStddev; a rough " +
      "(frosted) glass caustic must spread its energy over a wider area than a smooth one, " +
      "measured as a LOWER stddev (flatter brightness distribution). ") {
      roughStddev should be < smoothStddev
    }

end CausticsRoughGlassSuite
