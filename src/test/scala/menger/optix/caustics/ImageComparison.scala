package menger.optix.caustics

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import scala.util.Try

/** Image comparison utilities for caustics validation (C8 - Reference Match).
  *
  * Implements Structural Similarity Index (SSIM) for comparing rendered images against reference
  * images. SSIM is preferred over MSE/PSNR because it better correlates with human perception of
  * image quality.
  *
  * @see
  *   CAUSTICS_TEST_LADDER.md Step 8
  * @see
  *   Wang et al. "Image Quality Assessment: From Error Visibility to Structural Similarity" (2004)
  */
object ImageComparison:

  /** SSIM constants (standard values from Wang et al. 2004) */
  private val K1 = 0.01
  private val K2 = 0.03
  private val L = 255.0 // Dynamic range for 8-bit images
  private val C1 = (K1 * L) * (K1 * L)
  private val C2 = (K2 * L) * (K2 * L)

  /** Window size for local SSIM computation */
  private val WindowSize = 11

  def ssim(reference: BufferedImage, test: BufferedImage): Double =
    require(
      reference.getWidth == test.getWidth && reference.getHeight == test.getHeight,
      s"Image dimensions must match: ${reference.getWidth}x${reference.getHeight} " +
        s"vs ${test.getWidth}x${test.getHeight}"
    )

    val width = reference.getWidth
    val height = reference.getHeight

    // Convert to grayscale luminance for SSIM (standard approach)
    val refGray = toGrayscale(reference)
    val testGray = toGrayscale(test)

    val halfWindow = WindowSize / 2

    // Compute SSIM over sliding windows
    val ssimValues = for
      y <- halfWindow until (height - halfWindow)
      x <- halfWindow until (width - halfWindow)
    yield computeLocalSsim(refGray, testGray, x, y, halfWindow)

    if ssimValues.nonEmpty then ssimValues.sum / ssimValues.length else 0.0

  def ssimFromFiles(referencePath: String, testPath: String): Either[String, Double] =
    for
      reference <- loadImage(referencePath)
      test <- loadImage(testPath)
    yield ssim(reference, test)

  def mse(reference: BufferedImage, test: BufferedImage): Double =
    require(
      reference.getWidth == test.getWidth && reference.getHeight == test.getHeight,
      "Image dimensions must match"
    )

    val width = reference.getWidth
    val height = reference.getHeight

    val sum = (for
      y <- 0 until height
      x <- 0 until width
    yield
      val refPixel = reference.getRGB(x, y)
      val testPixel = test.getRGB(x, y)

      val dr = ((refPixel >> 16) & 0xff) - ((testPixel >> 16) & 0xff)
      val dg = ((refPixel >> 8) & 0xff) - ((testPixel >> 8) & 0xff)
      val db = (refPixel & 0xff) - (testPixel & 0xff)

      (dr * dr + dg * dg + db * db) / 3.0
    ).sum

    sum / (width * height)

  def psnrDecibels(reference: BufferedImage, test: BufferedImage): Double =
    val mseValue = mse(reference, test)
    if mseValue == 0.0 then Double.PositiveInfinity
    else 10.0 * math.log10((255.0 * 255.0) / mseValue)

  def loadImage(path: String): Either[String, BufferedImage] =
    Try(ImageIO.read(new File(path))).toEither.left
      .map(e => s"Failed to load image $path: ${e.getMessage}")
      .flatMap { img =>
        if img == null then Left(s"Failed to load image: $path")
        else Right(img)
      }

  // Luminance formula: Y = 0.299*R + 0.587*G + 0.114*B
  private def toGrayscale(image: BufferedImage): Array[Array[Double]] =
    val width = image.getWidth
    val height = image.getHeight
    val result = Array.ofDim[Double](height, width)

    for
      y <- 0 until height
      x <- 0 until width
    do
      val pixel = image.getRGB(x, y)
      val r = (pixel >> 16) & 0xff
      val g = (pixel >> 8) & 0xff
      val b = pixel & 0xff
      result(y)(x) = 0.299 * r + 0.587 * g + 0.114 * b

    result

  private case class SsimStats(
      sumRef: Double,
      sumTest: Double,
      sumRefSq: Double,
      sumTestSq: Double,
      sumRefTest: Double,
      count: Int
  ):
    def ssim: Double =
      val n = count.toDouble
      val muRef = sumRef / n
      val muTest = sumTest / n

      val sigmaRefSq = sumRefSq / n - muRef * muRef
      val sigmaTestSq = sumTestSq / n - muTest * muTest
      val sigmaRefTest = sumRefTest / n - muRef * muTest

      val numerator = (2.0 * muRef * muTest + C1) * (2.0 * sigmaRefTest + C2)
      val denominator = (muRef * muRef + muTest * muTest + C1) * (sigmaRefSq + sigmaTestSq + C2)

      numerator / denominator


  private def computeLocalSsim(
      ref: Array[Array[Double]],
      test: Array[Array[Double]],
      cx: Int,
      cy: Int,
      halfWindow: Int
  ): Double =
    val stats = (for
      dy <- -halfWindow to halfWindow
      dx <- -halfWindow to halfWindow
    yield (ref(cy + dy)(cx + dx), test(cy + dy)(cx + dx))
    ).foldLeft(SsimStats(0.0, 0.0, 0.0, 0.0, 0.0, 0)) { case (acc, (refVal, testVal)) =>
      SsimStats(
        acc.sumRef + refVal,
        acc.sumTest + testVal,
        acc.sumRefSq + refVal * refVal,
        acc.sumTestSq + testVal * testVal,
        acc.sumRefTest + refVal * testVal,
        acc.count + 1
      )
    }
    stats.ssim
