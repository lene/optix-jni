package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import OptiXRendererTest.TestConfig
import OptiXRendererTest.ImageAnalysis

/**
 * Integration tests for opaque sphere rendering.
 *
 * Tests baseline sphere rendering without refraction or transparency:
 * - Varying radii (geometric validation)
 * - Different positions (spatial validation)
 * - Different colors (color accuracy)
 */
class OptiXOpaqueSphereTest extends AnyFlatSpec with Matchers with LazyLogging:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Opaque sphere" should "render at radius 0.1" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
    TestConfig.DefaultCameraEye,
    TestConfig.DefaultCameraLookAt,
    TestConfig.DefaultCameraUp,
    TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.1f)  // Tiny sphere
    renderer.setSphereColor(0.784f, 0.784f, 0.784f, 1.0f)  // Light gray, opaque
    renderer.setIOR(1.0f)  // No refraction
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    imageData should not be null
    imageData.length shouldBe TestConfig.TestImageSize._1 * TestConfig.TestImageSize._2 * 4

    // Tiny sphere should occupy small pixel area
    val area = ImageValidation.spherePixelArea(
    imageData,
    TestConfig.TestImageSize._1,
    TestConfig.TestImageSize._2
    )
    area should be < 500  // Small sphere, small area

    renderer.dispose()

  it should "render at radius 0.5" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.784f, 0.784f, 0.784f, 1.0f)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val area = ImageValidation.spherePixelArea(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    area should (be > 500 and be < 5000)  // Medium sphere

    renderer.dispose()

  it should "render at radius 1.0" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.0f)
    renderer.setSphereColor(0.784f, 0.784f, 0.784f, 1.0f)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val area = ImageValidation.spherePixelArea(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    area should be > 5000  // Large sphere, large area

    renderer.dispose()

  it should "render at radius 2.0" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 2.0f)
    renderer.setSphereColor(0.784f, 0.784f, 0.784f, 1.0f)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val area = ImageValidation.spherePixelArea(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )
    area should be > 10000  // Very large sphere

    renderer.dispose()

  it should "show proportional size scaling" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setSphereColor(0.784f, 0.784f, 0.784f, 1.0f)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    // Render sphere at radius 0.5
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    val image1 = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val area1 = ImageValidation.spherePixelArea(
      image1,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    // Render sphere at radius 1.0 (double the radius)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 1.0f)
    val image2 = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val area2 = ImageValidation.spherePixelArea(
      image2,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    // Area should scale with radius² → area2 ≈ 4 × area1
    val ratio = area2.toDouble / area1.toDouble
    ratio should (be >= 3.0 and be <= 5.0)  // Allow 25% tolerance

    renderer.dispose()

  it should "render at center position (0, 0, 0)" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.784f, 0.784f, 0.784f, 1.0f)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val (centerX, centerY) = ImageValidation.detectSphereCenter(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    // Sphere at origin should appear near image center
    centerX should (be >= TestConfig.TestImageSize._1 / 2 - 30 and
                   be <= TestConfig.TestImageSize._1 / 2 + 30)
    centerY should (be >= TestConfig.TestImageSize._2 / 2 - 30 and
                   be <= TestConfig.TestImageSize._2 / 2 + 30)

    renderer.dispose()

  it should "render with offset position (1, 0, 0)" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setSphere(1.0f, 0.0f, 0.0f, 0.5f)  // Offset to right
    renderer.setSphereColor(0.784f, 0.784f, 0.784f, 1.0f)
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val (centerX, _) = ImageValidation.detectSphereCenter(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    // Sphere offset to +X should appear right of center
    centerX should be > TestConfig.TestImageSize._1 / 2

    renderer.dispose()

  it should "render in pure red" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(1.0f, 0.0f, 0.0f, 1.0f)  // Pure red
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val dominantChannel = ImageAnalysis.dominantColorChannel(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    dominantChannel shouldBe "r"

    renderer.dispose()

  it should "render in pure green" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.0f, 1.0f, 0.0f, 1.0f)  // Pure green
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val dominantChannel = ImageAnalysis.dominantColorChannel(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    dominantChannel shouldBe "g"

    renderer.dispose()

  it should "render in pure blue" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(0.0f, 0.0f, 1.0f, 1.0f)  // Pure blue
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val dominantChannel = ImageAnalysis.dominantColorChannel(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    dominantChannel shouldBe "b"

    renderer.dispose()

  it should "render in white/grayscale" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(
      TestConfig.DefaultCameraEye,
      TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp,
      TestConfig.DefaultCameraFov
    )
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 1.0f)  // White
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val dominantChannel = ImageAnalysis.dominantColorChannel(
      imageData,
      TestConfig.TestImageSize._1,
      TestConfig.TestImageSize._2
    )

    dominantChannel shouldBe "gray"

    renderer.dispose()
