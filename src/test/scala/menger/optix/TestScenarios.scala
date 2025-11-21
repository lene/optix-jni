package menger.optix

import menger.common.{Color, ImageSize, Vector}
import ColorConstants.*
import ThresholdConstants.*

case class SphereConfig(
  position: Vector[3] = Vector[3](0.0f, 0.0f, 0.0f),
  radius: Float = 0.5f,
  color: Color = OPAQUE_GREEN,
  ior: Float = 1.0f
)

case class CameraConfig(
  eye: Vector[3] = Vector[3](0.0f, 0.5f, 3.0f),
  lookAt: Vector[3] = Vector[3](0.0f, 0.0f, 0.0f),
  up: Vector[3] = Vector[3](0.0f, 1.0f, 0.0f),
  horizontalFov: Float = 60.0f
)

case class LightConfig(
  direction: Vector[3] = Vector[3](0.5f, 0.5f, -0.5f),
  intensity: Float = 1.0f
)

case class PlaneConfig(
  axis: Int = 1,      // Y-axis (horizontal plane)
  positive: Boolean = true,
  value: Float = -1.0f
)

case class TestScenario(
  sphere: SphereConfig = SphereConfig(),
  camera: CameraConfig = CameraConfig(),
  light: LightConfig = LightConfig(),
  plane: Option[PlaneConfig] = None,
  imageDimensions: Option[ImageSize] = Some(ImageSize(800, 600))
):

  // ========== Sphere Configuration ==========

  def withSpherePosition(position: Vector[3]): TestScenario =
    copy(sphere = sphere.copy(position = position))

  def withSphereRadius(radius: Float): TestScenario =
    copy(sphere = sphere.copy(radius = radius))

  def withSphereColor(color: Color): TestScenario =
    copy(sphere = sphere.copy(color = color))

  def withIOR(ior: Float): TestScenario =
    copy(sphere = sphere.copy(ior = ior))

  // ========== Camera Configuration ==========

  def withCameraEye(eye: Vector[3]): TestScenario =
    copy(camera = camera.copy(eye = eye))

  def withCameraDistance(distance: Float): TestScenario =
    copy(camera = camera.copy(eye = Vector[3](camera.eye(0), camera.eye(1), distance)))

  def withCameraLookAt(lookAt: Vector[3]): TestScenario =
    copy(camera = camera.copy(lookAt = lookAt))

  def withCameraUp(up: Vector[3]): TestScenario =
    copy(camera = camera.copy(up = up))

  def withHorizontalFOV(horizontalFov: Float): TestScenario =
    copy(camera = camera.copy(horizontalFov = horizontalFov))

  // ========== Light Configuration ==========

  def withLightDirection(direction: Vector[3]): TestScenario =
    copy(light = light.copy(direction = direction))

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
    copy(imageDimensions = Some(ImageSize(width, height)))

  def withImageDimensions(size: ImageSize): TestScenario =
    copy(imageDimensions = Some(size))

  def withoutImageDimensions(): TestScenario =
    copy(imageDimensions = None)

  // ========== Apply and Render ==========

  
  def applyTo(renderer: OptiXRenderer): Unit =
    // Image dimensions (must be set before camera for correct aspect ratio)
    imageDimensions.foreach { size =>
      renderer.updateImageDimensions(size)
    }

    // Camera
    renderer.setCamera(
      camera.eye,
      camera.lookAt,
      camera.up,
      horizontalFovDegrees = camera.horizontalFov
    )

    // Light
    renderer.setLight(
      light.direction,
      light.intensity
    )

    // Sphere
    renderer.setSphere(
      sphere.position,
      sphere.radius
    )
    renderer.setSphereColor(sphere.color)
    renderer.setIOR(sphere.ior)

    // Plane (optional)
    plane.foreach { p =>
      renderer.setPlane(p.axis, p.positive, p.value)
    }


  def render(renderer: OptiXRenderer, size: ImageSize): Array[Byte] =
    applyTo(renderer)
    // If dimensions weren't set in applyTo, ensure they're set now
    if imageDimensions.isEmpty then
      renderer.updateImageDimensions(size)
    renderer.render(size).get


object TestScenario:

  // ========== Default Scenario ==========

  
  def default(): TestScenario = TestScenario()

  // ========== Material Scenarios ==========

  
  def glassSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(
      color = GLASS_LIGHT_CYAN,
      ior = 1.5f
    )
  )

  
  def waterSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(
      color = CLEAR_GLASS_WHITE,
      ior = 1.33f
    )
  )

  
  def diamondSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(
      color = OPAQUE_WHITE,
      ior = 2.42f
    )
  )

  
  def coloredGlassSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(
      color = TRANSLUCENT_GREEN_CYAN,
      ior = 1.5f
    )
  )

  // ========== Transparency Test Scenarios ==========

  
  def opaqueSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(color = OPAQUE_GREEN)
  )

  
  def semiTransparentSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(color = SEMI_TRANSPARENT_GREEN)
  )

  
  def fullyTransparentSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(color = FULLY_TRANSPARENT_GREEN)
  )

  // ========== Size Test Scenarios ==========

  
  def smallSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(radius = 0.1f)
  )

  
  def largeSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(radius = 1.0f)
  )

  
  def veryLargeSphere(): TestScenario = TestScenario(
    sphere = SphereConfig(radius = 2.0f)
  )

  // ========== Performance Test Scenarios ==========

  
  def performanceBaseline(): TestScenario = TestScenario(
    sphere = SphereConfig(color = OPAQUE_LIGHT_GRAY)
  )

  
  def performanceTransparent(): TestScenario = TestScenario(
    sphere = SphereConfig(color = PERFORMANCE_TEST_WHITE)
  )

  
  def performanceHighlyTransparent(): TestScenario = TestScenario(
    sphere = SphereConfig(color = HIGHLY_TRANSPARENT_WHITE)
  )

  
  def performanceColoredTransparent(): TestScenario = TestScenario(
    sphere = SphereConfig(color = PERFORMANCE_TEST_GREEN_CYAN)
  )

  // ========== Shadow Test Scenarios ==========

  
  def shadowTest(alpha: Float = 1.0f): TestScenario = TestScenario(
    sphere = SphereConfig(
      position = Vector[3](0.0f, 0.0f, 0.0f),
      radius = 0.5f,
      color = ColorConstants.withAlpha(OPAQUE_SHADOW_TEST_GRAY, alpha),
      ior = 1.5f
    ),
    camera = CameraConfig(
      eye = Vector[3](0.0f, 0.0f, 5.0f),
      lookAt = Vector[3](0.0f, -0.3f, 0.0f),
      up = Vector[3](0.0f, 1.0f, 0.0f),
      horizontalFov = 45.0f
    ),
    light = LightConfig(
      direction = Vector[3](0.5f, 0.5f, -0.5f),
      intensity = 1.0f
    ),
    plane = Some(PlaneConfig(
      axis = 1,           // Y-axis
      positive = true,
      value = -0.6f
    )),
    imageDimensions = Some(ThresholdConstants.STANDARD_IMAGE_SIZE)
  )
