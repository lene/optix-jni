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

  /** Computes the Structural Similarity Index (SSIM) between two images.
    *
    * @param reference
    *   The reference (ground truth) image
    * @param test
    *   The test image to compare
    * @return
    *   SSIM value in range [0, 1], where 1 = identical
    */
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

    // Compute SSIM over sliding windows
    var ssimSum = 0.0
    var count = 0

    val halfWindow = WindowSize / 2

    for
      y <- halfWindow until (height - halfWindow)
      x <- halfWindow until (width - halfWindow)
    do
      val localSsim = computeLocalSsim(refGray, testGray, x, y, halfWindow)
      ssimSum += localSsim
      count += 1

    if count > 0 then ssimSum / count else 0.0
  end ssim

  /** Computes SSIM between two image files.
    *
    * @param referencePath
    *   Path to reference image
    * @param testPath
    *   Path to test image
    * @return
    *   SSIM value or error message
    */
  def ssimFromFiles(referencePath: String, testPath: String): Either[String, Double] =
    for
      reference <- loadImage(referencePath)
      test <- loadImage(testPath)
    yield ssim(reference, test)

  /** Computes Mean Squared Error between two images.
    *
    * @param reference
    *   The reference image
    * @param test
    *   The test image
    * @return
    *   MSE value (0 = identical)
    */
  def mse(reference: BufferedImage, test: BufferedImage): Double =
    require(
      reference.getWidth == test.getWidth && reference.getHeight == test.getHeight,
      "Image dimensions must match"
    )

    val width = reference.getWidth
    val height = reference.getHeight
    var sum = 0.0

    for
      y <- 0 until height
      x <- 0 until width
    do
      val refPixel = reference.getRGB(x, y)
      val testPixel = test.getRGB(x, y)

      val dr = ((refPixel >> 16) & 0xff) - ((testPixel >> 16) & 0xff)
      val dg = ((refPixel >> 8) & 0xff) - ((testPixel >> 8) & 0xff)
      val db = (refPixel & 0xff) - (testPixel & 0xff)

      sum += (dr * dr + dg * dg + db * db) / 3.0

    sum / (width * height)
  end mse

  /** Computes Peak Signal-to-Noise Ratio.
    *
    * @param reference
    *   The reference image
    * @param test
    *   The test image
    * @return
    *   PSNR in decibels (higher = more similar)
    */
  def psnr(reference: BufferedImage, test: BufferedImage): Double =
    val mseValue = mse(reference, test)
    if mseValue == 0.0 then Double.PositiveInfinity
    else 10.0 * math.log10((255.0 * 255.0) / mseValue)

  /** Loads an image from file.
    *
    * @param path
    *   Path to image file
    * @return
    *   Loaded image or error message
    */
  def loadImage(path: String): Either[String, BufferedImage] =
    Try(ImageIO.read(new File(path))).toEither.left
      .map(e => s"Failed to load image $path: ${e.getMessage}")
      .flatMap { img =>
        if img == null then Left(s"Failed to load image: $path")
        else Right(img)
      }

  /** Converts an image to grayscale luminance values.
    *
    * Uses standard luminance formula: Y = 0.299*R + 0.587*G + 0.114*B
    */
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
  end toGrayscale

  /** Computes local SSIM for a window centered at (cx, cy). */
  private def computeLocalSsim(
      ref: Array[Array[Double]],
      test: Array[Array[Double]],
      cx: Int,
      cy: Int,
      halfWindow: Int
  ): Double =
    var sumRef = 0.0
    var sumTest = 0.0
    var sumRefSq = 0.0
    var sumTestSq = 0.0
    var sumRefTest = 0.0
    var count = 0

    for
      dy <- -halfWindow to halfWindow
      dx <- -halfWindow to halfWindow
    do
      val refVal = ref(cy + dy)(cx + dx)
      val testVal = test(cy + dy)(cx + dx)

      sumRef += refVal
      sumTest += testVal
      sumRefSq += refVal * refVal
      sumTestSq += testVal * testVal
      sumRefTest += refVal * testVal
      count += 1

    val n = count.toDouble
    val muRef = sumRef / n
    val muTest = sumTest / n

    val sigmaRefSq = (sumRefSq / n) - (muRef * muRef)
    val sigmaTestSq = (sumTestSq / n) - (muTest * muTest)
    val sigmaRefTest = (sumRefTest / n) - (muRef * muTest)

    val numerator = (2.0 * muRef * muTest + C1) * (2.0 * sigmaRefTest + C2)
    val denominator = (muRef * muRef + muTest * muTest + C1) * (sigmaRefSq + sigmaTestSq + C2)

    numerator / denominator
  end computeLocalSsim

end ImageComparison
