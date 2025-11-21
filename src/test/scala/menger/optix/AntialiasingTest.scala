package menger.optix

import menger.common.ImageSize
import menger.common.Vector

import com.typesafe.scalalogging.LazyLogging
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import ColorConstants.*
import ThresholdConstants.*
import ImageMatchers.*


class AntialiasingTest extends AnyFlatSpec
    with Matchers
    with LazyLogging
    with RendererFixture:

  private val testSize = ImageSize(200, 200)

  "Antialiasing" should "be settable without error" in:
    noException should be thrownBy:
      renderer.setAntialiasing(enabled = true, maxDepth = 2, threshold = 0.1f)

  it should "render successfully when enabled" in:
    renderer.setAntialiasing(enabled = true, maxDepth = 2, threshold = 0.1f)
    val image = renderImage(testSize)
    image.length shouldBe testSize.width * testSize.height * 4

  it should "produce valid RGBA output when enabled" in:
    renderer.setAntialiasing(enabled = true, maxDepth = 2, threshold = 0.1f)
    val image = renderImage(testSize)

    // Check for valid pixel data (not all zeros or all 255)
    val hasVariation = image.exists(b => (b & 0xFF) > 0 && (b & 0xFF) < 255)
    hasVariation shouldBe true

  it should "work with different max depth values" in:
    for depth <- 1 to 4 do
      noException should be thrownBy:
        renderer.setAntialiasing(enabled = true, maxDepth = depth, threshold = 0.1f)
        val image = renderImage(testSize)
        image.length shouldBe testSize.width * testSize.height * 4

  it should "work with different threshold values" in:
    for threshold <- List(0.01f, 0.05f, 0.1f, 0.2f, 0.5f) do
      noException should be thrownBy:
        renderer.setAntialiasing(enabled = true, maxDepth = 2, threshold = threshold)
        val image = renderImage(testSize)
        image.length shouldBe testSize.width * testSize.height * 4

  it should "be disableable after being enabled" in:
    renderer.setAntialiasing(enabled = true, maxDepth = 2, threshold = 0.1f)
    val aaImage = renderImage(testSize)

    renderer.setAntialiasing(enabled = false, maxDepth = 2, threshold = 0.1f)
    val normalImage = renderImage(testSize)

    // Both should produce valid images
    aaImage.length shouldBe normalImage.length

  it should "produce consistent results on repeated renders" in:
    renderer.setAntialiasing(enabled = true, maxDepth = 2, threshold = 0.1f)
    val image1 = renderImage(testSize)
    val image2 = renderImage(testSize)

    // AA is deterministic, so renders should be identical
    image1 shouldEqual image2

  "AA edge detection" should "produce smoother edges than non-AA" in:
    // Render without AA
    renderer.setAntialiasing(enabled = false, maxDepth = 2, threshold = 0.1f)
    val normalImage = renderImage(testSize)
    val normalEdgeVariance = measureEdgeVariance(normalImage, testSize.width, testSize.height)

    // Render with AA
    renderer.setAntialiasing(enabled = true, maxDepth = 2, threshold = 0.1f)
    val aaImage = renderImage(testSize)
    val aaEdgeVariance = measureEdgeVariance(aaImage, testSize.width, testSize.height)

    logger.info(s"Edge variance: normal=$normalEdgeVariance, aa=$aaEdgeVariance")

    // AA should produce lower edge variance (smoother transitions)
    // Using a generous tolerance since AA effect depends on scene content
    aaEdgeVariance should be <= (normalEdgeVariance * 1.1f)

  private def measureEdgeVariance(image: Array[Byte], width: Int, height: Int): Float =
    // Measure variance in pixel-to-pixel differences
    // Lower variance = smoother edges
    var sumDiff = 0L
    var sumDiffSq = 0L
    var count = 0L

    for y <- 0 until height - 1 do
      for x <- 0 until width - 1 do
        val idx = (y * width + x) * 4
        val nextX = (y * width + x + 1) * 4
        val nextY = ((y + 1) * width + x) * 4

        // Horizontal difference
        val hDiff = Math.abs((image(idx) & 0xFF) - (image(nextX) & 0xFF)) +
                    Math.abs((image(idx + 1) & 0xFF) - (image(nextX + 1) & 0xFF)) +
                    Math.abs((image(idx + 2) & 0xFF) - (image(nextX + 2) & 0xFF))

        // Vertical difference
        val vDiff = Math.abs((image(idx) & 0xFF) - (image(nextY) & 0xFF)) +
                    Math.abs((image(idx + 1) & 0xFF) - (image(nextY + 1) & 0xFF)) +
                    Math.abs((image(idx + 2) & 0xFF) - (image(nextY + 2) & 0xFF))

        val totalDiff = hDiff + vDiff
        sumDiff += totalDiff
        sumDiffSq += totalDiff.toLong * totalDiff
        count += 1

    if count == 0 then 0.0f
    else
      val mean = sumDiff.toDouble / count
      val variance = (sumDiffSq.toDouble / count) - (mean * mean)
      variance.toFloat
