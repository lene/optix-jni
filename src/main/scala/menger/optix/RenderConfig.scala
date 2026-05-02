package menger.optix

object RenderLimits:
  /** Pipeline-level ray trace depth ceiling, matching MAX_TRACE_DEPTH in OptiXData.h.
   *  Increasing this requires recompiling the native library with a matching C++ change. */
  val MaxRayDepth: Int = 5

// Configuration for render quality options
case class RenderConfig(
  shadows: Boolean = false,
  transparentShadows: Boolean = false,  // Sprint 13.2: colored shadows through transparent objects
  antialiasing: Boolean = false,
  aaMaxDepth: Int = 2,
  aaThreshold: Float = 0.1f,
  maxRayDepth: Int = RenderLimits.MaxRayDepth,
  // Sprint 18.3: opt-in GPU 4D rotation + projection. When true, 4D
  // tesseract-derived meshes upload as raw 4D quads and the kernel
  // does rotation + perspective divide on the GPU at scene-build
  // time (and per-frame via Cut F's update path).
  gpuProject4D: Boolean = false
):
  require(aaMaxDepth >= 1 && aaMaxDepth <= 4, s"aaMaxDepth must be 1-4, got $aaMaxDepth")
  require(aaThreshold >= 0.0f && aaThreshold <= 1.0f, s"aaThreshold must be 0.0-1.0, got $aaThreshold")
  require(maxRayDepth >= 1 && maxRayDepth <= RenderLimits.MaxRayDepth,
    s"maxRayDepth must be 1-${RenderLimits.MaxRayDepth} (pipeline ceiling), got $maxRayDepth")

object RenderConfig:
  val Default: RenderConfig = RenderConfig()
  val HighQuality: RenderConfig = RenderConfig(shadows = true, antialiasing = true, aaMaxDepth = 3, aaThreshold = 0.05f)

// Configuration for Progressive Photon Mapping caustics
case class CausticsConfig(
  enabled: Boolean = false,
  photonsPerIteration: Int = 100000,
  iterations: Int = 10,
  initialRadius: Float = 1.0f,
  alpha: Float = 0.7f
):
  require(photonsPerIteration > 0 && photonsPerIteration <= CausticsConfig.MaxPhotonsPerIteration,
    s"photonsPerIteration must be 1-${CausticsConfig.MaxPhotonsPerIteration}, got $photonsPerIteration")
  require(iterations > 0 && iterations <= CausticsConfig.MaxIterations,
    s"iterations must be 1-${CausticsConfig.MaxIterations}, got $iterations")
  require(initialRadius > 0.0f && initialRadius <= CausticsConfig.MaxInitialRadius,
    s"initialRadius must be 0.0-${CausticsConfig.MaxInitialRadius}, got $initialRadius")
  require(alpha > 0.0f && alpha < 1.0f, s"alpha must be 0.0-1.0 exclusive, got $alpha")

object CausticsConfig:
  val MaxPhotonsPerIteration: Int   = 10000000
  val MaxIterations: Int            = 1000
  val MaxInitialRadius: Float       = 10.0f

  val Disabled: CausticsConfig = CausticsConfig()
  val Default: CausticsConfig = CausticsConfig(enabled = true)
  val HighQuality: CausticsConfig = CausticsConfig(enabled = true, photonsPerIteration = 500000, iterations = 20, alpha = 0.8f)
