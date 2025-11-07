package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import OptiXRendererTest.TestConfig
import OptiXRendererTest.ImageAnalysis

/**
 * Integration tests for refractive sphere rendering (Snell's law + Fresnel).
 *
 * Tests IOR (index of refraction) variation with fixed alpha=0.5:
 * - IOR=1.0 → no refraction (baseline)
 * - IOR=1.33 → water
 * - IOR=1.5 → glass
 * - IOR=2.42 → diamond
 *
 * Validates Fresnel edge brightness and refraction distortion.
 */
class OptiXRefractionTest extends AnyFlatSpec with Matchers with LazyLogging:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Refractive sphere" should "show minimal refraction at IOR=1.0" in:
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
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.502f)  // Semi-transparent white
    renderer.setIOR(1.0f)  // No refraction
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Should show background but without significant distortion
    val bgVisible = ImageValidation.backgroundVisibility(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    bgVisible shouldBe true

    renderer.dispose()

  it should "show water-like refraction at IOR=1.33" in:
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
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.502f)
    renderer.setIOR(1.33f)  // Water
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Should show moderate variation (refraction distortion)
    val stdDev = ImageAnalysis.brightnessStdDev(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    stdDev should be > 15.0

    renderer.dispose()

  it should "show glass-like refraction at IOR=1.5" in:
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
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.502f)
    renderer.setIOR(1.5f)  // Glass
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Should show strong variation (glass refraction)
    val stdDev = ImageAnalysis.brightnessStdDev(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    stdDev should be > 20.0

    renderer.dispose()

  it should "show diamond-like refraction at IOR=2.42" in:
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
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.902f)  // Nearly opaque
    renderer.setIOR(2.42f)  // Diamond
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Should show very high variation (strong refraction)
    val stdDev = ImageAnalysis.brightnessStdDev(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    stdDev should be > 25.0

    renderer.dispose()

  it should "show Fresnel edge brightness at IOR=1.5" in:
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
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.502f)
    renderer.setIOR(1.5f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Fresnel effect: edges should be brighter than center for refractive materials
    val gradient = ImageValidation.brightnessGradient(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    // Negative gradient = edges brighter (Fresnel)
    logger.info(f"Fresnel gradient (center-edge) at IOR=1.5: $gradient%.2f")
    gradient should be < 0.0

    renderer.dispose()

  it should "increase edge brightness with higher IOR" in:
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
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.784f)
    renderer.setScale(1.0f)

    // Measure edge brightness at IOR=1.33 (water)
    renderer.setIOR(1.33f)
    val imageWater = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val edgeBrightnessWater = ImageValidation.edgeBrightness(
      imageWater,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    // Measure edge brightness at IOR=2.42 (diamond)
    renderer.setIOR(2.42f)
    val imageDiamond = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val edgeBrightnessDiamond = ImageValidation.edgeBrightness(
      imageDiamond,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    logger.info(f"Edge brightness: Water(1.33)=$edgeBrightnessWater%.2f Diamond(2.42)=$edgeBrightnessDiamond%.2f")
    edgeBrightnessDiamond should be >= edgeBrightnessWater

    renderer.dispose()

  it should "transmit green light through green glass" in:
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
    renderer.setSphereColor(0.0f, 1.0f, 0.502f, 0.502f)  // Green-tinted glass
    renderer.setIOR(1.5f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Green channel should dominate
    val (r, g, b) = ImageValidation.colorChannelRatio(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    logger.info(f"Green glass RGB ratios: R=$r%.3f G=$g%.3f B=$b%.3f")
    g should be > r
    g should be > b

    renderer.dispose()

  it should "transmit blue light through blue glass" in:
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
    renderer.setSphereColor(0.392f, 0.588f, 1.0f, 0.502f)  // Blue-tinted glass
    renderer.setIOR(1.5f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Blue channel should dominate
    val (r, g, b) = ImageValidation.colorChannelRatio(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    logger.info(f"Blue glass RGB ratios: R=$r%.3f G=$g%.3f B=$b%.3f")
    b should be > r

    renderer.dispose()

  it should "show background distortion through refractive sphere" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)  // Checkered floor
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.392f)  // Highly transparent
    renderer.setIOR(1.5f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // Background should be visible but distorted
    val bgVisible = ImageValidation.backgroundVisibility(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    bgVisible shouldBe true

    // High variation due to refraction distorting checkered pattern
    val stdDev = ImageAnalysis.brightnessStdDev(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    stdDev should be > 20.0

    renderer.dispose()
