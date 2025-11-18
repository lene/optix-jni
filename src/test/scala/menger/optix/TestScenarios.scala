package menger.optix

import ColorConstants.*
import ThresholdConstants.*

/**
 * Fluent builders for common test scenarios.
 *
 * Provides convenient methods to configure OptiXRenderer with
 * pre-configured scenarios (glass sphere, water sphere, etc.)
 * and common rendering parameters.
 *
 * Usage:
 * {{{
 * // Simple scenario
 * val scenario = TestScenario.glassSphere()
 * scenario.applyTo(renderer)
 *
 * // Fluent API with custom overrides
 * val scenario = TestScenario.waterSphere()
 *   .withSphereRadius(1.0f)
 *   .withCameraDistance(5.0f)
 * scenario.applyTo(renderer)
 * val imageData = scenario.render(renderer, 800, 600)
 * }}}
 */

/**
 * Sphere configuration data.
 *
 * @param position Sphere center (x, y, z)
 * @param radius Sphere radius
 * @param color RGBA color tuple (r, g, b, a)
 * @param ior Index of refraction (1.0 = no refraction)
 */
case class SphereConfig(
  position: (Float, Float, Float) = (0.0f, 0.0f, 0.0f),
  radius: Float = 0.5f,
  color: (Float, Float, Float, Float) = OPAQUE_GREEN,
  ior: Float = 1.0f
)

/**
 * Camera configuration data.
 *
 * @param eye Camera position (x, y, z)
 * @param lookAt Point camera is looking at (x, y, z)
 * @param up Up vector (x, y, z)
 * @param fov Field of view in degrees
 */
case class CameraConfig(
  eye: (Float, Float, Float) = (0.0f, 0.5f, 3.0f),
  lookAt: (Float, Float, Float) = (0.0f, 0.0f, 0.0f),
  up: (Float, Float, Float) = (0.0f, 1.0f, 0.0f),
  fov: Float = 60.0f
)

/**
 * Light configuration data.
 *
 * @param direction Light direction vector (x, y, z)
 * @param intensity Light intensity (0.0 to 1.0+)
 */
case class LightConfig(
  direction: (Float, Float, Float) = (0.5f, 0.5f, -0.5f),
  intensity: Float = 1.0f
)

/**
 * Plane configuration data.
 *
 * Note: OptiX plane API uses axis-aligned planes (setPlane(axis, positive, value))
 * rather than arbitrary plane equations. This simplified config reflects that.
 *
 * @param axis Plane axis (0=X, 1=Y, 2=Z)
 * @param positive Whether plane faces positive direction
 * @param value Plane position along axis
 */
case class PlaneConfig(
  axis: Int = 1,      // Y-axis (horizontal plane)
  positive: Boolean = true,
  value: Float = -1.0f
)

/**
 * Complete test scenario with all rendering parameters.
 *
 * Immutable builder for configuring renderer state. All withX methods
 * return a new TestScenario instance (functional style).
 *
 * @param imageDimensions Optional image dimensions (width, height). If None, tests must
 *                        provide dimensions to render() method. If Some, applyTo() will
 *                        configure the renderer with these dimensions before setting camera.
 */
