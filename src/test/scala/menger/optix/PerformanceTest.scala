package menger.optix

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ColorConstants.*
import ThresholdConstants.*

/**
 * Performance benchmarking tests.
 *
 * Tests rendering performance across different scenarios:
 * - Opaque vs transparent spheres
 * - Different IOR values (no refraction, glass, diamond)
 * - Different sphere sizes
 *
 * All tests validate >10 FPS threshold for real-time rendering capability.
 *
 * Uses refactored test utilities:
 * - RendererFixture for automatic lifecycle
 * - ColorConstants for performance test configurations
 * - TestScenario.performance*() methods
 * - ThresholdConstants for FPS validation
 * - Helper method to eliminate FPS measurement duplication
 */
class PerformanceTest extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  val runningUnderSanitizer: Boolean = sys.env.get("RUNNING_UNDER_COMPUTE_SANITIZER").contains("true")

  // Ensure library is loaded before running tests
  OptiXRenderer.isLibraryLoaded shouldBe true

  "OptiX renderer" should "achieve >10 FPS for opaque spheres" in:
    assume(!runningUnderSanitizer, "Performance test skipped under compute-sanitizer instrumentation")
    val fps = TestUtilities.measureFPS(
      renderer,
      TestScenario.performanceBaseline()
        .withPlane(1, false, -2.0f),
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )

    logger.info(f"Opaque sphere performance: $fps%.1f FPS")
    fps should be > MIN_FPS

  it should "achieve >10 FPS for transparent spheres" in:
    assume(!runningUnderSanitizer, "Performance test skipped under compute-sanitizer instrumentation")
    val fps = TestUtilities.measureFPS(
      renderer,
      TestScenario.performanceTransparent()
        .withIOR(1.5f)
        .withPlane(1, false, -2.0f),
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )

    logger.info(f"Transparent sphere performance: $fps%.1f FPS")
    fps should be > MIN_FPS

  it should "achieve >10 FPS for high-IOR materials" in:
    assume(!runningUnderSanitizer, "Performance test skipped under compute-sanitizer instrumentation")
    val fps = TestUtilities.measureFPS(
      renderer,
      TestScenario.diamondSphere()
        .withSphereColor(HIGHLY_TRANSPARENT_WHITE)
        .withPlane(1, false, -2.0f),
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )

    logger.info(f"Diamond material performance: $fps%.1f FPS")
    fps should be > MIN_FPS

  it should "achieve >10 FPS for large spheres" in:
    assume(!runningUnderSanitizer, "Performance test skipped under compute-sanitizer instrumentation")
    val fps = TestUtilities.measureFPS(
      renderer,
      TestScenario.largeSphere()
        .withSphereColor(PERFORMANCE_TEST_GREEN_CYAN)
        .withIOR(1.5f)
        .withSphereRadius(2.0f)
        .withPlane(1, false, -2.0f),
      TEST_IMAGE_SIZE._1,
      TEST_IMAGE_SIZE._2
    )

    logger.info(f"Large sphere performance: $fps%.1f FPS")
    fps should be > MIN_FPS
