package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import OptiXRendererTest.TestConfig

/** Plane rendering tests. */
class OptiXPlaneTest extends AnyFlatSpec with Matchers:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Plane rendering" should "show floor at bottom of image" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.784f, 0.784f, 0.784f, 1.0f)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val bottomVisible = ImageValidation.planeVisibility(imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2, "bottom")

    bottomVisible shouldBe true

    renderer.dispose()

  it should "show ceiling at top of image" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, true, 2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.784f, 0.784f, 0.784f, 1.0f)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val topVisible = ImageValidation.planeVisibility(imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2, "top")

    topVisible shouldBe true

    renderer.dispose()

  it should "be visible through transparent sphere" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.196f)  // Very transparent
    renderer.setIOR(1.5f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val bgVisible = ImageValidation.backgroundVisibility(imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    bgVisible shouldBe true

    renderer.dispose()