case class TestScenario(
  sphere: SphereConfig = SphereConfig(),
  camera: CameraConfig = CameraConfig(),
  light: LightConfig = LightConfig(),
  plane: Option[PlaneConfig] = None,
  imageDimensions: Option[(Int, Int)] = Some((800, 600))
):

  // ========== Sphere Configuration ==========

  def withSpherePosition(x: Float, y: Float, z: Float): TestScenario =
    copy(sphere = sphere.copy(position = (x, y, z)))

  def withSphereRadius(radius: Float): TestScenario =
    copy(sphere = sphere.copy(radius = radius))

  def withSphereColor(color: (Float, Float, Float, Float)): TestScenario =
    copy(sphere = sphere.copy(color = color))

  def withSphereColor(r: Float, g: Float, b: Float, a: Float): TestScenario =
    copy(sphere = sphere.copy(color = (r, g, b, a)))

  def withIOR(ior: Float): TestScenario =
    copy(sphere = sphere.copy(ior = ior))

  // ========== Camera Configuration ==========

  def withCameraEye(x: Float, y: Float, z: Float): TestScenario =
    copy(camera = camera.copy(eye = (x, y, z)))

  def withCameraDistance(distance: Float): TestScenario =
    val (x, y, _) = camera.eye
    copy(camera = camera.copy(eye = (x, y, distance)))

  def withCameraLookAt(x: Float, y: Float, z: Float): TestScenario =
    copy(camera = camera.copy(lookAt = (x, y, z)))

  def withCameraUp(x: Float, y: Float, z: Float): TestScenario =
    copy(camera = camera.copy(up = (x, y, z)))

  def withFOV(fov: Float): TestScenario =
    copy(camera = camera.copy(fov = fov))

  // ========== Light Configuration ==========

  def withLightDirection(x: Float, y: Float, z: Float): TestScenario =
    copy(light = light.copy(direction = (x, y, z)))

  def withLightIntensity(intensity: Float): TestScenario =
    copy(light = light.copy(intensity = intensity))

  // ========== Plane Configuration ==========

  def withPlane(axis: Int, positive: Boolean, value: Float): TestScenario =
    copy(plane = Some(PlaneConfig(axis, positive, value)))

  def withDefaultPlane(): TestScenario =
    copy(plane = Some(PlaneConfig()))

  def withoutPlane(): TestScenario =
    copy(plane = None)

  // ========== Image Dimensions Configuration ==========

  def withImageDimensions(width: Int, height: Int): TestScenario =
    copy(imageDimensions = Some((width, height)))

  def withoutImageDimensions(): TestScenario =
    copy(imageDimensions = None)

  // ========== Apply and Render ==========

  /**
   * Applies this scenario's configuration to an OptiXRenderer.
   *
   * Sets image dimensions (if configured), camera, light, sphere, IOR, and optionally plane.
   * Does NOT call initialize() or dispose() - caller is responsible.
   */
  def applyTo(renderer: OptiXRenderer): Unit =
    // Image dimensions (must be set before camera for correct aspect ratio)
    imageDimensions.foreach { case (width, height) =>
      renderer.updateImageDimensions(width, height)
    }

    // Camera
    renderer.setCamera(
      Array(camera.eye._1, camera.eye._2, camera.eye._3),
      Array(camera.lookAt._1, camera.lookAt._2, camera.lookAt._3),
      Array(camera.up._1, camera.up._2, camera.up._3),
      camera.fov
    )

    // Light
    renderer.setLight(
      Array(light.direction._1, light.direction._2, light.direction._3),
      light.intensity
    )

    // Sphere
    renderer.setSphere(
      sphere.position._1, sphere.position._2, sphere.position._3,
      sphere.radius
    )
    renderer.setSphereColor(
      sphere.color._1, sphere.color._2, sphere.color._3, sphere.color._4
    )
    renderer.setIOR(sphere.ior)

    // Plane (optional)
    plane.foreach { p =>
      renderer.setPlane(p.axis, p.positive, p.value)
    }

  /**
   * Applies configuration to renderer and renders image.
   *
   * Convenience method that combines applyTo() and render().
   * If imageDimensions are not configured in the scenario, uses the provided width/height.
   *
   */
  def render(renderer: OptiXRenderer, width: Int, height: Int): Array[Byte] =
    applyTo(renderer)
    // If dimensions weren't set in applyTo, ensure they're set now
    if imageDimensions.isEmpty then
      renderer.updateImageDimensions(width, height)
    renderer.render(width, height).get

/**
 * Pre-configured test scenarios for common materials and use cases.
 */
