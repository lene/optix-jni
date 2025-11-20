package menger.optix
import menger.common.Vector

import org.scalatest.Ignore
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ShadowValidation.*
import TestUtilities.*
import ThresholdConstants.*


@Ignore
class ShadowDiagnosticTest extends AnyFlatSpec with Matchers with RendererFixture:

  def setupShadowScene(
    sphereAlpha: Float = 1.0f,
    lightDir: Vector[3] = Vector[3](0.5f, 0.5f, -0.5f)
  ): Unit =
    renderer.setSphere(Vector[3](0.0f, 0.0f, 0.0f), 0.5f)
    renderer.setSphereColor(0.75f, 0.75f, 0.75f, sphereAlpha)
    renderer.setIOR(1.5f)
    renderer.setScale(1.0f)
    renderer.setPlane(1, true, -0.6f)
    renderer.setPlaneSolidColor(true)  // Use solid color for shadow visibility
    renderer.setCamera(
      Vector[3](0.0f, 0.0f, 5.0f),
      Vector[3](0.0f, -0.3f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      45.0f
    )
    renderer.setLight(lightDir, 1.0f)

  "Shadow diagnostic" should "show actual brightness values for different alpha" in:
    val alphaValues = List(0.0f, 0.5f, 1.0f)
    val imageSize = STANDARD_IMAGE_SIZE

    println("\n=== SHADOW BRIGHTNESS DIAGNOSTIC ===")
    println("Saving images to: shadow_alpha_*.ppm")

    alphaValues.foreach { alpha =>
      setupShadowScene(sphereAlpha = alpha)
      renderer.setShadows(true)
      val image = renderer.render(imageSize).get

      // Save image for visual inspection
      val filename = f"shadow_alpha_${alpha}%.2f.ppm".replace(".", "_")
      savePPM(filename, image, imageSize.width, imageSize.height)
      println(f"Saved: $filename")

      // Test multiple regions to find where shadow actually is
      val regions = Map(
        "bottom-center" -> Region.bottomCenter(imageSize, fraction = 0.2),
        "top-center" -> Region.topCenter(imageSize, fraction = 0.2),
        "left-side" -> Region.leftSide(imageSize, fraction = 0.2),
        "right-side" -> Region.rightSide(imageSize, fraction = 0.2),
        "bottom-left" -> Region(0, 450, 200, 600),
        "bottom-right" -> Region(600, 450, 800, 600)
      )

      println(f"\nAlpha = $alpha%.2f:")
      regions.foreach { case (name, region) =>
        val brightness = regionBrightness(image, imageSize, region)
        println(f"  $name%-15s: brightness = $brightness%.2f")
      }

      // Find darkest region
      val darkest = detectDarkestRegion(image, imageSize, gridSize = DEFAULT_SHADOW_GRID)
      val darkestBright = regionBrightness(image, imageSize, darkest)
      println(f"  darkest-region  : brightness = $darkestBright%.2f at (${darkest.x0}, ${darkest.y0})")
    }

    println("\n=== END DIAGNOSTIC ===\n")

    // This test always passes - it's just for information
    true shouldBe true

  it should "compare shadows ON vs OFF" in:
    val imageSize = STANDARD_IMAGE_SIZE
    println("\n=== SHADOWS ON vs OFF ===")

    setupShadowScene(sphereAlpha = 1.0f)

    renderer.setShadows(false)
    val imageOff = renderer.render(imageSize).get

    renderer.setShadows(true)
    val imageOn = renderer.render(imageSize).get

    val regions = Map(
      "bottom-center" -> Region.bottomCenter(imageSize, fraction = 0.2),
      "top-center" -> Region.topCenter(imageSize, fraction = 0.2)
    )

    regions.foreach { case (name, region) =>
      val brightOff = regionBrightness(imageOff, imageSize, region)
      val brightOn = regionBrightness(imageOn, imageSize, region)
      val diff = brightOff - brightOn
      val pctChange = (diff / brightOff) * 100.0

      println(f"$name%s: OFF=$brightOff%.2f, ON=$brightOn%.2f, diff=$diff%.2f ($pctChange%.1f%% darker)")
    }

    println("\n=== END DIAGNOSTIC ===\n")

    true shouldBe true

  it should "show where shadow appears for different light directions" in:
    val imageSize = STANDARD_IMAGE_SIZE
    println("\n=== SHADOW POSITION BY LIGHT DIRECTION ===")

    val lightDirs = Map(
      "overhead" -> Vector[3](0.0f, 1.0f, 0.0f),
      "from-right" -> Vector[3](1.0f, 0.5f, 0.0f),
      "from-left" -> Vector[3](-1.0f, 0.5f, 0.0f),
      "default" -> Vector[3](0.5f, 0.5f, -0.5f)
    )

    lightDirs.foreach { case (name, dir) =>
      setupShadowScene(sphereAlpha = 1.0f, lightDir = dir)
      renderer.setShadows(true)
      val image = renderer.render(imageSize).get

      val darkest = detectDarkestRegion(image, imageSize, gridSize = LARGE_SHADOW_GRID)
      val centerX = (darkest.x0 + darkest.x1) / 2
      val centerY = (darkest.y0 + darkest.y1) / 2
      val brightness = regionBrightness(image, imageSize, darkest)

      println(f"Light $name%s: darkest at ($centerX%d, $centerY%d), brightness=$brightness%.2f")
    }

    println("\n=== END DIAGNOSTIC ===\n")

    true shouldBe true
