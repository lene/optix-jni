package menger.optix

/** Detects rendering failures that produce a uniform pixel buffer.
 *
 *  Common failure modes covered:
 *  - all-red error fill from a shader trap
 *  - all-black: no rays traced, framebuffer never written
 *  - all-(clear-colour): geometry never reached, only background
 *
 *  Usage: call [[RenderHealth.checkRgba]] just before persisting a render.
 *  The method returns normally if the render passes; it throws
 *  [[UniformRenderException]] if the render is uniform within tolerance.
 */
object RenderHealth:

  /** Default fraction-of-pixels threshold beyond which a render is "uniform". */
  val DefaultUniformFraction: Double = 0.99

  /** Default channel epsilon (out of 255) for "same colour". */
  val DefaultChannelEpsilon: Int = 1

  /** Check an RGBA byte buffer (4 bytes per pixel, row-major).
   *
   *  Returns normally when the render passes the health check.
   *  Throws [[UniformRenderException]] when the render is uniform; the
   *  exception message describes the dominant colour and ratio for diagnostics.
   */
  @throws[UniformRenderException]
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def checkRgba(
      pixels: Array[Byte],
      width: Int,
      height: Int,
      uniformFraction: Double = DefaultUniformFraction,
      channelEpsilon: Int = DefaultChannelEpsilon
  ): Unit =
    require(width > 0 && height > 0, "width and height must be positive")
    require(pixels.length >= width * height * 4,
      s"pixel buffer too small for ${width}x$height RGBA, got ${pixels.length} bytes")
    require(uniformFraction > 0.0 && uniformFraction <= 1.0,
      s"uniformFraction must be in (0, 1], got $uniformFraction")
    require(channelEpsilon >= 0, s"channelEpsilon must be non-negative, got $channelEpsilon")

    val totalPixels = width * height

    val refR = pixels(0) & 0xFF
    val refG = pixels(1) & 0xFF
    val refB = pixels(2) & 0xFF

    @scala.annotation.tailrec
    def countMatching(idx: Int, acc: Int): Int =
      if idx >= totalPixels then acc
      else
        val base = idx * 4
        val r    = pixels(base)     & 0xFF
        val g    = pixels(base + 1) & 0xFF
        val b    = pixels(base + 2) & 0xFF
        val hit  =
          math.abs(r - refR) <= channelEpsilon
            && math.abs(g - refG) <= channelEpsilon
            && math.abs(b - refB) <= channelEpsilon
        countMatching(idx + 1, if hit then acc + 1 else acc)

    val matching = countMatching(0, 0)
    val ratio    = matching.toDouble / totalPixels
    if ratio >= uniformFraction then
      val pct = f"${ratio * 100}%.2f"
      throw UniformRenderException(
        s"render is uniform: $pct% of pixels match RGB($refR, $refG, $refB) " +
          s"(epsilon=$channelEpsilon, threshold=${uniformFraction * 100}%)")

/** Thrown by [[RenderHealth.checkRgba]] when a render is detected as uniform. */
final class UniformRenderException(message: String)
    extends RuntimeException(message)
