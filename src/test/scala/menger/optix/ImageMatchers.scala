package menger.optix

import org.scalatest.matchers.{BeMatcher, MatchResult, Matcher}
import ThresholdConstants.*

/**
 * Custom ScalaTest matchers for image validation.
 *
 * Provides fluent, readable assertions for rendered OptiX images.
 * Eliminates repetitive validation code and makes tests self-documenting.
 *
 * Usage:
 * {{{
 * val imageData = renderer.render(800, 600)
 * imageData should haveSphereCenterAt(400, 300, tolerance = 30)
 * imageData should haveBrightnessGreaterThan(10.0)
 * imageData should haveGreenAsDominantColor
 * }}}
 */
object ImageMatchers:

  /**
   * Custom MatchResult that includes image dimensions for better error messages.
   */
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

  /**
   * Matcher for valid RGBA image size.
   *
   * Validates that byte array has correct size for RGBA format.
   *
   * Example: `imageData should haveValidRGBASize(800, 600)`
   */
  def haveValidRGBASize(width: Int, height: Int): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val expectedSize = ImageValidation.imageByteSize(width, height)
      val actualSize = imageData.length
      val matches = actualSize == expectedSize

      MatchResult(
        matches,
        s"Image size was $actualSize bytes but expected $expectedSize bytes for ${width}x${height} RGBA",
        s"Image size was $expectedSize bytes for ${width}x${height} RGBA as expected"
      )
    }

  // ========== Sphere Center Matchers ==========

  /**
   * Matcher for sphere center position.
   *
   * Example: `imageData should haveSphereCenterAt(400, 300, width, height)`
   */
  def haveSphereCenterAt(
    expectedX: Int,
    expectedY: Int,
    width: Int,
    height: Int,
    tolerance: Int = SPHERE_CENTER_TOLERANCE
  ): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val (actualX, actualY) = ImageValidation.detectSphereCenter(imageData, width, height)
      val xMatch = math.abs(actualX - expectedX) <= tolerance
      val yMatch = math.abs(actualY - expectedY) <= tolerance
      val matches = xMatch && yMatch

      imageMatchResult(
        matches,
        s"Sphere center at ($actualX, $actualY) was not within $tolerance pixels of expected ($expectedX, $expectedY)",
        s"Sphere center at ($actualX, $actualY) was within $tolerance pixels of ($expectedX, $expectedY) but should not have been",
        width,
        height
      )
    }

  /**
   * Matcher for sphere center near image center.
   *
   * Example: `imageData should haveSphereCenteredInImage(width, height)`
   */
  def haveSphereCenteredInImage(
    width: Int,
    height: Int,
    tolerance: Int = SPHERE_CENTER_TOLERANCE
  ): Matcher[Array[Byte]] =
    haveSphereCenterAt(width / 2, height / 2, width, height, tolerance)

  // ========== Sphere Area Matchers ==========

  /**
   * Matcher for sphere pixel area.
   *
   * Example: `imageData should haveSphereAreaBetween(500, 15000, width, height)`
   */
  def haveSphereAreaBetween(
    minArea: Int,
    maxArea: Int,
    width: Int,
    height: Int
  ): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val area = ImageValidation.spherePixelArea(imageData, width, height)
      val matches = area >= minArea && area <= maxArea

      imageMatchResult(
        matches,
        s"Sphere area $area was not between $minArea and $maxArea pixels",
        s"Sphere area $area was between $minArea and $maxArea pixels but should not have been",
        width,
        height
      )
    }

  /**
   * Matcher for small sphere area.
   *
   * Example: `imageData should haveSmallSphereArea(width, height)`
   */
  def haveSmallSphereArea(width: Int, height: Int): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val area = ImageValidation.spherePixelArea(imageData, width, height)
      val matches = area < SMALL_SPHERE_MAX_AREA

      imageMatchResult(
        matches,
        s"Sphere area $area was not less than $SMALL_SPHERE_MAX_AREA pixels (small sphere threshold)",
        s"Sphere area $area was less than $SMALL_SPHERE_MAX_AREA pixels but should not have been",
        width,
        height
      )
    }

  /**
   * Matcher for medium sphere area.
   *
   * Example: `imageData should haveMediumSphereArea(width, height)`
   */
  def haveMediumSphereArea(width: Int, height: Int): Matcher[Array[Byte]] =
    haveSphereAreaBetween(MEDIUM_SPHERE_MIN_AREA, MEDIUM_SPHERE_MAX_AREA, width, height)

  /**
   * Matcher for large sphere area.
   *
   * Example: `imageData should haveLargeSphereArea(width, height)`
   */
  def haveLargeSphereArea(width: Int, height: Int): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val area = ImageValidation.spherePixelArea(imageData, width, height)
      val matches = area > LARGE_SPHERE_MIN_AREA

      imageMatchResult(
        matches,
        s"Sphere area $area was not greater than $LARGE_SPHERE_MIN_AREA pixels (large sphere threshold)",
        s"Sphere area $area was greater than $LARGE_SPHERE_MIN_AREA pixels but should not have been",
        width,
        height
      )
    }

  // ========== Color Matchers ==========

  /**
   * Matcher for dominant color channel.
   *
   * Example: `imageData should haveDominantChannel("g", width, height)`
   */
  def haveDominantChannel(
    expectedChannel: String,
    width: Int,
    height: Int
  ): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val actualChannel = ImageValidation.dominantColorChannel(
        imageData,
        width,
        height
      )
      val matches = actualChannel == expectedChannel

      imageMatchResult(
        matches,
        s"Dominant color channel was '$actualChannel' but expected '$expectedChannel'",
        s"Dominant color channel was '$expectedChannel' but should not have been",
        width,
        height
      )
    }

  /**
   * Matcher for red-dominant image.
   *
   * Example: `imageData should beRedDominant(width, height)`
   */
  def beRedDominant(width: Int, height: Int): Matcher[Array[Byte]] =
    haveDominantChannel("r", width, height)

  /**
   * Matcher for green-dominant image.
   *
   * Example: `imageData should beGreenDominant(width, height)`
   */
  def beGreenDominant(width: Int, height: Int): Matcher[Array[Byte]] =
    haveDominantChannel("g", width, height)

  /**
   * Matcher for blue-dominant image.
   *
   * Example: `imageData should beblueDominant(width, height)`
   */
  def beBlueDominant(width: Int, height: Int): Matcher[Array[Byte]] =
    haveDominantChannel("b", width, height)

  /**
   * Matcher for grayscale image.
   *
   * Example: `imageData should beGrayscale(width, height)`
   */
  def beGrayscale(width: Int, height: Int): Matcher[Array[Byte]] =
    haveDominantChannel("gray", width, height)

  /**
   * Matcher for color channel ratio.
   *
   * Example: `imageData should haveColorRatio(0.33, 0.50, 0.17, width, height, tolerance = 0.1)`
   */
  def haveColorRatio(
    expectedR: Double,
    expectedG: Double,
    expectedB: Double,
    width: Int,
    height: Int,
    tolerance: Double = 0.1
  ): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val (r, g, b) = ImageValidation.colorChannelRatio(imageData, width, height)
      val rMatch = math.abs(r - expectedR) <= tolerance
      val gMatch = math.abs(g - expectedG) <= tolerance
      val bMatch = math.abs(b - expectedB) <= tolerance
      val matches = rMatch && gMatch && bMatch

      imageMatchResult(
        matches,
        f"Color ratio (R=$r%.3f, G=$g%.3f, B=$b%.3f) was not within $tolerance of expected (R=$expectedR%.3f, G=$expectedG%.3f, B=$expectedB%.3f)",
        f"Color ratio (R=$r%.3f, G=$g%.3f, B=$b%.3f) was within $tolerance of (R=$expectedR%.3f, G=$expectedG%.3f, B=$expectedB%.3f) but should not have been",
        width,
        height
      )
    }

  // ========== Brightness Matchers ==========

  /**
   * Matcher for brightness standard deviation (refraction indicator).
   *
   * Example: `imageData should haveBrightnessStdDevGreaterThan(15.0, width, height)`
   */
  def haveBrightnessStdDevGreaterThan(
    threshold: Double,
    width: Int,
    height: Int
  ): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val stdDev = ImageValidation.brightnessStdDev(imageData, width, height)
      val matches = stdDev > threshold

      imageMatchResult(
        matches,
        f"Brightness standard deviation $stdDev%.2f was not greater than $threshold%.2f",
        f"Brightness standard deviation $stdDev%.2f was greater than $threshold%.2f but should not have been",
        width,
        height
      )
    }

  /**
   * Matcher for glass-like refraction (stdDev > 15.0).
   *
   * Example: `imageData should showGlassRefraction(width, height)`
   */
  def showGlassRefraction(width: Int, height: Int): Matcher[Array[Byte]] =
    haveBrightnessStdDevGreaterThan(MIN_GLASS_REFRACTION_STDDEV, width, height)

  /**
   * Matcher for water-like refraction (stdDev > 20.0).
   *
   * Example: `imageData should showWaterRefraction(width, height)`
   */
  def showWaterRefraction(width: Int, height: Int): Matcher[Array[Byte]] =
    haveBrightnessStdDevGreaterThan(MIN_WATER_REFRACTION_STDDEV, width, height)

  /**
   * Matcher for diamond-like refraction (stdDev > 25.0).
   *
   * Example: `imageData should showDiamondRefraction(width, height)`
   */
  def showDiamondRefraction(width: Int, height: Int): Matcher[Array[Byte]] =
    haveBrightnessStdDevGreaterThan(MIN_DIAMOND_REFRACTION_STDDEV, width, height)

  // ========== Background/Plane Visibility Matchers ==========

  /**
   * Matcher for background visibility.
   *
   * Example: `imageData should showBackground(width, height)`
   */
  def showBackground(width: Int, height: Int): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val visible = ImageValidation.backgroundVisibility(imageData, width, height)

      imageMatchResult(
        visible,
        "Background checkered pattern was not detected (sphere might be opaque)",
        "Background checkered pattern was detected but should not have been",
        width,
        height
      )
    }

  /**
   * Matcher for plane visibility in region.
   *
   * Example: `imageData should showPlaneInRegion("bottom", width, height)`
   */
  def showPlaneInRegion(
    region: String,
    width: Int,
    height: Int
  ): Matcher[Array[Byte]] =
    Matcher { imageData =>
      val visible = ImageValidation.planeVisibility(imageData, width, height, region)

      imageMatchResult(
        visible,
        s"Checkered plane pattern was not detected in $region region",
        s"Checkered plane pattern was detected in $region region but should not have been",
        width,
        height
      )
    }
