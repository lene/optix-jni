package menger.optix.caustics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File

/** C8 - Reference Match validation tests.
  *
  * Compares rendered caustics images against known-good reference images using SSIM.
  *
  * @see
  *   CAUSTICS_TEST_LADDER.md Step 8
  * @see
  *   CAUSTICS_REFERENCES.md for reference image sources
  */
class ReferenceMatchSpec extends AnyFlatSpec with Matchers:

  /** Minimum SSIM threshold for validation */
  val SsimThreshold = 0.90

  /** Path to reference image (render with PBRT first) */
  val ReferenceImagePath = "src/test/resources/caustics-reference.png"

  /** Path to test image (rendered by our implementation) */
  val TestImagePath = "target/test-caustics.png"

  behavior of "Caustics reference match (C8)"

  it should "have reference image available" in:
    val refFile = new File(ReferenceImagePath)
    if !refFile.exists then
      pending // Skip test if reference not yet rendered
    else refFile.exists shouldBe true

  it should "match reference image with SSIM > 0.90" in:
    val refFile = new File(ReferenceImagePath)
    val testFile = new File(TestImagePath)

    if !refFile.exists then
      pending // Reference image not yet rendered
    else if !testFile.exists then
      pending // Test image not yet rendered
    else
      ImageComparison.ssimFromFiles(ReferenceImagePath, TestImagePath) match
        case Right(ssim) =>
          info(s"SSIM: $ssim (threshold: $SsimThreshold)")
          ssim should be >= SsimThreshold
        case Left(error) =>
          fail(s"Image comparison failed: $error")

  it should "report detailed metrics for debugging" in:
    val refFile = new File(ReferenceImagePath)
    val testFile = new File(TestImagePath)

    if !refFile.exists || !testFile.exists then pending
    else
      for
        reference <- ImageComparison.loadImage(ReferenceImagePath)
        test <- ImageComparison.loadImage(TestImagePath)
      do
        val ssim = ImageComparison.ssim(reference, test)
        val mse = ImageComparison.mse(reference, test)
        val psnr = ImageComparison.psnr(reference, test)

        info(s"SSIM: $ssim")
        info(s"MSE: $mse")
        info(s"PSNR: $psnr dB")

        // These are informational, not assertions
        ssim should be >= 0.0
        ssim should be <= 1.0

end ReferenceMatchSpec

/** Utility for comparing images from command line.
  *
  * Usage: scala ReferenceMatchSpec.main reference.png test.png
  */
object ReferenceMatchSpec:
  def main(args: Array[String]): Unit =
    if args.length < 2 then
      println("Usage: ReferenceMatchSpec <reference.png> <test.png>")
      sys.exit(1)

    val referencePath = args(0)
    val testPath = args(1)

    println(s"Comparing images:")
    println(s"  Reference: $referencePath")
    println(s"  Test: $testPath")
    println()

    ImageComparison.ssimFromFiles(referencePath, testPath) match
      case Right(ssim) =>
        println(f"SSIM: $ssim%.4f")
        if ssim >= 0.90 then
          println("✓ PASS: SSIM >= 0.90")
          sys.exit(0)
        else
          println("✗ FAIL: SSIM < 0.90")
          sys.exit(1)
      case Left(error) =>
        println(s"Error: $error")
        sys.exit(1)
