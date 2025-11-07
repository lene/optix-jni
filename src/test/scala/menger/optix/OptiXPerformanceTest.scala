package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import OptiXRendererTest.TestConfig

/** Performance benchmarking tests. */
class OptiXPerformanceTest extends AnyFlatSpec with Matchers with LazyLogging:

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "OptiX renderer" should "achieve >10 FPS for opaque spheres" in:
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

    val frames = 100
    val start = System.nanoTime
    for _ <- 0 until frames do renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val duration = (System.nanoTime - start) / 1e9
    val fps = frames / duration

    logger.info(f"Opaque sphere performance: ${fps}%.1f FPS")
    fps should be > 10.0

    renderer.dispose()

  it should "achieve >10 FPS for transparent spheres" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.502f)
    renderer.setIOR(1.5f)
    renderer.setScale(1.0f)

    val frames = 100
    val start = System.nanoTime
    for _ <- 0 until frames do renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val duration = (System.nanoTime - start) / 1e9
    val fps = frames / duration

    logger.info(f"Transparent sphere performance: ${fps}%.1f FPS")
    fps should be > 10.0

    renderer.dispose()

  it should "achieve >10 FPS for high-IOR materials" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 0.5f)
    renderer.setSphereColor(1.0f, 1.0f, 1.0f, 0.902f)
    renderer.setIOR(2.42f)  // Diamond
    renderer.setScale(1.0f)

    val frames = 100
    val start = System.nanoTime
    for _ <- 0 until frames do renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val duration = (System.nanoTime - start) / 1e9
    val fps = frames / duration

    logger.info(f"Diamond material performance: ${fps}%.1f FPS")
    fps should be > 10.0

    renderer.dispose()

  it should "achieve >10 FPS for large spheres" in:
    val renderer = new OptiXRenderer
    renderer.initialize()
    renderer.setCamera(TestConfig.DefaultCameraEye, TestConfig.DefaultCameraLookAt,
      TestConfig.DefaultCameraUp, TestConfig.DefaultCameraFov)
    renderer.setLight(TestConfig.DefaultLightDirection, TestConfig.DefaultLightIntensity)
    renderer.setPlane(1, false, -2.0f)
    renderer.setSphere(0.0f, 0.0f, 0.0f, 2.0f)  // Large sphere
    renderer.setSphereColor(0.0f, 1.0f, 0.502f, 0.502f)
    renderer.setIOR(1.5f)
    renderer.setScale(1.0f)

    val frames = 100
    val start = System.nanoTime
    for _ <- 0 until frames do renderer.render(TestConfig.TestImageSize._1, TestConfig.TestImageSize._2)
    val duration = (System.nanoTime - start) / 1e9
    val fps = frames / duration

    logger.info(f"Large sphere performance: ${fps}%.1f FPS")
    fps should be > 10.0

    renderer.dispose()
