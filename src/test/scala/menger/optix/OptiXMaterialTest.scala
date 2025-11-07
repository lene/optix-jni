package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import OptiXRendererTest.TestConfig

/** Real-world material simulation tests. */
class OptiXMaterialTest extends AnyFlatSpec with Matchers with LazyLogging:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Water material" should "render correctly" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.0f)
    renderer.setSphereColor(0.784f, 0.902f, 1.0f, 0.902f)  // Slightly blue-tinted
    renderer.setIOR(1.33f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val (r, g, b) = ImageValidation.colorChannelRatio(imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    logger.info(f"Water material RGB: R=$r%.3f G=$g%.3f B=$b%.3f")
    b should be >= r  // Blue tint

    renderer.dispose()

  "Clear glass" should "render correctly" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.0f)
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.949f)  // Nearly opaque
    renderer.setIOR(1.5f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val stdDev = OptiXRendererTest.ImageAnalysis.brightnessStdDev(imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    stdDev should be > 15.0  // Refraction creates variation

    renderer.dispose()

  "Green glass" should "render correctly" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.0f)
    renderer.setSphereColor(0.0f, 1.0f, 0.502f, 0.800f)  // Green with moderate transparency
    renderer.setIOR(1.5f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val (r, g, b) = ImageValidation.colorChannelRatio(imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    logger.info(f"Green glass RGB: R=$r%.3f G=$g%.3f B=$b%.3f")
    g should be > r
    g should be > b

    renderer.dispose()

  "Diamond" should "show strong edge brightness" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.980f)
    renderer.setIOR(2.42f)  // Diamond
    renderer.setScale(0.5f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val gradient = ImageValidation.brightnessGradient(imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

    logger.info(f"Diamond Fresnel gradient: $gradient%.2f")
    gradient should be < 0.0  // Edges brighter

    renderer.dispose()
