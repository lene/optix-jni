package menger.optix

import menger.common.ImageSize
import org.scalatest.matchers.{BeMatcher, MatchResult, Matcher}
import ThresholdConstants.*


object ImageMatchers:

  
  private def imageMatchResult(
    matches: Boolean,
    rawFailureMessage: String,
    rawNegatedFailureMessage: String,
    width: Int,
    height: Int
  ): MatchResult =
    MatchResult(
      matches,
      s"$rawFailureMessage (image: ${width}x${height})",
      s"$rawNegatedFailureMessage (image: ${width}x${height})"
    )

  // ========== Image Size Matchers ==========

  
  def haveValidRGBASize(size: ImageSize): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val expectedSize = ImageValidation.imageByteSize(size)
      val actualSize = imageData.length
      val matches = actualSize == expectedSize

      MatchResult(
        matches,
        s"Image size was $actualSize bytes but expected $expectedSize bytes for ${size.width}x${size.height} RGBA",
        s"Image size was $expectedSize bytes for ${size.width}x${size.height} RGBA as expected"
      )
    }

  // ========== Sphere Center Matchers ==========

  
  def haveSphereCenterAt(
    expectedX: Int,
    expectedY: Int,
    size: ImageSize,
    tolerance: Int = SPHERE_CENTER_TOLERANCE
  ): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val center = ImageValidation.detectSphereCenter(imageData, size)
      val xMatch = math.abs(center.x - expectedX) <= tolerance
      val yMatch = math.abs(center.y - expectedY) <= tolerance
      val matches = xMatch && yMatch

      imageMatchResult(
        matches,
        s"Sphere center at (${center.x}, ${center.y}) was not within $tolerance pixels of expected ($expectedX, $expectedY)",
        s"Sphere center at (${center.x}, ${center.y}) was within $tolerance pixels of ($expectedX, $expectedY) but should not have been",
        size.width,
        size.height
      )
    }

  
  def haveSphereCenteredInImage(
    size: ImageSize,
    tolerance: Int = SPHERE_CENTER_TOLERANCE
  ): Matcher[Array[Byte]] =
    haveSphereCenterAt(size.width / 2, size.height / 2, size, tolerance)

  // ========== Sphere Area Matchers ==========

  
  def haveSphereAreaBetween(
    minArea: Int,
    maxArea: Int,
    size: ImageSize
  ): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val area = ImageValidation.spherePixelArea(imageData, size)
      val matches = area >= minArea && area <= maxArea

      imageMatchResult(
        matches,
        s"Sphere area $area was not between $minArea and $maxArea pixels",
        s"Sphere area $area was between $minArea and $maxArea pixels but should not have been",
        size.width,
        size.height
      )
    }

  
  def haveSmallSphereArea(size: ImageSize): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val area = ImageValidation.spherePixelArea(imageData, size)
      val matches = area < SMALL_SPHERE_MAX_AREA

      imageMatchResult(
        matches,
        s"Sphere area $area was not less than $SMALL_SPHERE_MAX_AREA pixels (small sphere threshold)",
        s"Sphere area $area was less than $SMALL_SPHERE_MAX_AREA pixels but should not have been",
        size.width,
        size.height
      )
    }


  def haveMediumSphereArea(size: ImageSize): Matcher[Array[Byte]] =
    haveSphereAreaBetween(MEDIUM_SPHERE_MIN_AREA, MEDIUM_SPHERE_MAX_AREA, size)


  def haveLargeSphereArea(size: ImageSize): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val area = ImageValidation.spherePixelArea(imageData, size)
      val matches = area > LARGE_SPHERE_MIN_AREA

      imageMatchResult(
        matches,
        s"Sphere area $area was not greater than $LARGE_SPHERE_MIN_AREA pixels (large sphere threshold)",
        s"Sphere area $area was greater than $LARGE_SPHERE_MIN_AREA pixels but should not have been",
        size.width,
        size.height
      )
    }

  // ========== Color Matchers ==========

  
  def haveDominantChannel(
    expectedChannel: String,
    size: ImageSize,
    tolerance: Double = GRAYSCALE_CHANNEL_TOLERANCE
  ): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val actualChannel = ImageValidation.dominantColorChannel(
        imageData,
        size.width,
        size.height,
        tolerance
      )
      val matches = actualChannel == expectedChannel

      imageMatchResult(
        matches,
        s"Dominant color channel was '$actualChannel' but expected '$expectedChannel'",
        s"Dominant color channel was '$expectedChannel' but should not have been",
        size.width,
        size.height
      )
    }

  
  def beRedDominant(size: ImageSize, tolerance: Double = GRAYSCALE_CHANNEL_TOLERANCE): Matcher[Array[Byte]] =
    haveDominantChannel("r", size, tolerance)


  def beGreenDominant(size: ImageSize, tolerance: Double = GRAYSCALE_CHANNEL_TOLERANCE): Matcher[Array[Byte]] =
    haveDominantChannel("g", size, tolerance)


  def beBlueDominant(size: ImageSize, tolerance: Double = GRAYSCALE_CHANNEL_TOLERANCE): Matcher[Array[Byte]] =
    haveDominantChannel("b", size, tolerance)


  def beGrayscale(size: ImageSize): Matcher[Array[Byte]] =
    haveDominantChannel("gray", size)


  def haveColorRatio(
    expectedR: Double,
    expectedG: Double,
    expectedB: Double,
    size: ImageSize,
    tolerance: Double = 0.1
  ): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val ratio = ImageValidation.colorChannelRatio(imageData, size)
      val rMatch = math.abs(ratio.r - expectedR) <= tolerance
      val gMatch = math.abs(ratio.g - expectedG) <= tolerance
      val bMatch = math.abs(ratio.b - expectedB) <= tolerance
      val matches = rMatch && gMatch && bMatch

      imageMatchResult(
        matches,
        f"Color ratio (R=${ratio.r}%.3f, G=${ratio.g}%.3f, B=${ratio.b}%.3f) was not within $tolerance of expected (R=$expectedR%.3f, G=$expectedG%.3f, B=$expectedB%.3f)",
        f"Color ratio (R=${ratio.r}%.3f, G=${ratio.g}%.3f, B=${ratio.b}%.3f) was within $tolerance of (R=$expectedR%.3f, G=$expectedG%.3f, B=$expectedB%.3f) but should not have been",
        size.width,
        size.height
      )
    }

  // ========== Brightness Matchers ==========

  
  def haveBrightnessStdDevGreaterThan(
    threshold: Double,
    size: ImageSize
  ): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val stdDev = ImageValidation.brightnessStdDev(imageData, size)
      val matches = stdDev > threshold

      imageMatchResult(
        matches,
        f"Brightness standard deviation $stdDev%.2f was not greater than $threshold%.2f",
        f"Brightness standard deviation $stdDev%.2f was greater than $threshold%.2f but should not have been",
        size.width,
        size.height
      )
    }

  
  def showGlassRefraction(size: ImageSize): Matcher[Array[Byte]] =
    haveBrightnessStdDevGreaterThan(MIN_GLASS_REFRACTION_STDDEV, size)


  def showWaterRefraction(size: ImageSize): Matcher[Array[Byte]] =
    haveBrightnessStdDevGreaterThan(MIN_WATER_REFRACTION_STDDEV, size)


  def showDiamondRefraction(size: ImageSize): Matcher[Array[Byte]] =
    haveBrightnessStdDevGreaterThan(MIN_DIAMOND_REFRACTION_STDDEV, size)

  // ========== Background/Plane Visibility Matchers ==========


  def showBackground(size: ImageSize): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val visible = ImageValidation.isBackgroundVisible(imageData, size)

      imageMatchResult(
        visible,
        "Background checkered pattern was not detected (sphere might be opaque)",
        "Background checkered pattern was detected but should not have been",
        size.width,
        size.height
      )
    }


  def showPlaneInRegion(
    region: String,
    size: ImageSize
  ): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val visible = ImageValidation.isPlaneVisible(imageData, size, region)

      imageMatchResult(
        visible,
        s"Checkered plane pattern was not detected in $region region",
        s"Checkered plane pattern was detected in $region region but should not have been",
        size.width,
        size.height
      )
    }
