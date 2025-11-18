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

  // ========== Shadow Detection Grid Sizes ==========

  val DEFAULT_SHADOW_GRID = 8   // Standard grid for shadow region detection
  val LARGE_SHADOW_GRID = 10    // Fine-grained grid for precise shadow detection

  // ========== Shadow Brightness Thresholds (out of 255) ==========

  val TRANSPARENT_SHADOW_MIN_BRIGHTNESS = 130.0  // alpha=0.0 shadow region should be bright
  val OPAQUE_SHADOW_MAX_BRIGHTNESS = 95.0        // alpha=1.0 shadow region should be dark
  val DARK_SHADOW_THRESHOLD = 150.0              // Threshold for "dark" shadow

  // ========== Shadow Comparison Ratios ==========

  val TRANSPARENT_OPAQUE_BRIGHTNESS_RATIO = 1.3  // Transparent shadow should be 1.3x brighter than opaque
  val MAX_SHADOW_DARKENING_RATIO = 0.65          // Shadow can darken to 65% of lit brightness
  val MODERATE_SHADOW_RATIO = 0.8                // Moderate shadow darkening (80% of lit brightness)
  val MIN_SHADOW_CONTRAST_RATIO = 0.85           // Minimum shadow contrast (85% as bright)
  val ALPHA_TOLERANCE_LOWER_RATIO = 0.90         // Alpha comparison tolerance (90% of expected)
  val ALPHA_TOLERANCE_UPPER_RATIO = 1.10         // Alpha comparison tolerance (110% of expected)
  val RADIUS_TOLERANCE_RATIO = 1.05              // Radius-based shadow size tolerance (5%)

  // ========== Shadow Position Tolerances (pixels) ==========

  val CENTER_TOLERANCE_FRACTION = 0.15           // 15% of image width for centering
  val WIDE_CENTER_TOLERANCE_FRACTION = 0.2       // 20% for looser centering tests
  val MIN_SHADOW_SHIFT = 50                      // Minimum detectable shadow shift
  val MODERATE_SHADOW_SHIFT = 40                 // Moderate shadow position change
  val SMALL_SHADOW_SHIFT = 10                    // Small but detectable shift

  // ========== Shadow Region Fractions ==========

  val BOTTOM_REGION_FRACTION = 0.4               // 40% of image height for bottom region

  // ========== Shadow Performance Limits ==========

  val MAX_SHADOW_OVERHEAD = 1.0                  // Max 100% performance overhead for shadows
  val RENDER_ITERATIONS = 10                     // Number of iterations for performance tests

  // ========== Shadow Brightness Comparison Tolerance ==========

  val BRIGHTNESS_TOLERANCE = 1.0                 // ±1 brightness unit for floating-point variance
