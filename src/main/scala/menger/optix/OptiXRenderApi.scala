package menger.optix

import menger.common.ImageSize

private[optix] trait OptiXRenderApi:
  this: OptiXRenderer =>

  def setRenderConfig(config: RenderConfig): Unit =
    setShadows(config.shadows)
    setTransparentShadows(config.transparentShadows)
    setAntialiasing(config.antialiasing, config.aaMaxDepth, config.aaThreshold)
    setMaxRayDepth(config.maxRayDepth)

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

  def renderWithStats(size: ImageSize): RenderResult =
    val startNs = System.nanoTime()
    val raw = renderWithStats(size.width, size.height)
    val elapsedMs = (System.nanoTime() - startNs).toFloat / 1_000_000f
    if raw != null then raw.copy(frameMs = elapsedMs) else null // scalafix:ok DisableSyntax.null

  /** @return rendered image bytes, or null on failure */
  def render(size: ImageSize): Array[Byte] =
    val result = renderWithStats(size)
    if result != null then result.image else null // scalafix:ok DisableSyntax.null

  /** @return rendered image bytes, or null on failure */
  def render(width: Int, height: Int): Array[Byte] =
    render(ImageSize(width, height))
