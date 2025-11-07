package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import OptiXRendererTest.TestConfig

/** Camera position variation tests. */
class OptiXCameraTest extends AnyFlatSpec with Matchers:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Camera position" should "enable ceiling plane visibility from y=0.5" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(Array(0.0f, 0.5f, 3.0f), Array(0.0f, 0.0f, 0.0f), Array(0.0f, 1.0f, 0.0f), 60.0f)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, true, 2.0f)  // Ceiling at y=2
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.784f, 0.784f, 0.784f, 1.0f)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val topVisible = ImageValidation.planeVisibility(imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2, "top")

    topVisible shouldBe true

    renderer.dispose()

  it should "enable floor plane visibility from y=0.5" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(Array(0.0f, 0.5f, 3.0f), Array(0.0f, 0.0f, 0.0f), Array(0.0f, 1.0f, 0.0f), 60.0f)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)  // Floor at y=-2
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.784f, 0.784f, 0.784f, 1.0f)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val bottomVisible = ImageValidation.planeVisibility(imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2, "bottom")

    bottomVisible shouldBe true

    renderer.dispose()
