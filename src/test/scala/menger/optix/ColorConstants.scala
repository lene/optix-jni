package menger.optix

/**
 * Named color constants for OptiX tests.
 *
 * Provides descriptive names for commonly-used RGBA color values,
 * eliminating magic numbers and improving test readability.
 *
 * Color convention (standard graphics):
 * - RGB values: 0.0 (no intensity) to 1.0 (full intensity)
 * - Alpha: 0.0 (fully transparent, no absorption) to 1.0 (fully opaque, maximum absorption)
 *
 * Float values are normalized to [0.0, 1.0]. To convert from byte values [0, 255]:
 * - Exact: value / 255.0f
 * - Approximate: 0.502f ≈ 128/255, 0.749f ≈ 191/255, etc.
 */
object ColorConstants:

  // ========== Pure Primary Colors (Opaque) ==========

  val OPAQUE_RED = (1.0f, 0.0f, 0.0f, 1.0f)
  val OPAQUE_GREEN = (0.0f, 1.0f, 0.0f, 1.0f)
  val OPAQUE_BLUE = (0.0f, 0.0f, 1.0f, 1.0f)

  // ========== Grayscale (Opaque) ==========

  val OPAQUE_WHITE = (1.0f, 1.0f, 1.0f, 1.0f)
  val OPAQUE_LIGHT_GRAY = (0.784f, 0.784f, 0.784f, 1.0f)  // RGB(200, 200, 200)
  val OPAQUE_MEDIUM_GRAY = (0.5f, 0.5f, 0.5f, 1.0f)

  // ========== Transparency Levels (White Base) ==========

  val SEMI_TRANSPARENT_WHITE = (1.0f, 1.0f, 1.0f, 0.5f)
  val NEARLY_TRANSPARENT_WHITE = (1.0f, 1.0f, 1.0f, 0.098f)   // 10% opaque
  val VERY_TRANSPARENT_WHITE = (1.0f, 1.0f, 1.0f, 0.196f)     // 20% opaque
  val FULLY_TRANSPARENT_WHITE = (1.0f, 1.0f, 1.0f, 0.0f)

  // ========== Transparency Levels (Green Base) ==========

  val MOSTLY_OPAQUE_GREEN = (0.0f, 1.0f, 0.0f, 0.749f)       // 75% opaque
  val SEMI_TRANSPARENT_GREEN = (0.0f, 1.0f, 0.0f, 0.5f)
  val MOSTLY_TRANSPARENT_GREEN = (0.0f, 1.0f, 0.0f, 0.251f)  // 25% opaque
  val FULLY_TRANSPARENT_GREEN = (0.0f, 1.0f, 0.0f, 0.0f)

  // ========== Transparency Levels (Gray Base) ==========

  val SEMI_TRANSPARENT_GRAY = (0.502f, 0.502f, 0.502f, 0.502f)

  // ========== Mixed Colors (Opaque) ==========

  val OPAQUE_GREEN_CYAN = (0.0f, 1.0f, 0.5f, 1.0f)
  val MOSTLY_OPAQUE_LIGHT_GREEN = (0.5f, 1.0f, 0.5f, 0.8f)

  // ========== Material Simulation Colors ==========

  val GLASS_LIGHT_CYAN = (0.784f, 0.902f, 1.0f, 0.902f)      // RGB(200, 230, 255)
  val CLEAR_GLASS_WHITE = (1.0f, 1.0f, 1.0f, 0.949f)
  val TRANSLUCENT_GREEN_CYAN = (0.0f, 1.0f, 0.502f, 0.800f)
  val PERFORMANCE_TEST_WHITE = (1.0f, 1.0f, 1.0f, 0.502f)
  val HIGHLY_TRANSPARENT_WHITE = (1.0f, 1.0f, 1.0f, 0.902f)
  val PERFORMANCE_TEST_GREEN_CYAN = (0.0f, 1.0f, 0.502f, 0.502f)

  // ========== Refraction Test Colors ==========

  val REFRACTION_TEST_GRAY = (0.784f, 0.784f, 0.784f, 0.784f)
  val BLUE_TINTED_GLASS = (0.392f, 0.588f, 1.0f, 0.502f)      // RGB(100, 150, 255)
  val HIGHLY_TRANSPARENT_GRAY = (0.392f, 0.392f, 0.392f, 0.392f)

  // ========== Helper Methods ==========

  def rgba(r: Float, g: Float, b: Float, a: Float): (Float, Float, Float, Float) =
    (r, g, b, a)

  // Converts byte RGB [0-255] to float [0.0-1.0]
  def fromBytes(r: Int, g: Int, b: Int, a: Float): (Float, Float, Float, Float) =
    (r / 255.0f, g / 255.0f, b / 255.0f, a)

  def withAlpha(baseColor: (Float, Float, Float, Float), alpha: Float): (Float, Float, Float, Float) =
    (baseColor._1, baseColor._2, baseColor._3, alpha)
