package menger.optix

import scala.math.{abs, sqrt}

/**
 * Helper object for validating rendered OptiX images.
 *
 * Provides plausibility checks for geometric, optical, and color properties
 * without requiring pixel-perfect reference images.
 */
object ImageValidation:

  /**
   * Count pixels that differ significantly from background.
   * Used to detect sphere silhouette and estimate size.
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @param bgThreshold minimum color channel difference to consider non-background
   * @return number of non-background pixels
   */
  def spherePixelArea(
    imageData: Array[Byte],
    width: Int,
    height: Int,
    bgThreshold: Int = 30
  ): Int =
    var count = 0
    for y <- 0 until height do
      for x <- 0 until width do
        val idx = y * width + x
        val offset = idx * 4
        val r = imageData(offset) & 0xFF
        val g = imageData(offset + 1) & 0xFF
        val b = imageData(offset + 2) & 0xFF

        // Background is checkered gray (20-120)
        // Sphere has varying shading (channels differ), distinct color, or is brighter than background
        val channelVariance = abs(r - g) + abs(g - b) + abs(r - b)
        val avgBrightness = (r + g + b) / 3

        // Non-background if channels vary OR significantly brighter than background (>140)
        if channelVariance > bgThreshold || avgBrightness > 140 then
          count += 1

    count

  /**
   * Measure average brightness around sphere edges.
   * Validates Fresnel effect (edges should be brighter at grazing angles).
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @return average brightness (0-255) of edge pixels
   */
  def edgeBrightness(imageData: Array[Byte], width: Int, height: Int): Double =
    val (centerX, centerY) = detectSphereCenter(imageData, width, height)
    val radius = estimateSphereRadius(imageData, width, height)

    // Sample pixels in a ring around the sphere edge
    val sampleRadius = (radius * 0.8).toInt // Sample at 80% of radius
    val samples = scala.collection.mutable.ListBuffer[Int]()

    // Sample 16 points around the circle
    for angle <- 0 until 360 by 22 do
      val radians = angle * math.Pi / 180.0
      val x = (centerX + sampleRadius * math.cos(radians)).toInt
      val y = (centerY + sampleRadius * math.sin(radians)).toInt

      if x >= 0 && x < width && y >= 0 && y < height then
        val idx = y * width + x
        val offset = idx * 4
        val r = imageData(offset) & 0xFF
        val g = imageData(offset + 1) & 0xFF
        val b = imageData(offset + 2) & 0xFF
        samples += (r + g + b) / 3

    if samples.isEmpty then 0.0 else samples.sum.toDouble / samples.length

  /**
   * Detect if checkered plane pattern is visible in the image.
   * Indicates background visibility through transparency.
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @return true if checkered pattern detected
   */
  def backgroundVisibility(imageData: Array[Byte], width: Int, height: Int): Boolean =
    // Sample corners and edges (likely to be background)
    val samples = scala.collection.mutable.ListBuffer[Int]()

    // Top-left corner
    for y <- 0 until math.min(20, height) do
      for x <- 0 until math.min(20, width) do
        val idx = y * width + x
        val offset = idx * 4
        val r = imageData(offset) & 0xFF
        val g = imageData(offset + 1) & 0xFF
        val b = imageData(offset) & 0xFF
        samples += (r + g + b) / 3

    // Check for alternating pattern (high variance indicates checkered background)
    if samples.isEmpty then false
    else
      val mean = samples.sum.toDouble / samples.length
      val variance = samples.map(s => (s - mean) * (s - mean)).sum / samples.length
      variance > 1000 // Checkered pattern has high variance

  /**
   * Measure R:G:B channel ratios across the entire image.
   * Validates color rendering.
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
    var rSum = 0L
    var gSum = 0L
    var bSum = 0L

    for idx <- 0 until (width * height) do
      val offset = idx * 4
      rSum += (imageData(offset) & 0xFF)
      gSum += (imageData(offset + 1) & 0xFF)
      bSum += (imageData(offset + 2) & 0xFF)

    val total = (rSum + gSum + bSum).toDouble
    if total == 0 then (0.0, 0.0, 0.0)
    else (rSum / total, gSum / total, bSum / total)

  /**
   * Measure brightness gradient from center to edges.
   * Positive gradient = center brighter (absorption).
   * Negative gradient = edges brighter (Fresnel reflection).
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @return centerBrightness - edgeBrightness
   */
  def brightnessGradient(imageData: Array[Byte], width: Int, height: Int): Double =
    val (centerX, centerY) = detectSphereCenter(imageData, width, height)

    // Sample center region (10x10 box)
    val centerSamples = scala.collection.mutable.ListBuffer[Int]()
    for dy <- -5 to 5 do
      for dx <- -5 to 5 do
        val x = centerX + dx
        val y = centerY + dy
        if x >= 0 && x < width && y >= 0 && y < height then
          val idx = y * width + x
          val offset = idx * 4
          val r = imageData(offset) & 0xFF
          val g = imageData(offset + 1) & 0xFF
          val b = imageData(offset + 2) & 0xFF
          centerSamples += (r + g + b) / 3

    val centerBrightness =
      if centerSamples.isEmpty then 0.0
      else centerSamples.sum.toDouble / centerSamples.length

    val edgeBright = edgeBrightness(imageData, width, height)

    centerBrightness - edgeBright

  /**
   * Check if plane is visible in specified region of the image.
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

    val samples = scala.collection.mutable.ListBuffer[Int]()

    for y <- yStart until yEnd do
      for x <- 0 until width by 2 do // Sample every other pixel for efficiency
        if y >= 0 && y < height && x >= 0 && x < width then
          val idx = y * width + x
          val offset = idx * 4
          val r = imageData(offset) & 0xFF
          val g = imageData(offset + 1) & 0xFF
          val b = imageData(offset + 2) & 0xFF
          samples += (r + g + b) / 3

    if samples.isEmpty then false
    else
      // Checkered pattern has high variance
      val mean = samples.sum.toDouble / samples.length
      val variance = samples.map(s => (s - mean) * (s - mean)).sum / samples.length
      variance > 500

  /**
   * Detect sphere center position in image coordinates.
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @return (centerX, centerY) in pixels
   */
  def detectSphereCenter(imageData: Array[Byte], width: Int, height: Int): (Int, Int) =
    var sumX = 0.0
    var sumY = 0.0
    var count = 0

    for y <- 0 until height do
      for x <- 0 until width do
        val idx = y * width + x
        val offset = idx * 4
        val r = imageData(offset) & 0xFF
        val g = imageData(offset + 1) & 0xFF
        val b = imageData(offset + 2) & 0xFF

        // Detect non-background pixels (background is checkered gray 20-120)
        val channelVariance = abs(r - g) + abs(g - b) + abs(r - b)
        val avgBrightness = (r + g + b) / 3

        if channelVariance > 30 || avgBrightness > 140 then
          sumX += x
          sumY += y
          count += 1

    if count == 0 then (width / 2, height / 2)
    else ((sumX / count).toInt, (sumY / count).toInt)

  /**
   * Estimate sphere radius in pixels.
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @return estimated radius in pixels
   */
  def estimateSphereRadius(imageData: Array[Byte], width: Int, height: Int): Double =
    val (centerX, centerY) = detectSphereCenter(imageData, width, height)

    // Find edge pixels in 4 directions
    def findEdge(dx: Int, dy: Int): Int =
      var dist = 0
      var found = false
      while !found && dist < math.max(width, height) do
        val x = centerX + dx * dist
        val y = centerY + dy * dist

        if x < 0 || x >= width || y < 0 || y >= height then
          found = true
        else
          val idx = y * width + x
          val offset = idx * 4
          val r = imageData(offset) & 0xFF
          val g = imageData(offset + 1) & 0xFF
          val b = imageData(offset + 2) & 0xFF
          val channelVariance = abs(r - g) + abs(g - b) + abs(r - b)
          val avgBrightness = (r + g + b) / 3

          // Hit background if low variance and dark (checkered gray 20-120)
          if channelVariance <= 30 && avgBrightness <= 140 then
            found = true

        if !found then dist += 1

      dist

    // Sample in 4 directions and average
    val right = findEdge(1, 0)
    val left = findEdge(-1, 0)
    val down = findEdge(0, 1)
    val up = findEdge(0, -1)

    (right + left + up + down) / 4.0
