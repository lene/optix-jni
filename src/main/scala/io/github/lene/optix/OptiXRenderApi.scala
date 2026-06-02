package io.github.lene.optix

import java.util.Optional

import menger.common.CausticsConfig
import menger.common.ImageSize
import menger.common.RenderConfig

private[optix] trait OptiXRenderApi:
  this: OptiXRenderer =>

  def setRenderConfig(config: RenderConfig): Unit =
    setShadows(config.shadows)
    setTransparentShadows(config.transparentShadows)
    setAntialiasing(config.antialiasing, config.aaMaxDepth, config.aaThreshold)
    setMaxRayDepth(config.maxRayDepth)
    setToneMapping(config.toneMappingOperator, config.toneMappingExposure)

  def setToneMapping(operator: Int, exposure: Float): Unit =
    setToneMappingNative(operator, exposure)

  def setIBL(enabled: Boolean, strength: Float, samples: Int): Unit =
    setIBLNative(enabled, strength, samples)

  def setAccumulationFrames(n: Int): Unit =
    setAccumulationFramesNative(n)

  def setCausticsConfig(config: CausticsConfig): Unit =
    setCaustics(config.enabled, config.photonsPerIteration, config.iterations, config.initialRadius, config.alpha)

  def enableCaustics(
    photonsPerIter: Int = 100000,
    iterations: Int = 10,
    initialRadius: Float = 1.0f,
    alpha: Float = 0.7f
  ): Unit =
    setCaustics(true, photonsPerIter, iterations, initialRadius, alpha)

  def disableCaustics(): Unit =
    setCaustics(false, 0, 0, 0.0f, 0.0f)

  /** @return caustics statistics if photons were emitted, or null if caustics are disabled / no photons emitted */
  def getCausticsStats: CausticsStats =
    try
      val stats = getCausticsStatsNative()
      if stats != null && stats.photonsEmitted > 0 then stats else null // scalafix:ok DisableSyntax.null
    catch
      case _: Exception => null // scalafix:ok DisableSyntax.null

  /** @return the render result with timing, or an empty Optional on failure.
    *  java.util.Optional (not scala.Option) keeps this JNI API consumable from Java/Kotlin. */
  def renderWithStats(size: ImageSize): Optional[RenderResult] =
    val startNs = System.nanoTime()
    val raw = renderWithStats(size.width, size.height)
    val elapsedMs = (System.nanoTime() - startNs).toFloat / 1_000_000f
    Optional.ofNullable(raw).map((r: RenderResult) => r.copy(frameMs = elapsedMs))

  /** @return rendered image bytes, or null on failure */
  def render(size: ImageSize): Array[Byte] =
    renderWithStats(size).map((r: RenderResult) => r.image).orElse(null) // scalafix:ok DisableSyntax.null

  /** @return rendered image bytes, or null on failure */
  def render(width: Int, height: Int): Array[Byte] =
    render(ImageSize(width, height))
