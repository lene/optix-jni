package menger.optix

import scala.math.abs

import menger.common.ImageSize
import menger.optix.TestUtilities.Region
import menger.optix.TestUtilities.regionBrightness


object ShadowValidation:

  export TestUtilities.Region
  export TestUtilities.regionBrightness

  def brightnessContrast(
    imageData: Array[Byte],
    size: ImageSize,
    region1: Region,
    region2: Region
  ): Double =
    val b1 = regionBrightness(imageData, size, region1)
    val b2 = regionBrightness(imageData, size, region2)
    if b2 == 0.0 then 0.0 else b1 / b2


  def detectDarkestRegion(
    imageData: Array[Byte],
    size: ImageSize,
    gridSize: Int = 5
  ): Region =
    val yStart = size.height / 2
    val regionWidth = size.width / gridSize
    val regionHeight = (size.height - yStart) / gridSize

    val regions = for
      gy <- 0 until gridSize
      gx <- 0 until gridSize
      x0 = gx * regionWidth
      y0 = yStart + gy * regionHeight
      x1 = x0 + regionWidth
      y1 = y0 + regionHeight
      region = Region(x0, y0, x1, y1)
    yield region

    regions.minBy(region => regionBrightness(imageData, size, region))


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

  
  def shadowIntensity(litBrightness: Double, shadowedBrightness: Double): Double =
    if litBrightness == 0.0 then 0.0
    else 1.0 - (shadowedBrightness / litBrightness)

  
  def expectedShadowIntensity(alpha: Float): Double =
    // Maximum shadow intensity (opaque sphere) is about 0.74 based on shader constants
    val maxShadowIntensity = 0.74
    alpha * maxShadowIntensity
