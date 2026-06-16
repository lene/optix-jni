package io.github.lene.optix

import io.github.lene.optix.ColorConstants.OPAQUE_GREEN
import io.github.lene.optix.ColorConstants.OPAQUE_RED
import io.github.lene.optix.ColorConstants.OPAQUE_WHITE
import io.github.lene.optix.ThresholdConstants.QUICK_TEST_SIZE
import io.github.lene.optix.ThresholdConstants.TEST_IMAGE_SIZE
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CurveSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val simpleCurvePoints = Array(
    -1.2f, -0.35f, 0.0f,
    -0.4f, 0.65f, 0.0f,
    0.4f, 0.65f, 0.0f,
    1.2f, -0.35f, 0.0f
  )

  private val simpleCurveWidths = Array(0.12f, 0.12f, 0.12f, 0.12f)

  "addCurveInstance" should "reject null points" in:
    an[IllegalArgumentException] should be thrownBy
      renderer.addCurveInstance(
        null, // scalafix:ok DisableSyntax.null
        simpleCurveWidths,
        Material.Chrome
      )

  it should "reject null widths" in:
    an[IllegalArgumentException] should be thrownBy
      renderer.addCurveInstance(
        simpleCurvePoints,
        null, // scalafix:ok DisableSyntax.null
        Material.Chrome
      )

  it should "reject non-xyz point length" in:
    an[IllegalArgumentException] should be thrownBy
      renderer.addCurveInstance(
        Array(0.0f, 1.0f, 2.0f, 3.0f),
        simpleCurveWidths,
        Material.Chrome
      )

  it should "reject fewer than four control points" in:
    an[IllegalArgumentException] should be thrownBy
      renderer.addCurveInstance(
        Array(
          0.0f, 0.0f, 0.0f,
          1.0f, 0.0f, 0.0f,
          2.0f, 0.0f, 0.0f
        ),
        Array(0.1f, 0.1f, 0.1f),
        Material.Chrome
      )

  it should "reject width length mismatch" in:
    an[IllegalArgumentException] should be thrownBy
      renderer.addCurveInstance(
        simpleCurvePoints,
        Array(0.1f, 0.1f, 0.1f),
        Material.Chrome
      )

  it should "reject non-finite points" in:
    val points = simpleCurvePoints.updated(2, Float.NaN)
    an[IllegalArgumentException] should be thrownBy
      renderer.addCurveInstance(points, simpleCurveWidths, Material.Chrome)

  it should "reject non-finite widths" in:
    val widths = simpleCurveWidths.updated(1, Float.PositiveInfinity)
    an[IllegalArgumentException] should be thrownBy
      renderer.addCurveInstance(simpleCurvePoints, widths, Material.Chrome)

  it should "reject zero and negative widths" in:
    val zeroWidth = simpleCurveWidths.updated(0, 0.0f)
    val negativeWidth = simpleCurveWidths.updated(0, -0.1f)

    an[IllegalArgumentException] should be thrownBy
      renderer.addCurveInstance(simpleCurvePoints, zeroWidth, Material.Chrome)
    an[IllegalArgumentException] should be thrownBy
      renderer.addCurveInstance(simpleCurvePoints, negativeWidth, Material.Chrome)

  it should "return a valid instance ID for a simple cubic curve" in:
    val result = renderer.addCurveInstance(
      simpleCurvePoints,
      simpleCurveWidths,
      Material(OPAQUE_GREEN, ior = 1.0f)
    )

    result should be >= 0

  "Curve rendering" should "produce non-empty image data" in:
    renderer.addCurveInstance(
      simpleCurvePoints,
      simpleCurveWidths,
      Material(OPAQUE_GREEN, ior = 1.0f)
    )

    val imageData = renderImage(QUICK_TEST_SIZE)
    imageData.length shouldBe ImageValidation.imageByteSize(QUICK_TEST_SIZE)

  it should "render visible curve geometry" in:
    renderer.addCurveInstance(
      simpleCurvePoints,
      simpleCurveWidths,
      Material(OPAQUE_GREEN, ior = 1.0f)
    )

    val imageData = renderImage(TEST_IMAGE_SIZE)
    val stdDev = ImageValidation.brightnessStdDev(imageData, TEST_IMAGE_SIZE)
    stdDev should be > 5.0

  it should "render mixed sphere and curve geometry" in:
    val sphereId = renderer.addSphereInstance(
      Vector[3](-0.7f, 0.0f, 0.0f),
      Material(OPAQUE_RED, ior = 1.0f)
    )
    val curveId = renderer.addCurveInstance(
      simpleCurvePoints.map(_ * 0.7f),
      simpleCurveWidths,
      Material(OPAQUE_GREEN, ior = 1.0f)
    )

    sphereId should be >= 0
    curveId should be >= 0

    val imageData = renderImage(TEST_IMAGE_SIZE)
    val ratio = ImageValidation.colorChannelRatio(imageData, TEST_IMAGE_SIZE)
    ratio.r should be > 0.1
    ratio.g should be > 0.1

  it should "render shadows for curve geometry" in:
    renderer.clearPlanes()
    renderer.addPlane(1, positive = true, -0.55f)
    renderer.setShadows(true)

    renderer.addCurveInstance(
      simpleCurvePoints,
      simpleCurveWidths.map(_ * 1.3f),
      Material(OPAQUE_WHITE, ior = 1.0f)
    )

    val imageData = renderImage(TEST_IMAGE_SIZE)
    val stdDev = ImageValidation.brightnessStdDev(imageData, TEST_IMAGE_SIZE)
    stdDev should be > 8.0
