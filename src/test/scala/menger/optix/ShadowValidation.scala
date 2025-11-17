package menger.optix

import ImageValidation.*
import scala.math.{abs, sqrt}

/**
 * Shadow-specific image validation functions.
 *
 * Provides utilities for:
 * - Measuring shadow intensity in specific regions
 * - Detecting shadow positions
 * - Validating geometric correctness of shadows
 */
object ShadowValidation:

  /**
   * Define a rectangular region in image coordinates.
   *
   * @param x0 left edge (pixels)
   * @param y0 top edge (pixels)
   * @param x1 right edge (pixels)
   * @param y1 bottom edge (pixels)
   */
  case class Region(x0: Int, y0: Int, x1: Int, y1: Int):
    def width: Int = x1 - x0
    def height: Int = y1 - y0
    def area: Int = width * height
    def contains(x: Int, y: Int): Boolean =
      x >= x0 && x < x1 && y >= y0 && y < y1

  object Region:
    /** Create region from center point and radius */
    def centered(cx: Int, cy: Int, radius: Int): Region =
      Region(cx - radius, cy - radius, cx + radius, cy + radius)

    /** Bottom center region (likely shadow location for overhead light) */
    def bottomCenter(width: Int, height: Int, fraction: Double = 0.25): Region =
      val regionWidth = (width * fraction).toInt
      val regionHeight = (height * fraction).toInt
      val x0 = (width - regionWidth) / 2
      val y0 = height - regionHeight
      Region(x0, y0, x0 + regionWidth, y0 + regionHeight)

    /** Top center region (likely lit area for overhead light) */
    def topCenter(width: Int, height: Int, fraction: Double = 0.25): Region =
      val regionWidth = (width * fraction).toInt
      val regionHeight = (height * fraction).toInt
      val x0 = (width - regionWidth) / 2
      Region(x0, 0, x0 + regionWidth, regionHeight)

    /** Left side region */
    def leftSide(width: Int, height: Int, fraction: Double = 0.25): Region =
      val regionWidth = (width * fraction).toInt
      val y0 = height / 4
      val y1 = 3 * height / 4
      Region(0, y0, regionWidth, y1)

    /** Right side region */
    def rightSide(width: Int, height: Int, fraction: Double = 0.25): Region =
      val regionWidth = (width * fraction).toInt
      val y0 = height / 4
      val y1 = 3 * height / 4
      Region(width - regionWidth, y0, width, y1)

  /**
   * Measure average brightness in a specific region.
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @param region region to measure
   * @return average brightness (0-255)
   */
  def regionBrightness(
    imageData: Array[Byte],
    width: Int,
    height: Int,
    region: Region
  ): Double =
    val samples = for
      y <- region.y0 until region.y1 if y >= 0 && y < height
      x <- region.x0 until region.x1 if x >= 0 && x < width
    yield getRGBAt(imageData, width, x, y).brightness

    if samples.isEmpty then 0.0
    else samples.sum.toDouble / samples.length

  /**
   * Calculate brightness contrast ratio between two regions.
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @param region1 first region (e.g., lit area)
   * @param region2 second region (e.g., shadowed area)
   * @return contrast ratio (brightness1 / brightness2)
   */
  def brightnessContrast(
    imageData: Array[Byte],
    width: Int,
    height: Int,
    region1: Region,
    region2: Region
  ): Double =
    val b1 = regionBrightness(imageData, width, height, region1)
    val b2 = regionBrightness(imageData, width, height, region2)
    if b2 == 0.0 then 0.0 else b1 / b2

  /**
   * Detect the darkest region in the bottom half of the image.
   * This is where we expect shadows to appear for typical overhead lighting.
   *
   * @param imageData RGBA byte array
   * @param width image width
   * @param height image height
   * @param gridSize number of regions to divide bottom half into (e.g., 5x5 = 25 regions)
   * @return Region with lowest average brightness
   */
  def detectDarkestRegion(
    imageData: Array[Byte],
    width: Int,
    height: Int,
    gridSize: Int = 5
  ): Region =
    val yStart = height / 2
    val regionWidth = width / gridSize
    val regionHeight = (height - yStart) / gridSize

    val regions = for
      gy <- 0 until gridSize
      gx <- 0 until gridSize
      x0 = gx * regionWidth
      y0 = yStart + gy * regionHeight
      x1 = x0 + regionWidth
      y1 = y0 + regionHeight
      region = Region(x0, y0, x1, y1)
    yield region

    regions.minBy(region => regionBrightness(imageData, width, height, region))

  /**
   * Calculate expected shadow position based on light direction and sphere position.
   *
   * Uses simple projection: shadow is on opposite side of sphere from light.
   * For a light direction (lx, ly, lz) and sphere at (sx, sy, sz),
   * the shadow center on a plane at y=planeY is approximately:
   * shadowX = sx - lx * (sy - planeY) / ly
   * shadowZ = sz - lz * (sy - planeY) / ly
   *
   * @param sphereX sphere center X in world coordinates
   * @param sphereY sphere center Y in world coordinates
   * @param sphereZ sphere center Z in world coordinates
   * @param lightDirX light direction X (normalized)
   * @param lightDirY light direction Y (normalized)
   * @param lightDirZ light direction Z (normalized)
   * @param planeY Y-coordinate of plane
   * @return (shadowX, shadowZ) in world coordinates
   */
  def expectedShadowPosition(
    sphereX: Float,
    sphereY: Float,
    sphereZ: Float,
    lightDirX: Float,
    lightDirY: Float,
    lightDirZ: Float,
    planeY: Float
  ): (Float, Float) =
    // Avoid division by zero
    if abs(lightDirY) < 0.001f then
      (sphereX, sphereZ)
    else
      val t = (sphereY - planeY) / lightDirY
      val shadowX = sphereX - lightDirX * t
      val shadowZ = sphereZ - lightDirZ * t
      (shadowX, shadowZ)

  /**
   * Measure shadow intensity (darkness) as a fraction.
   *
   * @param litBrightness brightness in lit region (no shadow)
   * @param shadowedBrightness brightness in shadowed region
   * @return shadow intensity from 0.0 (no shadow) to 1.0 (complete darkness)
   */
  def shadowIntensity(litBrightness: Double, shadowedBrightness: Double): Double =
    if litBrightness == 0.0 then 0.0
    else 1.0 - (shadowedBrightness / litBrightness)

  /**
   * Calculate expected shadow intensity based on material alpha.
   *
   * According to the shader implementation:
   * - alpha = 0.0 (fully transparent) → shadowFactor = 0.0 → no shadow
   * - alpha = 1.0 (fully opaque) → shadowFactor = 1.0 → full shadow
   *
   * Shadow darkening formula (from shader):
   * lighting = ambient + shadowFactor * (1 - ambient) * diffuse
   *
   * For our test setup:
   * - ambient = 0.2 (AMBIENT_LIGHT_FACTOR)
   * - diffuse varies by angle but assume ~0.7 for typical setup
   *
   * Simplified for testing:
   * - Fully lit: ambient + (1 - ambient) * diffuse = 0.2 + 0.8 * 0.7 = 0.76
   * - Fully shadowed (opaque): ambient = 0.2
   * - Shadow intensity = (0.76 - 0.2) / 0.76 = 0.737 ≈ 0.74
   *
   * For semi-transparent (alpha = 0.5):
   * - shadowFactor = 0.5
   * - lighting = 0.2 + 0.5 * 0.8 * 0.7 = 0.48
   * - Shadow intensity = (0.76 - 0.48) / 0.76 = 0.368 ≈ 0.37
   *
   * General formula:
   * shadowIntensity(alpha) ≈ alpha * 0.74
   *
   * @param alpha material alpha (0.0 to 1.0)
   * @return expected shadow intensity (0.0 to ~0.74)
   */
  def expectedShadowIntensity(alpha: Float): Double =
    // Maximum shadow intensity (opaque sphere) is about 0.74 based on shader constants
    val maxShadowIntensity = 0.74
    alpha * maxShadowIntensity
