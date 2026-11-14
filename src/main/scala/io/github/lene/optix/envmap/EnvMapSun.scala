package io.github.lene.optix.envmap

import java.nio.file.Path

import menger.common.Color
import menger.common.Vector

/** The sun of an equirectangular HDR environment map, measured so that a scene can light it with a
  * directional light and leave it out of the image-based lighting
  * (`OptiXRenderer.setIBLSunExclusion`).
  *
  * @param directionToSun unit vector from the scene to the sun, in the map's frame
  * @param halfAngleDegrees half-angle of the cone around `directionToSun` that holds the sun: the
  *                         disc and the part of its glow that is still the sun
  * @param irradiance per-channel irradiance of a surface facing the sun, `sum L cos dw` over the
  *                   cone
  * @param intensity intensity of the directional light that lights a Lambertian surface facing the
  *                  sun as the cone does, in the renderer's `intensity * cos` convention for
  *                  directional lights: `max(irradiance) / pi`, because image-based lighting
  *                  returns `albedo * E / pi`
  * @param color `irradiance / max(irradiance)`, so 1 in the strongest channel
  * @param powerShare share of the map's total power (luminance times solid angle) inside the cone
  */
final case class EnvMapSun(
    directionToSun: Vector[3],
    halfAngleDegrees: Float,
    irradiance: Vector[3],
    intensity: Float,
    color: Color,
    powerShare: Float)

/** Measures the sun of an equirectangular HDR map. It needs no GPU and no native library.
  *
  * Convention of the map, the one the renderer's background and lighting use (`EnvMapMapping.h`):
  * texel centre (col, row) has u = (col + 0.5) / width, v = (row + 0.5) / height, theta = v * pi
  * from +y, phi = (u - 0.5) * 2 pi and the direction (sin theta cos phi, cos theta, sin theta
  * sin phi), so u = 0.5 is +x, u = 0.75 is +z and v = 0 is +y.
  *
  * Method:
  *  1. The direction is the luminance-weighted centroid of the brightest 0.01 % of the texels
  *     (those at or above the linear-interpolation 0.9999 quantile of the luminance, Rec. 709).
  *  2. The half-angle is the smallest of 0.5, 1, 2 and 4 degrees at which doubling it adds less
  *     than 0.5 % to the luminance of the irradiance, else 4 degrees.
  *  3. The irradiance is `sum L cos dw` per channel over the texels within that half-angle of the
  *     direction, `dw` the solid angle of a texel and `cos` taken about the sun direction.
  *  4. The intensity is its largest channel over pi, the colour the irradiance over that channel.
  *
  * A map with no distinct sun (black, or equal everywhere) has no such centroid and is rejected
  * with `IllegalArgumentException`.
  */
