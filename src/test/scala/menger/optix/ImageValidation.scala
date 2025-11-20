package menger.optix

import menger.common.ImageSize
import ThresholdConstants.*
import scala.math.{abs, sqrt}


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
  def imageByteSize(size: ImageSize): Int = size.width * size.height * 4

  // ========== Low-Level Pixel Access ==========

  // Converts signed bytes to unsigned RGB
  def getRGB(imageData: Array[Byte], pixelIndex: Int): RGB =
    val offset = pixelIndex * 4
    RGB(
      r = imageData(offset) & 0xFF,
      g = imageData(offset + 1) & 0xFF,
      b = imageData(offset + 2) & 0xFF
    )

  def getRGBAt(imageData: Array[Byte], size: ImageSize, x: Int, y: Int): RGB =
    getRGB(imageData, y * size.width + x)

  def brightness(imageData: Array[Byte], pixelIndex: Int): Double =
    getRGB(imageData, pixelIndex).brightness.toDouble

  // Returns "r", "g", "b", or "gray" based on center 50% of image
  def dominantColorChannel(
    imageData: Array[Byte],
    width: Int,
    height: Int,
    tolerance: Double = GRAYSCALE_CHANNEL_TOLERANCE
  ): String =
    val size = ImageSize(width, height)
    // Sample center 50% of image
    val startX = width / 4
    val endX = 3 * width / 4
    val startY = height / 4
    val endY = 3 * height / 4

    // Collect all RGB values in center region
    val rgbValues = for
      y <- startY until endY
      x <- startX until endX
    yield getRGBAt(imageData, size, x, y)

    // Calculate averages functionally
    val count = rgbValues.length
    val avgR = rgbValues.map(_.r).sum.toDouble / count
    val avgG = rgbValues.map(_.g).sum.toDouble / count
    val avgB = rgbValues.map(_.b).sum.toDouble / count

    val maxChannel = math.max(avgR, math.max(avgG, avgB))
    val minChannel = math.min(avgR, math.min(avgG, avgB))

    // If difference is small, it's grayscale
    if (maxChannel - minChannel < tolerance) then "gray"
    else if (avgR == maxChannel) then "r"
    else if (avgG == maxChannel) then "g"
    else "b"

  // ========== Pixel Access Helpers ==========

  def getCenterPixel(imageData: Array[Byte], size: ImageSize): RGB =
    val centerIdx = (size.height / 2) * size.width + (size.width / 2)
    getRGB(imageData, centerIdx)

  // ========== Sphere Detection ==========

  def spherePixelArea(imageData: Array[Byte], size: ImageSize): Int =
    // Generate all pixel indices
    val allPixels = 0 until (size.width * size.height)

    // Count green pixels (sphere detection logic)
    allPixels.count { idx =>
      getRGB(imageData, idx).isGreen
    }

  def spherePixelArea(imageData: Array[Byte], size: ImageSize, bgThreshold: Int): Int =
    spherePixelArea(imageData, size)


  def detectSphereCenter(imageData: Array[Byte], size: ImageSize): (Int, Int) =
    // All pixel coordinates with their indices
    val pixelCoords = for
      y <- 0 until size.height
      x <- 0 until size.width
    yield (x, y, y * size.width + x)

    // Filter for green pixels and accumulate weighted sum
    val (sumX, sumY, count) = pixelCoords.foldLeft((0.0, 0.0, 0)) {
      case ((accX, accY, accCount), (x, y, idx)) =>
        val rgb = getRGB(imageData, idx)
        if rgb.isGreen then
          (accX + x, accY + y, accCount + 1)
        else
          (accX, accY, accCount)
    }

    if count == 0 then (size.width / 2, size.height / 2)
    else ((sumX / count).toInt, (sumY / count).toInt)


  def estimateSphereRadius(imageData: Array[Byte], size: ImageSize): Double =
    val (centerX, centerY) = detectSphereCenter(imageData, size)

    // Find edge pixels in a direction using tail recursion
    def findEdge(dx: Int, dy: Int): Int =
      @scala.annotation.tailrec
      def search(dist: Int): Int =
        val x = centerX + dx * dist
        val y = centerY + dy * dist

        if x < 0 || x >= size.width || y < 0 || y >= size.height then
          dist
        else if !getRGBAt(imageData, size, x, y).isGreen then
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

  def brightnessStdDev(imageData: Array[Byte], size: ImageSize): Double =
    val numPixels = size.width * size.height
    val brightnesses = (0 until numPixels).map(i => getRGB(imageData, i).brightness)

    val mean = brightnesses.sum.toDouble / numPixels
    val variance = brightnesses.map(b => math.pow(b - mean, 2)).sum / numPixels
    math.sqrt(variance)


  def edgeBrightness(imageData: Array[Byte], size: ImageSize): Double =
    val (centerX, centerY) = detectSphereCenter(imageData, size)
    val radius = estimateSphereRadius(imageData, size)
    val sampleRadius = (radius * 0.8).toInt

    // Generate 16 sample angles
    val angles = 0 until 360 by 22

    // Map each angle to brightness value (if in bounds)
    val brightnessSamples = angles.flatMap { angle =>
      val radians = angle * math.Pi / 180.0
      val x = (centerX + sampleRadius * math.cos(radians)).toInt
      val y = (centerY + sampleRadius * math.sin(radians)).toInt

      if x >= 0 && x < size.width && y >= 0 && y < size.height then
        Some(getRGBAt(imageData, size, x, y).brightness)
      else
        None
    }

    if brightnessSamples.isEmpty then 0.0
    else brightnessSamples.sum.toDouble / brightnessSamples.length


  def brightnessGradient(imageData: Array[Byte], size: ImageSize): Double =
    val (centerX, centerY) = detectSphereCenter(imageData, size)

    // Sample center region (11x11 box: -5 to +5)
    val centerSamples = for
      dy <- -5 to 5
      dx <- -5 to 5
      x = centerX + dx
      y = centerY + dy
      if x >= 0 && x < size.width && y >= 0 && y < size.height
    yield getRGBAt(imageData, size, x, y).brightness

    val centerBrightness =
      if centerSamples.isEmpty then 0.0
      else centerSamples.sum.toDouble / centerSamples.length

    val edgeBright = edgeBrightness(imageData, size)

    centerBrightness - edgeBright

  // ========== Color Analysis ==========


  def colorChannelRatio(imageData: Array[Byte], size: ImageSize): (Double, Double, Double) =
    val allPixels = 0 until (size.width * size.height)

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


  def backgroundVisibility(imageData: Array[Byte], size: ImageSize): Boolean =
    // Sample top-left corner (20x20 region)
    val samples = for
      y <- 0 until math.min(20, size.height)
      x <- 0 until math.min(20, size.width)
    yield getRGBAt(imageData, size, x, y).brightness

    if samples.isEmpty then false
    else
      // Calculate variance
      val mean = samples.sum.toDouble / samples.length
      val variance = samples.map(s => (s - mean) * (s - mean)).sum / samples.length
      variance > CHECKERED_PATTERN_MIN_VARIANCE


  def planeVisibility(imageData: Array[Byte], size: ImageSize, region: String): Boolean =
    val (yStart, yEnd) = region match
      case "top"    => (0, size.height / 4)
      case "bottom" => (3 * size.height / 4, size.height)
      case _        => (0, 0)

    // Sample every other pixel in the region for efficiency
    val samples = for
      y <- yStart until yEnd
      x <- 0 until size.width by 2
      if x >= 0 && x < size.width && y >= 0 && y < size.height
    yield getRGBAt(imageData, size, x, y).brightness

    if samples.isEmpty then false
    else
      // Plane pattern has moderate variance
      val mean = samples.sum.toDouble / samples.length
      val variance = samples.map(s => (s - mean) * (s - mean)).sum / samples.length
      variance > PLANE_PATTERN_MIN_VARIANCE
