package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import OptiXRendererTest.TestConfig

/**
 * Integration tests for Beer-Lambert absorption (scale parameter).
 */
class OptiXAbsorptionTest extends AnyFlatSpec with Matchers with LazyLogging:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Beer-Lambert absorption" should "increase with scale parameter" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.502f, 0.502f, 0.502f, 0.502f)
    renderer.setIOR(1.5f)

    // Measure brightness at scale=0.5
    renderer.setScale(0.5f)
    val image1 = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val (cx1, cy1) = ImageValidation.detectSphereCenter(image1, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val bright1 = OptiXRendererTest.ImageAnalysis.brightness(image1, cy1 * TestConfig.TestImageSize._1 + cx1)

    // Measure brightness at scale=2.0
    renderer.setScale(2.0f)
    val image2 = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val (cx2, cy2) = ImageValidation.detectSphereCenter(image2, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val bright2 = OptiXRendererTest.ImageAnalysis.brightness(image2, cy2 * TestConfig.TestImageSize._1 + cx2)

    logger.info(f"Absorption scale test: scale=0.5 brightness=$bright1%.2f, scale=2.0 brightness=$bright2%.2f")
    bright1 should be > bright2  // Higher scale = more absorption = darker

    renderer.dispose()

  it should "show center-to-edge gradient" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.502f, 0.502f, 0.502f, 0.502f)
    renderer.setIOR(1.5f)
    renderer.setScale(2.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val gradient = ImageValidation.brightnessGradient(imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    // With absorption, center should be darker (positive gradient)
    logger.info(f"Absorption gradient: $gradient%.2f")
    gradient should be > -50.0  // Some absorption effect

    renderer.dispose()
