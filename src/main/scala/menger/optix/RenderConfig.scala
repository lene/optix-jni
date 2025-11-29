package menger.optix

// Configuration for render quality options
case class RenderConfig(
  shadows: Boolean = false,
  antialiasing: Boolean = false,
  aaMaxDepth: Int = 2,
  aaThreshold: Float = 0.1f
):
  require(aaMaxDepth >= 1 && aaMaxDepth <= 4, s"aaMaxDepth must be 1-4, got $aaMaxDepth")
  require(aaThreshold >= 0.0f && aaThreshold <= 1.0f, s"aaThreshold must be 0.0-1.0, got $aaThreshold")

object RenderConfig:
  val Default: RenderConfig = RenderConfig()
  val HighQuality: RenderConfig = RenderConfig(shadows = true, antialiasing = true, aaMaxDepth = 3, aaThreshold = 0.05f)

// Configuration for Progressive Photon Mapping caustics
case class CausticsConfig(
  enabled: Boolean = false,
  photonsPerIteration: Int = 100000,
  iterations: Int = 10,
  initialRadius: Float = 0.1f,
  alpha: Float = 0.7f
):
  require(photonsPerIteration > 0 && photonsPerIteration <= 10000000, s"photonsPerIteration must be 1-10000000, got $photonsPerIteration")
  require(iterations > 0 && iterations <= 1000, s"iterations must be 1-1000, got $iterations")
  require(initialRadius > 0.0f && initialRadius <= 10.0f, s"initialRadius must be 0.0-10.0, got $initialRadius")
  require(alpha > 0.0f && alpha < 1.0f, s"alpha must be 0.0-1.0 exclusive, got $alpha")

object CausticsConfig:
  val Disabled: CausticsConfig = CausticsConfig()
  val Default: CausticsConfig = CausticsConfig(enabled = true)
  val HighQuality: CausticsConfig = CausticsConfig(enabled = true, photonsPerIteration = 500000, iterations = 20, alpha = 0.8f)
