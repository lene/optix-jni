package io.github.lene.optix

import io.github.lene.optix.ImageValidation.getRGBAt
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.TriangleMeshData
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

  // --- menger#128 (F111): a mesh charges the glass a shadow ray crosses -----------------------

  private val unitCubeFrame = Array(1f, 0f, 0f, 0.5f, 0f, 1f, 0f, 0.5f, 0f, 0f, 1f, 0.5f)
  private val LayerCount = 4

  /** The unit cube's volume as `LayerCount` separate slabs stacked along y in one mesh: a
    * shadow ray crosses a front face of each, as one crosses many in a sponge. */
  private def layeredCube: TriangleMeshData =
    val cube = TestUtilities.createUnitCubeMesh()
    val stride = cube.vertexStride
    val vertexCount = cube.vertices.length / stride
    val thickness = 1f / LayerCount
    val layers = (0 until LayerCount).map { k =>
      val centreY = -0.5f + thickness * (k + 0.5f)
      val vertices = cube.vertices.grouped(stride).flatMap { v =>
        v.updated(1, v(1) * thickness * 0.8f + centreY)
      }.toArray
      (vertices, cube.indices.map(_ + k * vertexCount))
    }
    TriangleMeshData(
      layers.flatMap(_._1).toArray, layers.flatMap(_._2).toArray, stride
    )

  /** The floor under `mesh` (none: the bare floor), lit along (1, -1, 0). */
  private def renderMeshShadow(mesh: Option[TriangleMeshData], colour: Color = redGlass,
                               intensity: Float = 1.0f): Array[Byte] =
    renderer.setCamera(
      Vector[3](2f, 4f, 4f), Vector[3](2f, Const.defaultFloorPlaneY, 0f), Vector[3](0f, 1f, 0f), 60f
    )
    renderer.setLight(Vector[3](1f, -1f, 0f), intensity)
    renderer.setShadows(true)
    renderer.setTransparentShadows(true)
    renderer.clearAllInstances()
    renderer.clearTriangleMesh()
    mesh.foreach { m =>
      val _ = renderer.addTriangleMesh(m)
      val id = renderer.addTriangleMeshInstance(
        Array(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f), Material(colour, Const.iorGlass)
      )
      renderer.setObjectFrame(id, unitCubeFrame)
    }
    renderImage(imgSize)

  /** The unit cube scaled by (sx, sy, sz) about its centre. Only positions are scaled: the
    * axis-aligned normals stay unit vectors. */
  private def scaledCube(sx: Float, sy: Float, sz: Float): TriangleMeshData =
    val cube = TestUtilities.createUnitCubeMesh()
    TriangleMeshData(
      cube.vertices.grouped(cube.vertexStride).flatMap { v =>
        v.updated(0, v(0) * sx).updated(1, v(1) * sy).updated(2, v(2) * sz)
      }.toArray,
      cube.indices, cube.vertexStride
    )

  /** The unit cube flattened along y to `fraction` of its height, under the same frame. */
  private def slab(fraction: Float): TriangleMeshData = scaledCube(1f, fraction, 1f)

  private def greenToRed(rgb: (Double, Double, Double)): Double = rgb._2 / math.max(rgb._1, 1.0)

  "A glass mesh crossed through several slabs" should
    "lose light at each surface but absorb only over the glass it crosses" in {
    // The red glass absorbs almost no red: red measures the surfaces' Fresnel loss, green the
    // absorption. Four slabs have eight surfaces to the block's two, and 0.8 of its glass.
    // Charging the whole-object chord at every front face over-counted the glass (green/red
    // below the block's); charging it once per object dropped the extra surfaces (red equal).
    val block = patch(renderMeshShadow(Some(TestUtilities.createUnitCubeMesh())), 32, 32)
    val layers = patch(renderMeshShadow(Some(layeredCube)), 32, 32)
    info(f"block $block, layers $layers")
    layers._1 should be < 0.95 * block._1
    greenToRed(layers) should be > greenToRed(block) + 0.01
  }

  "A thinner glass slab under the same frame" should "absorb less than the full block" in {
    // The mean chord is the same for both (it comes from the frame); the glass crossed is not.
    val block = patch(renderMeshShadow(Some(TestUtilities.createUnitCubeMesh())), 32, 32)
    val thin = patch(renderMeshShadow(Some(slab(0.5f))), 32, 32)
    info(f"block $block, thin $thin")
    greenToRed(thin) should be > greenToRed(block) + 0.01
  }

  "A flat glass slab" should "let through (1 - F0)^2 * exp(-sigma * L) of the direct light" in {
    // The floor is linear in the light (no tone mapping), so on the same pixels the direct light
    // a slab lets through is (glass - opaque) / (bare - opaque) per channel. The slab is wide in
    // x and z, so the 45-degree shadow ray enters its top and leaves its bottom: L = h * sqrt 2.
    val thickness = 0.25f
    val glass = Color(0.9f, 0.5f, 0.2f, 0.2f)
    val mesh = scaledCube(2f, thickness, 2f)
    val intensity = 3.0f
    def floor(m: Option[TriangleMeshData], c: Color) =
      patch(renderMeshShadow(m, c, intensity), 32, 32)
    val bare = floor(None, glass)
    val opaque = floor(Some(mesh), Color(0f, 0f, 0f, 1f))
    val through = floor(Some(mesh), glass)
    def measured(pick: ((Double, Double, Double)) => Double) =
      (pick(through) - pick(opaque)) / (pick(bare) - pick(opaque))
    val f0 = math.pow((Const.iorGlass - 1.0) / (Const.iorGlass + 1.0), 2)
    val length = thickness * math.sqrt(2.0)
    def expected(channel: Float) =
      math.pow(1 - f0, 2) * math.exp(math.log(channel.toDouble) * glass.a * 5.0 * length)
    info(f"bare $bare, opaque $opaque, through $through")
    val pairs = Seq((measured(_._1), expected(glass.r)), (measured(_._2), expected(glass.g)),
      (measured(_._3), expected(glass.b)))
    info(pairs.map((m, e) => f"$m%.3f vs $e%.3f").mkString(", "))
    pairs.foreach((m, e) => m shouldBe e +- 0.03)
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
