package io.github.lene.optix

import io.github.lene.optix.ImageValidation.getRGBAt
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.Vector
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** Object frames (optix-jni#61): object-local procedural colouring (F63) and the shadow of a
  * transparent refractive object tinted by the colour seen through it, procedural colour
  * included (F67). The sphere instance is the unit sphere (radius 1) at the origin, so its frame
  * onto [0,1]^3 is `0.5 * world + 0.5`. */
class ObjectFrameSuite extends AnyFlatSpec with Matchers with RendererFixture:

  OptiXRenderer.isLibraryLoaded shouldBe true

  private val imgSize = ImageSize(64, 64)
  private val unitSphereFrame = Array(
    0.5f, 0f, 0f, 0.5f,
    0f, 0.5f, 0f, 0.5f,
    0f, 0f, 0.5f, 0.5f
  )

  /** Mean (r, g, b) of a 5x5 patch centred on pixel (x, y). */
  private def patch(image: Array[Byte], x: Int, y: Int): (Double, Double, Double) =
    val samples = for dy <- -2 to 2; dx <- -2 to 2 yield getRGBAt(image, imgSize, x + dx, y + dy)
    (samples.map(_.r.toDouble).sum / 25, samples.map(_.g.toDouble).sum / 25,
      samples.map(_.b.toDouble).sum / 25)

  private def redToGreen(rgb: (Double, Double, Double)): Double = rgb._1 / math.max(rgb._2, 1.0)

  // --- F63: object-local xyz -> rgb ------------------------------------------------------------

  private def renderColouredSphere(proceduralType: Int, frame: Boolean): Array[Byte] =
    // Lit from the camera side, so left and right of the sphere are shaded alike.
    renderer.setLight(Vector[3](0f, 0f, -1f), 1.0f)
    val id = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), Color(0.8f, 0.8f, 0.8f), 1.0f)
    if frame then renderer.setObjectFrame(id, unitSphereFrame)
    renderer.setProceduralTexture(id, proceduralType, 1.0f)
    renderImage(imgSize)

  "XYZToRGBLocal" should "colour the two sides of a centred object differently" in {
    // Left of centre local x is near 0.2, right near 0.8: red differs, green (local y) doesn't.
    // (The raw image has world +x on the left; only the size of the difference matters here.)
    val image = renderColouredSphere(ProceduralType.XYZToRGBLocal, frame = true)

    val (a, b) = (redToGreen(patch(image, 44, 32)), redToGreen(patch(image, 20, 32)))
    math.max(a, b) should be > 2.0 * math.min(a, b)
  }

  "XYZToRGB (world)" should "mirror a centred object at x = 0, the F63 limitation" in {
    val image = renderColouredSphere(ProceduralType.XYZToRGB, frame = false)

    val ratio = redToGreen(patch(image, 44, 32)) / redToGreen(patch(image, 20, 32))
    ratio should (be > 0.7 and be < 1.4)
  }

  "XYZToRGBLocal without a frame" should "fall back to world coordinates" in {
    renderer.setLight(Vector[3](0f, 0f, -1f), 1.0f)
    val id = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), Color(0.8f, 0.8f, 0.8f), 1.0f)
    renderer.setProceduralTexture(id, ProceduralType.XYZToRGBLocal, 1.0f)
    val local = renderImage(imgSize)
    renderer.setProceduralTexture(id, ProceduralType.XYZToRGB, 1.0f)

    local shouldBe renderImage(imgSize)
  }

  "setObjectFrame" should "reject a matrix that is not 3x4" in {
    val id = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), Color(0.8f, 0.8f, 0.8f), 1.0f)

    an[IllegalArgumentException] should be thrownBy renderer.setObjectFrame(id, Array.fill(9)(0f))
  }

  it should "reject a non-finite matrix" in {
    val id = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), Color(0.8f, 0.8f, 0.8f), 1.0f)
    val frame = unitSphereFrame.updated(0, Float.NaN)

    an[IllegalArgumentException] should be thrownBy renderer.setObjectFrame(id, frame)
  }

  // --- F67: the shadow of a transparent refractive object ------------------------------------

  /** Shadow of a glass sphere at the origin, light travelling (1, -1, 0): the shadow on the
    * floor (y = -2) is centred at (2, -2, 0), which the camera looks at, so it is the image
    * centre; the sphere is off to the side. */
  private def renderShadow(colour: Color, frame: Boolean, proceduralType: Int = 0): Array[Byte] =
    renderer.setCamera(
      Vector[3](2f, 4f, 4f), Vector[3](2f, Const.defaultFloorPlaneY, 0f), Vector[3](0f, 1f, 0f), 60f
    )
    renderer.setLight(Vector[3](1f, -1f, 0f), 1.0f)
    renderer.setShadows(true)
    renderer.setTransparentShadows(true)
    val id = renderer.addSphereInstance(Vector[3](0f, 0f, 0f), colour, Const.iorGlass)
    if frame then renderer.setObjectFrame(id, unitSphereFrame)
    if proceduralType != 0 then renderer.setProceduralTexture(id, proceduralType, 1.0f)
    renderImage(imgSize)

  private val redGlass = Color(1.0f, 0.1f, 0.1f, 0.02f)

  "A red glass sphere with a frame" should "cast a clearly red shadow" in {
    // Beer-Lambert over the mean chord (4/3 for radius 1): green and blue transmit ~0.68 of the
    // direct light; ambient light on the floor dilutes the ratio to ~1.2.
    redToGreen(patch(renderShadow(redGlass, frame = true), 32, 32)) should be > 1.12
  }

  "A red glass sphere without a frame" should "keep the old surface-model shadow, barely tinted" in {
    // alpha * (1 - colour): 0.02 * 0.9 of the direct light.
    redToGreen(patch(renderShadow(redGlass, frame = false), 32, 32)) should be < 1.05
  }

  "A glass sphere coloured by XYZToRGBLocal" should "cast a shadow whose hue varies across it" in {
    // Shadow rays through different parts of the sphere see different local colours.
    val image = renderShadow(Color(1f, 1f, 1f, 0.2f), frame = true, ProceduralType.XYZToRGBLocal)

    // Local x drives red, local z blue: across the shadow it shifts from pink to blue.
    def redToBlue(rgb: (Double, Double, Double)): Double = rgb._1 / math.max(rgb._3, 1.0)
    val left = redToBlue(patch(image, 26, 34))
    val right = redToBlue(patch(image, 37, 34))
    math.max(left, right) / math.min(left, right) should be > 1.15
  }
