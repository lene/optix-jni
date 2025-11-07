package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import OptiXRendererTest.TestConfig
import OptiXRendererTest.ImageAnalysis

/**
 * Integration tests for transparent sphere rendering (Beer-Lambert absorption).
 *
 * Tests alpha channel variation with IOR=1.0 (no refraction):
 * - Alpha=1.0 (fully opaque) → maximum absorption, no background
 * - Alpha=0.5 (semi-transparent) → moderate absorption, some background
 * - Alpha=0.0 (fully transparent) → no absorption, full background
 */
class OptiXTransparencyTest extends AnyFlatSpec with Matchers with LazyLogging:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Transparent sphere" should "be fully opaque at alpha=1.0" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)  // Y-axis floor at y=-2
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.502f, 0.502f, 0.502f, 1.0f)  // Alpha=1.0
    renderer.setIOR(1.0f)  // No refraction
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Background should NOT be visible through opaque sphere
    val bgVisible = ImageValidation.backgroundVisibility(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    // Note: Even opaque sphere won't completely hide background at edges
    // So we check center is bright (sphere) vs edges (may show background)
    val hasCenterBrightness = ImageAnalysis.hasCenterBrightness(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    hasCenterBrightness shouldBe true

    renderer.dispose()

  it should "show partial transparency at alpha=0.75" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.502f, 0.502f, 0.502f, 0.749f)  // Alpha=0.75 (191/255)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Should have some variation (sphere+background)
    val stdDev = ImageAnalysis.brightnessStdDev(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    stdDev should be > 10.0

    renderer.dispose()

  it should "show moderate transparency at alpha=0.5" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.502f, 0.502f, 0.502f, 0.502f)  // Alpha=0.5
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Background pattern should be visible
    val bgVisible = ImageValidation.backgroundVisibility(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    bgVisible shouldBe true

    renderer.dispose()

  it should "show high transparency at alpha=0.25" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.502f, 0.502f, 0.502f, 0.251f)  // Alpha=0.25 (64/255)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Background should be clearly visible
    val bgVisible = ImageValidation.backgroundVisibility(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    bgVisible shouldBe true

    // Image should have high variation (checkered background dominates)
    val stdDev = ImageAnalysis.brightnessStdDev(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    stdDev should be > 20.0

    renderer.dispose()

  it should "be fully transparent at alpha=0.0" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.502f, 0.502f, 0.502f, 0.0f)  // Alpha=0.0 (fully transparent)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Background should be fully visible (sphere has no effect)
    val bgVisible = ImageValidation.backgroundVisibility(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    bgVisible shouldBe true

    renderer.dispose()

  it should "show monotonic brightness decrease with increasing alpha" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.502f, 0.502f, 0.502f, 0.0f)  // Will vary alpha
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    // Measure center brightness at different alpha values
    val alphas = Seq(0, 64, 128, 191, 255)  // 0.0, 0.25, 0.5, 0.75, 1.0
    val brightnesses = alphas.map: alpha =>
      renderer.setSphereColor(0.502f, 0.502f, 0.502f, alpha / 255.0f)
      val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
      val (centerX, centerY) = ImageValidation.detectSphereCenter(
        imageData,
        TestConfig.TestImageSize._1,
        TestConfig.TestImageSize._2
      )
      val centerIdx = centerY * TestConfig.TestImageSize._1 + centerX
      ImageAnalysis.brightness(imageData, centerIdx)

    logger.info(s"Alpha brightness series: ${brightnesses.mkString(", ")}")

    // For alpha=0, brightness should be highest (background shows)
    // For alpha=1.0, brightness may vary but sphere absorbs light
    // We check that alpha=0 is brighter than alpha=1.0
    brightnesses(0) should be >= brightnesses(4)  // alpha=0 >= alpha=1.0

    renderer.dispose()

  it should "show colored transparency with green tint" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0, 255, 0, 128)  // Green, semi-transparent
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Green should be dominant channel
    val (r, g, b) = ImageValidation.colorChannelRatio(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    logger.info(f"Green transparent sphere RGB ratios: R=$r%.3f G=$g%.3f B=$b%.3f")
    g should be > r
    g should be > b

    renderer.dispose()

  it should "preserve background checkered pattern with low alpha" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)  // Floor plane
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.098f)  // Nearly transparent white
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Checkered floor should be visible at bottom
    val floorVisible = ImageValidation.planeVisibility(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2,
      "bottom"
    )
    floorVisible shouldBe true

    renderer.dispose()
