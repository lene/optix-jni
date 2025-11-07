package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import OptiXRendererTest.TestConfig
import OptiXRendererTest.ImageAnalysis

import java.io.FileOutputStream

/**
 * Debug test to visualize rendering at different alpha values.
 * Saves PPM images to inspect actual rendering output.
 */
class OptiXDebugRenderTest extends AnyFlatSpec with Matchers with LazyLogging:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "Debug renderer" should "save images at different alpha values" in:
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
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    // Render at different alpha values and save images
    val alphas = Seq(0, 64, 128, 191, 255)  // 0.0, 0.25, 0.5, 0.75, 1.0
    alphas.foreach: alpha =>
      renderer.setSphereColor(0.502f, 0.502f, 0.502f, alpha / 255.0f)
      val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

      // Calculate center brightness
      val (centerX, centerY) = ImageValidation.detectSphereCenter(
        imageData,
        TestConfig.TestImageSize._1,
        TestConfig.TestImageSize._2
      )
      val centerIdx = centerY * TestConfig.TestImageSize._1 + centerX
      val brightness = ImageAnalysis.brightness(imageData, centerIdx)

      // Save as PPM
      val filename = s"optix_debug_alpha_${alpha}.ppm"
      savePPM(filename, imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

      logger.info(f"Alpha=$alpha%3d (${alpha/255.0}%.2f): center=($centerX,$centerY), brightness=$brightness%.2f → saved to $filename")

    renderer.dispose()

  it should "save images at different alpha values with IOR=1.5" in:
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
    renderer.setIOR(1.5f)  // Glass
    renderer.setScale(1.0f)

    // Render at different alpha values and save images
    val alphas = Seq(0, 64, 128, 191, 255)  // 0.0, 0.25, 0.5, 0.75, 1.0
    alphas.foreach: alpha =>
      renderer.setSphereColor(0.502f, 0.502f, 0.502f, alpha / 255.0f)
      val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

      val filename = s"optix_debug_alpha_ior15_${alpha}.ppm"
      savePPM(filename, imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

      logger.info(f"IOR=1.5 Alpha=$alpha%3d (${alpha/255.0}%.2f) → saved to $filename")

    renderer.dispose()

  it should "save pure color images" in:
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
    renderer.setIOR(1.0f)
    renderer.setScale(1.0f)

    // Test pure colors
    val colors = Seq(
      ("red", 1.0f, 0.0f, 0.0f),
      ("green", 0.0f, 1.0f, 0.0f),
      ("blue", 0.0f, 0.0f, 1.0f),
      ("white", 1.0f, 1.0f, 1.0f),
      ("gray", 0.502f, 0.502f, 0.502f)
    )

    colors.foreach: (name, r, g, b) =>
      renderer.setSphereColor(r, g, b, 1.0f)
      val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

      val filename = s"optix_debug_color_${name}.ppm"
      savePPM(filename, imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

      val dominant = ImageAnalysis.dominantColorChannel(imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
      logger.info(f"IOR=1.0 Color $name%6s: dominant=$dominant → saved to $filename")

    renderer.dispose()

  it should "save pure color images with IOR=1.5" in:
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
    renderer.setIOR(1.5f)  // Glass
    renderer.setScale(1.0f)

    // Test pure colors
    val colors = Seq(
      ("red", 1.0f, 0.0f, 0.0f),
      ("green", 0.0f, 1.0f, 0.0f),
      ("blue", 0.0f, 0.0f, 1.0f),
      ("white", 1.0f, 1.0f, 1.0f),
      ("gray", 0.502f, 0.502f, 0.502f)
    )

    colors.foreach: (name, r, g, b) =>
      renderer.setSphereColor(r, g, b, 1.0f)
      val imageData = renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

      val filename = s"optix_debug_color_ior15_${name}.ppm"
      savePPM(filename, imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)

      val dominant = ImageAnalysis.dominantColorChannel(imageData, TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
      logger.info(f"IOR=1.5 Color $name%6s: dominant=$dominant → saved to $filename")

    renderer.dispose()

  private def savePPM(filename: String, imageData: Array[Byte], width: Int, height: Int): Unit =
    val out = new FileOutputStream(filename)
    try
      // PPM header
      val header = s"P6\n$width $height\n255\n"
      out.write(header.getBytes)

      // RGB data (strip alpha channel)
      for i <- 0 until (width * height) do
        val offset = i * 4
        out.write(imageData(offset) & 0xFF)      // R
        out.write(imageData(offset + 1) & 0xFF)  // G
        out.write(imageData(offset + 2) & 0xFF)  // B
    finally
      out.close()
