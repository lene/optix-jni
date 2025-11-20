package menger.optix

import menger.common.ImageSize
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import ColorConstants.*
import ThresholdConstants.*

class RayStatsTest extends AnyFlatSpec with Matchers with RendererFixture:

  "Ray statistics" should "have primary rays equal to pixel count" in:
    TestScenario.default().applyTo(renderer)

    val result = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    result.primaryRays shouldBe STANDARD_IMAGE_SIZE.width * STANDARD_IMAGE_SIZE.height

  it should "have total rays greater than primary rays" in:
    TestScenario.glassSphere().applyTo(renderer)

    val result = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    result.totalRays should be > result.primaryRays

  it should "have refracted rays > 0 for transparent sphere" in:
    TestScenario.glassSphere().applyTo(renderer)

    val result = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    result.refractedRays should be > 0L

  it should "have reflected rays > 0 for glass sphere" in:
    TestScenario.glassSphere().applyTo(renderer)

    val result = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    result.reflectedRays should be > 0L

  it should "have no refracted rays for opaque sphere" in:
    TestScenario.default()
      .withSphereColor(1f, 0f, 0f, 1.0f)  // Fully opaque
      .withSphereRadius(0.5f)
      .withIOR(1.5f)
      .withCameraEye(0f, 0f, 3f)
      .withHorizontalFOV(60f)
      .applyTo(renderer)

    val result = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    result.refractedRays shouldBe 0L
    result.reflectedRays shouldBe 0L  // Opaque sphere doesn't trace rays

  it should "have no refracted rays for fully transparent sphere" in:
    TestScenario.default()
      .withSphereColor(1f, 1f, 1f, 0.0f)  // Fully transparent
      .withSphereRadius(0.5f)
      .withIOR(1.0f)  // No refraction
      .withCameraEye(0f, 0f, 3f)
      .withHorizontalFOV(60f)
      .applyTo(renderer)

    val result = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    // Fully transparent means rays pass through without refraction/reflection
    result.refractedRays shouldBe 0L
    result.reflectedRays shouldBe 0L

  it should "track minimum depth correctly" in:
    TestScenario.glassSphere().applyTo(renderer)

    val result = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    // Minimum depth should be 1 (rays that hit the sphere once)
    result.minDepthReached shouldBe 1

  it should "track maximum depth > minimum depth for glass sphere" in:
    TestScenario.glassSphere().applyTo(renderer)

    val result = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    // Glass sphere with refraction should have multiple bounces
    result.maxDepthReached should be > result.minDepthReached
    result.maxDepthReached should be <= 5  // MAX_TRACE_DEPTH constant

  it should "have more rays for larger image dimensions" in:
    TestScenario.glassSphere().applyTo(renderer)

    val small = renderer.renderWithStats(ImageSize(100, 100))
    val large = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    large.primaryRays should be > small.primaryRays
    large.totalRays should be > small.totalRays

  it should "have different ray counts for different sphere transparency" in:
    // Semi-transparent sphere
    TestScenario.default()
      .withSphereRadius(0.5f)
      .withSphereColor(1f, 1f, 1f, 0.5f)
      .withIOR(1.5f)
      .withCameraEye(0f, 0f, 3f)
      .withHorizontalFOV(60f)
      .applyTo(renderer)
    val semiTransparent = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    // More transparent sphere
    TestScenario.default()
      .withSphereRadius(0.5f)
      .withSphereColor(1f, 1f, 1f, 0.2f)
      .withIOR(1.5f)
      .withCameraEye(0f, 0f, 3f)
      .withHorizontalFOV(60f)
      .applyTo(renderer)
    val moreTransparent = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    // More transparent = less absorption = more refracted rays
    moreTransparent.refractedRays should be >= semiTransparent.refractedRays

  it should "have consistent stats across multiple renders" in:
    TestScenario.glassSphere().applyTo(renderer)

    val result1 = renderer.renderWithStats(STANDARD_IMAGE_SIZE)
    val result2 = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    // Same scene should produce same statistics
    result1.primaryRays shouldBe result2.primaryRays
    result1.totalRays shouldBe result2.totalRays
    result1.reflectedRays shouldBe result2.reflectedRays
    result1.refractedRays shouldBe result2.refractedRays
    result1.minDepthReached shouldBe result2.minDepthReached
    result1.maxDepthReached shouldBe result2.maxDepthReached

  it should "return valid RayStats via stats accessor" in:
    TestScenario.glassSphere().applyTo(renderer)

    val result = renderer.renderWithStats(STANDARD_IMAGE_SIZE)
    val stats = result.stats

    stats.totalRays shouldBe result.totalRays
    stats.primaryRays shouldBe result.primaryRays
    stats.reflectedRays shouldBe result.reflectedRays
    stats.refractedRays shouldBe result.refractedRays
    stats.minDepthReached shouldBe result.minDepthReached
    stats.maxDepthReached shouldBe result.maxDepthReached

  it should "have image data matching render() output" in:
    TestScenario.glassSphere().applyTo(renderer)

    val standardRender = renderImage(STANDARD_IMAGE_SIZE)
    val statsRender = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    statsRender.image.length shouldBe standardRender.length
    // Images should be identical (same rendering)
    statsRender.image shouldBe standardRender

  "Shadow ray statistics" should "be zero when shadows disabled (default)" in:
    TestScenario.default().applyTo(renderer)
    // Shadows disabled by default

    val result = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    result.shadowRays shouldBe 0L

  it should "track shadow rays when shadows enabled" in:
    TestScenario.default().applyTo(renderer)
    renderer.setShadows(true)

    val result = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    // Should have cast shadow rays for lit pixels
    result.shadowRays should be > 0L

  it should "include shadow rays in total ray count" in:
    TestScenario.default().applyTo(renderer)
    renderer.setShadows(true)

    val result = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    // Total rays should include primary + shadow rays (at minimum)
    result.totalRays should be >= (result.primaryRays + result.shadowRays)

  it should "not cast shadow rays for pixels facing away from light" in:
    // Opaque sphere with light behind the camera (opposite direction)
    TestScenario.default()
      .withSphereRadius(0.5f)
      .withSphereColor(1f, 0f, 0f, 1.0f)  // Opaque red
      .withCameraEye(0f, 0f, 3f)  // Camera in front
      .withHorizontalFOV(60f)
      .applyTo(renderer)
    renderer.setLight(
      Array(0f, 0f, -1f),  // Light behind camera (away from sphere)
      1.0f
    )
    renderer.setShadows(true)

    val result = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    // All visible surfaces face away from light, so diffuse=0, no shadow rays
    result.shadowRays shouldBe 0L

  it should "have consistent shadow ray counts across multiple renders" in:
    TestScenario.default().applyTo(renderer)
    renderer.setShadows(true)

    val result1 = renderer.renderWithStats(STANDARD_IMAGE_SIZE)
    val result2 = renderer.renderWithStats(STANDARD_IMAGE_SIZE)

    result1.shadowRays shouldBe result2.shadowRays

  it should "include shadowRays in stats accessor" in:
    TestScenario.default().applyTo(renderer)
    renderer.setShadows(true)

    val result = renderer.renderWithStats(STANDARD_IMAGE_SIZE)
    val stats = result.stats

    stats.shadowRays shouldBe result.shadowRays
