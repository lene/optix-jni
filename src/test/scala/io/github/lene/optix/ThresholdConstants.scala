package io.github.lene.optix

import menger.common.ImageSize

object ThresholdConstants:

  // ========== Performance Thresholds ==========

  // Maximum render-time ratio (subject / reference) for PerformanceSuite and ShadowSuite,
  // judged by io.github.lene.qa.RelativeBenchmark: both scenes are timed in interleaved rounds
  // on the same renderer, so throttling and background load hit both sides alike. (The
  // previous design divided by one calibration render measured once per suite; its value
  // swung ~50% between runs on a throttling laptop GPU and failed gates with unchanged code.)
  // Each limit is ~2x the highest upper confidence bound measured on this project's RTX A1000
  // laptop GPU (also the CI runner), over 6 idle runs and 5 runs under a 99% GPU burn plus CPU
  // load (2026-09-23; load compressed every ratio toward 1). Highest bound -> limit:
  val MAX_SLOWDOWN_OPAQUE = 2.5          // 1.22 (loaded)
  val MAX_SLOWDOWN_TRANSPARENT = 4.5     // 2.20
  val MAX_SLOWDOWN_DIAMOND = 4.2         // 2.06
  val MAX_SLOWDOWN_LARGE_SPHERE = 26.0   // 12.73
  val MAX_SLOWDOWN_BUFFER_REUSE = 2.1    // 1.05 (loaded)
  val MAX_SLOWDOWN_ANTIALIASING = 330.0  // 160.9 (AA on vs the same scene with AA off)

  // Images with lighting/shading should have stddev > 10; solid colors are near 0
  val MIN_BRIGHTNESS_VARIATION = 10.0

  // ========== Sphere Area Thresholds (pixels) ==========
  // All thresholds for 320x240 viewport (76,800 total pixels)

  val SMALL_SPHERE_MAX_AREA = 320
  val MEDIUM_SPHERE_MIN_AREA = 320
  val MEDIUM_SPHERE_MAX_AREA = 9600
  val LARGE_SPHERE_MIN_AREA = 3200
  val VERY_LARGE_SPHERE_MIN_AREA = 6400
  val MIN_VISIBLE_SPHERE_AREA = 320
  val MAX_TRANSPARENT_COMPARISON_AREA = 6400   // Transparent has fewer colored pixels than opaque
  val MIN_SEMI_TRANSPARENT_AREA = 64

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

  val QUICK_TEST_SIZE = ImageSize(100, 75)      // Fast basic tests (4:3 aspect)
  val TEST_IMAGE_SIZE = ImageSize(320, 240)     // Most integration tests (4:3 aspect)
  val STANDARD_IMAGE_SIZE = ImageSize(640, 480) // Visual validation (4:3 aspect)

  // ========== Shadow Detection Grid Sizes ==========

  val DEFAULT_SHADOW_GRID = 8   // Standard grid for shadow region detection
  val LARGE_SHADOW_GRID = 10    // Fine-grained grid for precise shadow detection

  // ========== Shadow Brightness Thresholds (out of 255) ==========

  val TRANSPARENT_SHADOW_MIN_BRIGHTNESS = 130.0  // alpha=0.0 shadow region should be bright
  val OPAQUE_SHADOW_MAX_BRIGHTNESS = 95.0        // alpha=1.0 shadow region should be dark
  val DARK_SHADOW_THRESHOLD = 150.0              // Threshold for "dark" shadow

  // ========== Shadow Comparison Ratios ==========

  // Transparent shadow should be 1.3x brighter than opaque
  val TRANSPARENT_OPAQUE_BRIGHTNESS_RATIO = 1.3
  val MAX_SHADOW_DARKENING_RATIO = 0.65          // Shadow can darken to 65% of lit brightness
  // Moderate shadow darkening (80% of lit brightness)
  val MODERATE_SHADOW_RATIO = 0.8
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

  // Render time with / without shadows: the original "< 100% overhead" requirement. Highest
  // measured upper confidence bound 1.34 (idle), 1.20 under load.
  val MAX_SLOWDOWN_SHADOWS = 2.0

  // ========== Shadow Brightness Comparison Tolerance ==========

  val BRIGHTNESS_TOLERANCE = 1.0                 // ±1 brightness unit for floating-point variance

  // ========== Colored Shadow Thresholds ==========

  // Minimum per-channel difference to confirm color tinting in shadow region
  val MIN_COLOR_TINT_DIFFERENCE = 5.0

  // Maximum per-channel spread for "achromatic" (no color tint) assertion
  val ACHROMATIC_CHANNEL_TOLERANCE = 3.0