object EnvMapSun:

  private val BrightestFraction = 1.0e-4
  private val ConvergenceFraction = 0.005
  private val CandidateHalfAngles = Array(0.5, 1.0, 2.0, 4.0)
  private val FallbackHalfAngle = 4.0
  // One doubling past the largest candidate, to judge whether the largest is converged
  private val MeasuredHalfAngles = CandidateHalfAngles :+ 2.0 * CandidateHalfAngles.last
  private val Channels = 3
  private val LumaWeights = Array(0.2126, 0.7152, 0.0722)
  // The brightest texels' direction vectors must not cancel out for their centroid to be a direction
  private val MinCentroidLength = 1.0e-3

  def measure(path: Path): EnvMapSun = measure(RadianceHdr.read(path))

  def measure(image: HdrImage): EnvMapSun =
    val geometry = new Geometry(image.width, image.height)
    val centroid = sunDirection(image, geometry)
    val sums = coneSums(image, geometry, centroid)
    val chosen = chosenHalfAngle(sums.irradianceLuminance)
    val irradiance = sums.irradiance(chosen)
    val strongest = irradiance.max
    val Array(r, g, b) = irradiance.map(e => math.min(1.0, e / strongest).toFloat)
    EnvMapSun(
      directionToSun = Vector[3](centroid.map(_.toFloat).toIndexedSeq*),
      halfAngleDegrees = MeasuredHalfAngles(chosen).toFloat,
      irradiance = Vector[3](irradiance.map(_.toFloat).toIndexedSeq*),
      intensity = (strongest / math.Pi).toFloat,
      color = Color(r, g, b),
      powerShare = (sums.power(chosen) / sums.totalPower).toFloat)

  /** Direction vectors and solid angles of the texels of a width x height map, per row and column. */
  private final class Geometry(val width: Int, val height: Int):
    private val theta = Array.tabulate(height)(row => (row + 0.5) / height * math.Pi)
    private val phi = Array.tabulate(width)(col => ((col + 0.5) / width - 0.5) * 2.0 * math.Pi)
    private val sinTheta = theta.map(math.sin)
    private val cosTheta = theta.map(math.cos)
    private val cosPhi = phi.map(math.cos)
    private val sinPhi = phi.map(math.sin)
    private val texelArea = (2.0 * math.Pi / width) * (math.Pi / height)

    def x(col: Int, row: Int): Double = sinTheta(row) * cosPhi(col)
    def y(row: Int): Double = cosTheta(row)
    def z(col: Int, row: Int): Double = sinTheta(row) * sinPhi(col)
    def solidAngle(row: Int): Double = sinTheta(row) * texelArea

  private def luminance(image: HdrImage, texel: Int): Double =
    val i = texel * Channels
    LumaWeights(0) * image.rgb(i) + LumaWeights(1) * image.rgb(i + 1) + LumaWeights(2) * image.rgb(i + 2)

  /** The luminance at and above which a texel is among the brightest `BrightestFraction`: the
    * `1 - BrightestFraction` quantile, interpolated linearly between the two nearest values.
    */
  private def brightThreshold(image: HdrImage): Double =
    val count = image.width * image.height
    val position = (1.0 - BrightestFraction) * (count - 1)
    val below = math.floor(position).toInt
    val above = math.min(count - 1, math.ceil(position).toInt)
    val keep = count - below  // the values from index `below` of the sorted ones upward
    val brightest = new java.util.PriorityQueue[java.lang.Double](keep)  // a min-heap
    (0 until count).foreach: texel =>
      val l = luminance(image, texel)
      if brightest.size < keep then brightest.add(l)
      else if l > brightest.peek then
        brightest.poll()
        brightest.add(l)
    val ascending = brightest.toArray(new Array[java.lang.Double](0)).map(_.doubleValue).sorted
    ascending(0) + (position - below) * (ascending(above - below) - ascending(0))

  /** Unit direction to the luminance centroid of the texels at or above the threshold. */
  private def sunDirection(image: HdrImage, geometry: Geometry): Array[Double] =
    val threshold = brightThreshold(image)
    val sum = new Array[Double](Channels)
    val weights = new Array[Double](1)
    (0 until image.height).foreach: row =>
      (0 until image.width).foreach: col =>
        val l = luminance(image, row * image.width + col)
        if l >= threshold then
          sum(0) += l * geometry.x(col, row)
          sum(1) += l * geometry.y(row)
          sum(2) += l * geometry.z(col, row)
          weights(0) += l
    require(weights(0) > 0.0, "the environment map is black: it has no sun")
    val length = math.sqrt(sum.map(c => c * c).sum)
    require(length > MinCentroidLength * weights(0), "the environment map has no distinct sun")
    sum.map(_ / length)

  /** Irradiance (per channel and as luminance) and power inside each measured half-angle, and the
    * power of the whole map.
    */
  private final case class ConeSums(
      irradianceByAngle: Array[Array[Double]], power: Array[Double], totalPower: Double):
    def irradiance(angle: Int): Array[Double] = irradianceByAngle(angle)
    def irradianceLuminance: Array[Double] =
      irradianceByAngle.map(e => (0 until Channels).map(c => LumaWeights(c) * e(c)).sum)

  private def coneSums(image: HdrImage, geometry: Geometry, axis: Array[Double]): ConeSums =
    val cosines = MeasuredHalfAngles.map(degrees => math.cos(math.toRadians(degrees)))
    val irradiance = Array.fill(MeasuredHalfAngles.length)(new Array[Double](Channels))
    val power = new Array[Double](MeasuredHalfAngles.length)
    val total = new Array[Double](1)
    (0 until image.height).foreach: row =>
      val solidAngle = geometry.solidAngle(row)
      (0 until image.width).foreach: col =>
        val texel = row * image.width + col
        val l = luminance(image, texel)
        total(0) += l * solidAngle
        val cosine = geometry.x(col, row) * axis(0) + geometry.y(row) * axis(1) + geometry.z(col, row) * axis(2)
        cosines.indices.filter(a => cosine >= cosines(a)).foreach: a =>
          power(a) += l * solidAngle
          (0 until Channels).foreach: c =>
            irradiance(a)(c) += image.rgb(texel * Channels + c) * cosine * solidAngle
    require(total(0) > 0.0, "the environment map is black: it has no sun")
    ConeSums(irradiance, power, total(0))

  /** Index into `MeasuredHalfAngles` of the smallest candidate that doubling does not change by
    * more than `ConvergenceFraction`, else the fallback.
    */
  private def chosenHalfAngle(irradianceLuminance: Array[Double]): Int =
    CandidateHalfAngles.indices
      .find(a => irradianceLuminance(a + 1) - irradianceLuminance(a) < ConvergenceFraction * irradianceLuminance(a))
      .getOrElse(MeasuredHalfAngles.indexOf(FallbackHalfAngle))