object TestScenario:

  // ========== Default Scenario ==========

  /**
   * Default scenario: opaque green sphere, standard camera/light.
   *
   * Used as baseline for basic rendering tests.
   */
  def default(): TestScenario = TestScenario()

  // ========== Material Scenarios ==========

  /**
   * Glass sphere scenario (IOR=1.5, light cyan tint).
   *
   * Simulates realistic glass with:
   * - IOR 1.5 (typical window glass)
   * - Light cyan-blue tint (GLASS_LIGHT_CYAN)
   * - Semi-transparent (alpha ~0.9)
   */
  def glassSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(
      color = GLASS_LIGHT_CYAN,
      ior = 1.5f
    )
  )

  /**
   * Water sphere scenario (IOR=1.33, clear).
   *
   * Simulates water droplet with:
   * - IOR 1.33 (water at 20°C)
   * - Nearly opaque but clear (CLEAR_GLASS_WHITE)
   * - No color tint
   */
  def waterSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(
      color = CLEAR_GLASS_WHITE,
      ior = 1.33f
    )
  )

  /**
   * Diamond sphere scenario (IOR=2.42, clear).
   *
   * Simulates diamond with:
   * - IOR 2.42 (diamond)
   * - Fully opaque, clear (no tint)
   * - Strong Fresnel reflection at edges
   */
  def diamondSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(
      color = OPAQUE_WHITE,
      ior = 2.42f
    )
  )

  /**
   * Colored glass sphere (green-cyan, IOR=1.5).
   *
   * Simulates colored transparent material with:
   * - IOR 1.5 (glass)
   * - Green-cyan tint with moderate transparency
   */
  def coloredGlassSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(
      color = TRANSLUCENT_GREEN_CYAN,
      ior = 1.5f
    )
  )

  // ========== Transparency Test Scenarios ==========

  /**
   * Opaque sphere (alpha=1.0, green).
   */
  def opaqueSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(color = OPAQUE_GREEN)
  )

  /**
   * Semi-transparent sphere (alpha=0.5, green).
   */
  def semiTransparentSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(color = SEMI_TRANSPARENT_GREEN)
  )

  /**
   * Fully transparent sphere (alpha=0.0, green).
   */
  def fullyTransparentSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(color = FULLY_TRANSPARENT_GREEN)
  )

  // ========== Size Test Scenarios ==========

  /**
   * Small sphere (radius=0.1).
   */
  def smallSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(radius = 0.1f)
  )

  /**
   * Large sphere (radius=1.0).
   */
  def largeSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(radius = 1.0f)
  )

  /**
   * Very large sphere (radius=2.0).
   */
  def veryLargeSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(radius = 2.0f)
  )

  // ========== Performance Test Scenarios ==========

  /**
   * Performance test scenario: opaque gray sphere.
   *
   * Minimal complexity for baseline FPS measurement.
   */
  def performanceBaseline(): TestScenario = TestScenario(
    sphere = SphereConfig(color = OPAQUE_LIGHT_GRAY)
  )

  /**
   * Performance test scenario: semi-transparent white sphere.
   *
   * Moderate complexity with alpha blending.
   */
  def performanceTransparent(): TestScenario = TestScenario(
    sphere = SphereConfig(color = PERFORMANCE_TEST_WHITE)
  )

  /**
   * Performance test scenario: highly transparent white sphere.
   *
   * High complexity with strong transparency effects.
   */
  def performanceHighlyTransparent(): TestScenario = TestScenario(
    sphere = SphereConfig(color = HIGHLY_TRANSPARENT_WHITE)
  )

  /**
   * Performance test scenario: semi-transparent colored sphere.
   *
   * Moderate complexity with color and transparency.
   */
  def performanceColoredTransparent(): TestScenario = TestScenario(
    sphere = SphereConfig(color = PERFORMANCE_TEST_GREEN_CYAN)
  )

  // ========== Shadow Test Scenarios ==========

  /**
   * Standard shadow test scenario with solid plane.
   *
   * Configured for optimal shadow visibility:
   * - Gray sphere (0.75, 0.75, 0.75) at origin
   * - IOR 1.5 (glass-like)
   * - Horizontal plane below sphere at Y=-0.6
   * - Camera looking slightly down from Z=5.0
   * - Light from upper-right (0.5, 0.5, -0.5)
   * - Image size 800x600
   *
   * This matches the setupShadowScene() defaults in ShadowTest.
   */
  def shadowTest(alpha: Float = 1.0f): TestScenario = TestScenario(
    sphere = SphereConfig(
      position = (0.0f, 0.0f, 0.0f),
      radius = 0.5f,
      color = ColorConstants.withAlpha(OPAQUE_SHADOW_TEST_GRAY, alpha),
      ior = 1.5f
    ),
    camera = CameraConfig(
      eye = (0.0f, 0.0f, 5.0f),
      lookAt = (0.0f, -0.3f, 0.0f),
      up = (0.0f, 1.0f, 0.0f),
      fov = 45.0f
    ),
    light = LightConfig(
      direction = (0.5f, 0.5f, -0.5f),
      intensity = 1.0f
    ),
    plane = Some(PlaneConfig(
      axis = 1,           // Y-axis
      positive = true,
      value = -0.6f
    )),
    imageDimensions = Some(ThresholdConstants.STANDARD_IMAGE_SIZE)
  )
