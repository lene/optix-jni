package menger.optix

/**
 * Named threshold constants for OptiX test validation.
 *
 * Provides well-documented, physics-based threshold values for test assertions,
 * eliminating magic numbers and explaining why specific values are chosen.
 *
 * Thresholds are organized by validation category:
 * - Performance: FPS, timing thresholds
 * - Geometry: Area, size, position tolerances
 * - Rendering: Brightness, standard deviation, color ratios
 */
object ThresholdConstants:

  // ========== Performance Thresholds ==========

  // Lenient threshold for CI runners with varying GPU capabilities
  val MIN_FPS = 10.0

  // Images with lighting/shading should have stddev > 10; solid colors are near 0
  val MIN_BRIGHTNESS_VARIATION = 10.0

  // ========== Sphere Area Thresholds (pixels) ==========
  // All thresholds for 400x300 viewport (120,000 total pixels)

  val SMALL_SPHERE_MAX_AREA = 500
  val MEDIUM_SPHERE_MIN_AREA = 500
  val MEDIUM_SPHERE_MAX_AREA = 15000
  val LARGE_SPHERE_MIN_AREA = 5000
  val VERY_LARGE_SPHERE_MIN_AREA = 10000
  val MIN_VISIBLE_SPHERE_AREA = 500
  val MAX_TRANSPARENT_COMPARISON_AREA = 10000  // Transparent has fewer colored pixels than opaque
  val MIN_SEMI_TRANSPARENT_AREA = 100

  // ========== Position and Centering Thresholds ==========

  // Accounts for sub-pixel rendering, anti-aliasing, and numerical precision
  val SPHERE_CENTER_TOLERANCE = 30

  val MIN_OFFSET_DETECTION = 1

  // ========== Aspect Ratio Thresholds ==========

  // For 4:3 viewports: theoretical 4.0, ±25% tolerance for anti-aliasing and edge detection
  val MIN_ASPECT_RATIO_4_3 = 3.0
  val MAX_ASPECT_RATIO_4_3 = 5.0

  // ========== Brightness and Color Thresholds ==========

  val GRAYSCALE_TOLERANCE = 20
  val BACKGROUND_GRAYSCALE_TOLERANCE = 30  // Higher tolerance for anti-aliasing variation
  val MIN_COLOR_CHANNEL_DIFFERENCE = 50
  val GRAYSCALE_CHANNEL_TOLERANCE = 10

  // Refraction creates brightness variation from Fresnel reflection and Beer-Lambert absorption
  val MIN_BASIC_REFRACTION_STDDEV = 10.0
  val MIN_GLASS_REFRACTION_STDDEV = 15.0   // IOR ~1.5
  val MIN_WATER_REFRACTION_STDDEV = 20.0   // IOR ~1.33
  val MIN_DIAMOND_REFRACTION_STDDEV = 25.0 // IOR ~2.42, stronger Fresnel (R₀ ~17% vs glass ~4%)

  // Beer-Lambert: center (longer path) should be darker than edges, but not over-absorbing
  val MIN_ABSORPTION_GRADIENT = -50.0

  // ========== Image Pattern Detection Thresholds ==========

  val CHECKERED_PATTERN_MIN_VARIANCE = 1000  // Alternating black/white squares
  val PLANE_PATTERN_MIN_VARIANCE = 500       // Non-uniform rendering with lighting

  // ========== Test Image Sizes ==========

  val SMALL_IMAGE_SIZE = (10, 10)      // Quick smoke tests
  val TEST_IMAGE_SIZE = (400, 300)     // Most integration tests (4:3 aspect)
  val STANDARD_IMAGE_SIZE = (800, 600) // Visual validation (4:3 aspect)
