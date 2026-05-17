package menger.optix

import scala.util.Try

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

  def getCausticsStats: Option[CausticsStats] =
    Try(getCausticsStatsNative()).toOption.filter(_.photonsEmitted > 0)

  def renderWithStats(size: ImageSize): RenderResult =
    val startNs = System.nanoTime()
    val raw = renderWithStats(size.width, size.height)
    val elapsedMs = (System.nanoTime() - startNs).toFloat / 1_000_000f
    Option(raw).map(_.copy(frameMs = elapsedMs)).orNull

  def render(size: ImageSize): Option[Array[Byte]] =
    Option(renderWithStats(size)).map(_.image)

  def render(width: Int, height: Int): Option[Array[Byte]] =
    render(ImageSize(width, height))
