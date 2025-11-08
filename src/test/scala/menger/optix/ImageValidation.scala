package menger.optix

import ThresholdConstants.*
import scala.math.{abs, sqrt}

/**
 * Functional image validation helper object.
 *
 * This implementation eliminates all mutable variables (var) and imperative
 * loops, using functional programming patterns instead (map, filter, fold, etc.).
 */
object ImageValidation:

  // ========== Helper Types ==========

  case class RGB(r: Int, g: Int, b: Int):
    def brightness: Int = (r + g + b) / 3
    def max: Int = math.max(r, math.max(g, b))
    def min: Int = math.min(r, math.min(g, b))
    def isGreen: Boolean = (g > r + 30) && (g > b + 30)

  case class Point(x: Int, y: Int)

  // ========== Image Size Calculations ==========

  // Calculate expected byte array size for RGBA image
  def imageByteSize(width: Int, height: Int): Int = width * height * 4

  // ========== Low-Level Pixel Access ==========

  // Converts signed bytes to unsigned RGB
  def getRGB(imageData: Array[Byte], pixelIndex: Int): RGB =
    val offset = pixelIndex * 4
    RGB(
      r = imageData(offset) & 0xFF,
      g = imageData(offset + 1) & 0xFF,
      b = imageData(offset + 2) & 0xFF
    )

  def getRGBAt(imageData: Array[Byte], width: Int, x: Int, y: Int): RGB =
    getRGB(imageData, y * width + x)

  def brightness(imageData: Array[Byte], pixelIndex: Int): Double =
    getRGB(imageData, pixelIndex).brightness.toDouble

  // Returns "r", "g", "b", or "gray" based on center 50% of image
  def dominantColorChannel(imageData: Array[Byte], width: Int, height: Int): String =
    // Sample center 50% of image
    val startX = width / 4
    val endX = 3 * width / 4
    val startY = height / 4
    val endY = 3 * height / 4

    // Collect all RGB values in center region
    val rgbValues = for
      y <- startY until endY
      x <- startX until endX
    yield getRGBAt(imageData, width, x, y)

    // Calculate averages functionally
    val count = rgbValues.length
    val avgR = rgbValues.map(_.r).sum.toDouble / count
    val avgG = rgbValues.map(_.g).sum.toDouble / count
    val avgB = rgbValues.map(_.b).sum.toDouble / count

    val maxChannel = math.max(avgR, math.max(avgG, avgB))
    val minChannel = math.min(avgR, math.min(avgG, avgB))

    // If difference is small, it's grayscale
    if (maxChannel - minChannel < GRAYSCALE_CHANNEL_TOLERANCE) then "gray"
    else if (avgR == maxChannel) then "r"
    else if (avgG == maxChannel) then "g"
    else "b"

  // ========== Pixel Access Helpers ==========

  def getCenterPixel(imageData: Array[Byte], width: Int, height: Int): RGB =
    val centerIdx = (height / 2) * width + (width / 2)
    getRGB(imageData, centerIdx)

  // ========== Sphere Detection ==========

  def spherePixelArea(
    imageData: Array[Byte],
    width: Int,
    height: Int,
    bgThreshold: Int = 30
  ): Int =
    // Generate all pixel indices
    val allPixels = 0 until (width * height)

    // Count green pixels (sphere detection logic)
    allPixels.count { idx =>
      getRGB(imageData, idx).isGreen
    }

  /**
   * Detect sphere center position in image coordinates.
   *
   * Functional implementation using fold to accumulate weighted sum.
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @return (centerX, centerY) in pixels
   */
  def detectSphereCenter(
    imageData: Array[Byte],
    width: Int,
    height: Int
  ): (Int, Int) =
    // All pixel coordinates with their indices
    val pixelCoords = for
      y <- 0 until height
      x <- 0 until width
    yield (x, y, y * width + x)

    // Filter for green pixels and accumulate weighted sum
    val (sumX, sumY, count) = pixelCoords.foldLeft((0.0, 0.0, 0)) {
      case ((accX, accY, accCount), (x, y, idx)) =>
        val rgb = getRGB(imageData, idx)
        if rgb.isGreen then
          (accX + x, accY + y, accCount + 1)
        else
          (accX, accY, accCount)
    }

    if count == 0 then (width / 2, height / 2)
    else ((sumX / count).toInt, (sumY / count).toInt)

  /**
   * Estimate sphere radius in pixels.
   *
   * Functional implementation using tail recursion for edge finding.
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @return estimated radius in pixels
   */
  def estimateSphereRadius(
    imageData: Array[Byte],
    width: Int,
    height: Int
  ): Double =
    val (centerX, centerY) = detectSphereCenter(imageData, width, height)

    // Find edge pixels in a direction using tail recursion
    def findEdge(dx: Int, dy: Int): Int =
      @scala.annotation.tailrec
      def search(dist: Int): Int =
        val x = centerX + dx * dist
        val y = centerY + dy * dist

        if x < 0 || x >= width || y < 0 || y >= height then
          dist
        else if !getRGBAt(imageData, width, x, y).isGreen then
          dist
        else
          search(dist + 1)

      search(0)

    // Sample in 4 directions and average
    val distances = List(
      findEdge(1, 0),   // right
      findEdge(-1, 0),  // left
      findEdge(0, 1),   // down
      findEdge(0, -1)   // up
    )

    distances.sum / 4.0

  // ========== Brightness Analysis ==========

  def brightnessStdDev(imageData: Array[Byte], width: Int, height: Int): Double =
    val numPixels = width * height
    val brightnesses = (0 until numPixels).map(i => getRGB(imageData, i).brightness)

    val mean = brightnesses.sum.toDouble / numPixels
    val variance = brightnesses.map(b => math.pow(b - mean, 2)).sum / numPixels
    math.sqrt(variance)

  /**
   * Measure average brightness around sphere edges.
   * Validates Fresnel effect (edges should be brighter at grazing angles).
   *
   * Functional implementation using map/filter/average.
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @return average brightness (0-255) of edge pixels
   */
  def edgeBrightness(imageData: Array[Byte], width: Int, height: Int): Double =
    val (centerX, centerY) = detectSphereCenter(imageData, width, height)
    val radius = estimateSphereRadius(imageData, width, height)
    val sampleRadius = (radius * 0.8).toInt

    // Generate 16 sample angles
    val angles = 0 until 360 by 22

    // Map each angle to brightness value (if in bounds)
    val brightnessSamples = angles.flatMap { angle =>
      val radians = angle * math.Pi / 180.0
      val x = (centerX + sampleRadius * math.cos(radians)).toInt
      val y = (centerY + sampleRadius * math.sin(radians)).toInt

      if x >= 0 && x < width && y >= 0 && y < height then
        Some(getRGBAt(imageData, width, x, y).brightness)
      else
        None
    }

    if brightnessSamples.isEmpty then 0.0
    else brightnessSamples.sum.toDouble / brightnessSamples.length

  /**
   * Measure brightness gradient from center to edges.
   * Positive gradient = center brighter (absorption).
   * Negative gradient = edges brighter (Fresnel reflection).
   *
   * Functional implementation using flatMap/average.
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @return centerBrightness - edgeBrightness
   */
  def brightnessGradient(
    imageData: Array[Byte],
    width: Int,
    height: Int
  ): Double =
    val (centerX, centerY) = detectSphereCenter(imageData, width, height)

    // Sample center region (11x11 box: -5 to +5)
    val centerSamples = for
      dy <- -5 to 5
      dx <- -5 to 5
      x = centerX + dx
      y = centerY + dy
      if x >= 0 && x < width && y >= 0 && y < height
    yield getRGBAt(imageData, width, x, y).brightness

    val centerBrightness =
      if centerSamples.isEmpty then 0.0
      else centerSamples.sum.toDouble / centerSamples.length

    val edgeBright = edgeBrightness(imageData, width, height)

    centerBrightness - edgeBright

  // ========== Color Analysis ==========

  /**
   * Measure R:G:B channel ratios across the entire image.
   * Validates color rendering.
   *
   * Functional implementation using fold to accumulate sums.
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @return (rRatio, gRatio, bRatio) normalized to sum=1.0
   */
  def colorChannelRatio(
    imageData: Array[Byte],
    width: Int,
    height: Int
  ): (Double, Double, Double) =
    val allPixels = 0 until (width * height)

    // Accumulate RGB sums using fold
    val (rSum, gSum, bSum) = allPixels.foldLeft((0L, 0L, 0L)) {
      case ((accR, accG, accB), idx) =>
        val rgb = getRGB(imageData, idx)
        (accR + rgb.r, accG + rgb.g, accB + rgb.b)
    }

    val total = (rSum + gSum + bSum).toDouble
    if total == 0 then (0.0, 0.0, 0.0)
    else (rSum / total, gSum / total, bSum / total)

  // ========== Background/Plane Detection ==========

  /**
   * Detect if checkered plane pattern is visible in the image.
   * Indicates background visibility through transparency.
   *
   * Functional implementation using map/variance calculation.
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @return true if checkered pattern detected
   */
  def backgroundVisibility(
    imageData: Array[Byte],
    width: Int,
    height: Int
  ): Boolean =
    // Sample top-left corner (20x20 region)
    val samples = for
      y <- 0 until math.min(20, height)
      x <- 0 until math.min(20, width)
    yield getRGBAt(imageData, width, x, y).brightness

    if samples.isEmpty then false
    else
      // Calculate variance
      val mean = samples.sum.toDouble / samples.length
      val variance = samples.map(s => (s - mean) * (s - mean)).sum / samples.length
      variance > CHECKERED_PATTERN_MIN_VARIANCE

  /**
   * Check if plane is visible in specified region of the image.
   *
   * Functional implementation using map/variance calculation.
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @param region "top" or "bottom"
   * @return true if checkered pattern detected in the region
   */
  def planeVisibility(
    imageData: Array[Byte],
    width: Int,
    height: Int,
    region: String
  ): Boolean =
    val (yStart, yEnd) = region match
      case "top"    => (0, height / 4)
      case "bottom" => (3 * height / 4, height)
      case _        => (0, 0)

    // Sample every other pixel in the region for efficiency
    val samples = for
      y <- yStart until yEnd
      x <- 0 until width by 2
      if x >= 0 && x < width && y >= 0 && y < height
    yield getRGBAt(imageData, width, x, y).brightness

    if samples.isEmpty then false
    else
      // Plane pattern has moderate variance
      val mean = samples.sum.toDouble / samples.length
      val variance = samples.map(s => (s - mean) * (s - mean)).sum / samples.length
      variance > PLANE_PATTERN_MIN_VARIANCE
