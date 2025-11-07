package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import OptiXRendererTest.TestConfig

/** Geometric validation tests. */
class OptiXGeometryTest extends AnyFlatSpec with Matchers:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Sphere geometry" should "scale area with radius²" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setSphereColor(0.784f, 0.784f, 0.784f, 1.0f)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    val img1 = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val area1 = ImageValidation.spherePixelArea(img1, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.0f)
    val img2 = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val area2 = ImageValidation.spherePixelArea(img2, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    val ratio = area2.toDouble / area1.toDouble
    ratio should (be >= 3.0 and be <= 5.0)

    renderer.dispose()

  it should "detect center position correctly" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.784f, 0.784f, 0.784f, 1.0f)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val (cx, cy) = ImageValidation.detectSphereCenter(imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    cx should (be >= TestConfig.TestImageSize._1 / 2 - 30 and be <= TestConfig.TestImageSize._1 / 2 + 30)
    cy should (be >= TestConfig.TestImageSize._2 / 2 - 30 and be <= TestConfig.TestImageSize._2 / 2 + 30)

    renderer.dispose()
