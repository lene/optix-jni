package menger.optix

import menger.common.Color

object ColorConstants:

  // ========== Pure Primary Colors (Opaque) ==========

  val OPAQUE_RED = Color(1.0f, 0.0f, 0.0f, 1.0f)
  val OPAQUE_GREEN = Color(0.0f, 1.0f, 0.0f, 1.0f)
  val OPAQUE_BLUE = Color(0.0f, 0.0f, 1.0f, 1.0f)

  // ========== Grayscale (Opaque) ==========

  val OPAQUE_WHITE = Color(1.0f, 1.0f, 1.0f, 1.0f)
  val OPAQUE_LIGHT_GRAY = Color(0.784f, 0.784f, 0.784f, 1.0f)  // RGB(200, 200, 200)
  val OPAQUE_MEDIUM_GRAY = Color(0.5f, 0.5f, 0.5f, 1.0f)
  val OPAQUE_SHADOW_TEST_GRAY = Color(0.75f, 0.75f, 0.75f, 1.0f)  // Standard gray for shadow tests

  // ========== Transparency Levels (White Base) ==========

  val SEMI_TRANSPARENT_WHITE = Color(1.0f, 1.0f, 1.0f, 0.5f)
  val NEARLY_TRANSPARENT_WHITE = Color(1.0f, 1.0f, 1.0f, 0.098f)   // 10% opaque
  val VERY_TRANSPARENT_WHITE = Color(1.0f, 1.0f, 1.0f, 0.196f)     // 20% opaque
  val FULLY_TRANSPARENT_WHITE = Color(1.0f, 1.0f, 1.0f, 0.0f)

  // ========== Transparency Levels (Green Base) ==========

  val MOSTLY_OPAQUE_GREEN = Color(0.0f, 1.0f, 0.0f, 0.749f)       // 75% opaque
  val SEMI_TRANSPARENT_GREEN = Color(0.0f, 1.0f, 0.0f, 0.5f)
  val MOSTLY_TRANSPARENT_GREEN = Color(0.0f, 1.0f, 0.0f, 0.251f)  // 25% opaque
  val FULLY_TRANSPARENT_GREEN = Color(0.0f, 1.0f, 0.0f, 0.0f)

  // ========== Transparency Levels (Gray Base) ==========

  val SEMI_TRANSPARENT_GRAY = Color(0.502f, 0.502f, 0.502f, 0.502f)

  // ========== Mixed Colors (Opaque) ==========

  val OPAQUE_GREEN_CYAN = Color(0.0f, 1.0f, 0.5f, 1.0f)
  val MOSTLY_OPAQUE_LIGHT_GREEN = Color(0.5f, 1.0f, 0.5f, 0.8f)

  // ========== Material Simulation Colors ==========

  val GLASS_LIGHT_CYAN = Color(0.784f, 0.902f, 1.0f, 0.902f)      // RGB(200, 230, 255)
  val CLEAR_GLASS_WHITE = Color(1.0f, 1.0f, 1.0f, 0.949f)
  val TRANSLUCENT_GREEN_CYAN = Color(0.0f, 1.0f, 0.502f, 0.800f)
  val PERFORMANCE_TEST_WHITE = Color(1.0f, 1.0f, 1.0f, 0.502f)
  val HIGHLY_TRANSPARENT_WHITE = Color(1.0f, 1.0f, 1.0f, 0.902f)
  val PERFORMANCE_TEST_GREEN_CYAN = Color(0.0f, 1.0f, 0.502f, 0.502f)

  // ========== Refraction Test Colors ==========

  val REFRACTION_TEST_GRAY = Color(0.784f, 0.784f, 0.784f, 0.784f)
  val RED_TINTED_GLASS = Color(1.0f, 0.392f, 0.392f, 0.502f)       // RGB(255, 100, 100)
  val BLUE_TINTED_GLASS = Color(0.392f, 0.588f, 1.0f, 0.502f)      // RGB(100, 150, 255)
  val HIGHLY_TRANSPARENT_GRAY = Color(0.392f, 0.392f, 0.392f, 0.392f)

  // ========== Helper Methods ==========

  def withAlpha(baseColor: Color, alpha: Float): Color =
    Color(baseColor.r, baseColor.g, baseColor.b, alpha)
