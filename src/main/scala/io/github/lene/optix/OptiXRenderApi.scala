package io.github.lene.optix

import java.util.Optional

import menger.common.CausticsConfig
import menger.common.ImageSize
import menger.common.RenderConfig

/** Render-configuration and frame rendering helpers exposed through [[OptiXRenderer]].
  *
  * Methods in this trait map common Scala configuration objects to native OptiX
  * state. `renderWithStats` returns `java.util.Optional` so the standalone
  * JNI API remains convenient for Java and Kotlin consumers.
  */
private[optix] trait OptiXRenderApi:
  this: OptiXRenderer =>

  /** Applies shadow, antialiasing, ray-depth, and tone-mapping settings. */
  def setRenderConfig(config: RenderConfig): Unit =
    setShadows(config.shadows)
    setTransparentShadows(config.transparentShadows)
    setAntialiasing(config.antialiasing, config.aaMaxDepth, config.aaThreshold)
    setMaxRayDepth(config.maxRayDepth)
    setToneMapping(config.toneMappingOperator, config.toneMappingExposure)

  /** Sets native tone mapping by operator id and exposure multiplier. */
  def setToneMapping(operator: Int, exposure: Float): Unit =
    setToneMappingNative(operator, exposure)

  /** Configures image-based lighting strength and sample count. */
  def setIBL(enabled: Boolean, strength: Float, samples: Int): Unit =
    setIBLNative(enabled, strength, samples)

  /** Sets temporal accumulation frame count for noisy render paths. */
  def setAccumulationFrames(n: Int): Unit =
    setAccumulationFramesNative(n)

  /** Applies progressive photon-mapping caustics settings. */
  def setCausticsConfig(config: CausticsConfig): Unit =
    setCaustics(config.enabled, config.photonsPerIteration, config.iterations, config.initialRadius, config.alpha)

  /** Enables caustics using explicit photon-map parameters. */
  def enableCaustics(
    photonsPerIter: Int = 100000,
    iterations: Int = 10,
    initialRadius: Float = 1.0f,
    alpha: Float = 0.7f
  ): Unit =
    setCaustics(true, photonsPerIter, iterations, initialRadius, alpha)

  /** Disables caustics and clears native photon-map parameters. */
  def disableCaustics(): Unit =
    setCaustics(false, 0, 0, 0.0f, 0.0f)

  /** Returns caustics statistics, or `null` when caustics are disabled or empty. */
  def getCausticsStats: CausticsStats =
    try
      val stats = getCausticsStatsNative()
      if stats != null && stats.photonsEmitted > 0 then stats else null // scalafix:ok DisableSyntax.null
    catch
      case _: Exception => null // scalafix:ok DisableSyntax.null

  /** Renders an RGBA8 frame and ray statistics for an [[menger.common.ImageSize]].
    *
    * @return `java.util.Optional` containing [[RenderResult]] with elapsed
    *         frame time, or empty on native failure
    */
  def renderWithStats(size: ImageSize): Optional[RenderResult] =
    val startNs = System.nanoTime()
    val raw = renderWithStats(size.width, size.height)
    val elapsedMs = (System.nanoTime() - startNs).toFloat / 1_000_000f
    Optional.ofNullable(raw).map((r: RenderResult) => r.copy(frameMs = elapsedMs))

  /** Renders an RGBA8 image.
    *
    * @return row-major RGBA bytes, or `null` on failure
    */
  def render(size: ImageSize): Array[Byte] =
    renderWithStats(size).map((r: RenderResult) => r.image).orElse(null) // scalafix:ok DisableSyntax.null

  /** Renders an RGBA8 image at the supplied pixel dimensions.
    *
    * @return row-major RGBA bytes, or `null` on failure
    */
  def render(width: Int, height: Int): Array[Byte] =
    render(ImageSize(width, height))
