package menger.optix

import menger.common.ImageSize
import com.typesafe.scalalogging.LazyLogging
import java.io.File
import scala.util.Try


object TestUtilities extends LazyLogging:

  case class Region(x0: Int, y0: Int, x1: Int, y1: Int):
    def width: Int = x1 - x0
    def height: Int = y1 - y0
    def area: Int = width * height
    def contains(x: Int, y: Int): Boolean =
      x >= x0 && x < x1 && y >= y0 && y < y1

  object Region:

    def centered(cx: Int, cy: Int, radius: Int): Region =
      Region(cx - radius, cy - radius, cx + radius, cy + radius)

    def bottomCenter(size: ImageSize, fraction: Double = 0.25): Region =
      val regionWidth = (size.width * fraction).toInt
      val regionHeight = (size.height * fraction).toInt
      val x0 = (size.width - regionWidth) / 2
      val y0 = size.height - regionHeight
      Region(x0, y0, x0 + regionWidth, y0 + regionHeight)

    def topCenter(size: ImageSize, fraction: Double = 0.25): Region =
      val regionWidth = (size.width * fraction).toInt
      val regionHeight = (size.height * fraction).toInt
      val x0 = (size.width - regionWidth) / 2
      Region(x0, 0, x0 + regionWidth, regionHeight)

    def leftSide(size: ImageSize, fraction: Double = 0.25): Region =
      val regionWidth = (size.width * fraction).toInt
      val y0 = size.height / 4
      val y1 = 3 * size.height / 4
      Region(0, y0, regionWidth, y1)

    def rightSide(size: ImageSize, fraction: Double = 0.25): Region =
      val regionWidth = (size.width * fraction).toInt
      val y0 = size.height / 4
      val y1 = 3 * size.height / 4
      Region(size.width - regionWidth, y0, size.width, y1)

  def regionBrightness(
      imageData: Array[Byte],
      size: ImageSize,
      region: Region
  ): Double =
    import ImageValidation.getRGBAt
    val samples = for
      y <- region.y0 until region.y1 if y >= 0 && y < size.height
      x <- region.x0 until region.x1 if x >= 0 && x < size.width
    yield getRGBAt(imageData, size, x, y).brightness

    if samples.isEmpty then 0.0
    else samples.sum.toDouble / samples.length

  def savePNG(
    filename: String,
    data: Array[Byte],
    width: Int,
    height: Int
  ): Try[File] =
    import javax.imageio.ImageIO
    import java.awt.image.BufferedImage

    Try {
      val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)

      // Convert RGBA byte array to BufferedImage
      (0 until width * height).foreach { i =>
        val offset = i * 4
        val r = data(offset) & 0xFF
        val g = data(offset + 1) & 0xFF
        val b = data(offset + 2) & 0xFF
        val a = data(offset + 3) & 0xFF

        val argb = (a << 24) | (r << 16) | (g << 8) | b
        val x = i % width
        val y = i / width
        image.setRGB(x, y, argb)
      }

      val file = new File(filename)
      ImageIO.write(image, "PNG", file)
      file
    }

  
  def createTestRenderer(scenario: TestScenario): Try[OptiXRenderer] =
    for
      renderer <- Try(new OptiXRenderer())
      _        <- Try(renderer.initialize())
      _         = scenario.applyTo(renderer)
    yield renderer

  
  def withRenderer[A](scenario: TestScenario)(test: OptiXRenderer => A): Try[A] =
    Try {
      val renderer = new OptiXRenderer()
      try
        renderer.initialize()
        scenario.applyTo(renderer)
        test(renderer)
      finally
        renderer.dispose()
    }


  def measureFPS(
    renderer: OptiXRenderer,
    scenario: TestScenario,
    size: ImageSize,
    frames: Int = 100
  ): Double =
    scenario.applyTo(renderer)

    val start = System.nanoTime
    (0 until frames).foreach { _ =>
      renderer.render(size).get
    }
    val duration = (System.nanoTime - start) / 1e9
    frames / duration

  /** Create a simple unit cube mesh centered at origin for testing.
    * Cube has vertices at ±0.5 on each axis.
    * Uses 8-float vertex format: position(3) + normal(3) + uv(2)
    */
  def createUnitCubeMesh(): menger.common.TriangleMeshData =
    import menger.common.TriangleMeshData

    // 24 vertices (4 per face) with position + normal + uv (8 floats each)
    // This is the canonical cube with proper face normals and UV coordinates
    val vertexData = Array[Float](
      // Front face (z=0.5, normal +Z)
      -0.5f, -0.5f, 0.5f,  0f, 0f, 1f,  0f, 0f,
       0.5f, -0.5f, 0.5f,  0f, 0f, 1f,  1f, 0f,
       0.5f,  0.5f, 0.5f,  0f, 0f, 1f,  1f, 1f,
      -0.5f,  0.5f, 0.5f,  0f, 0f, 1f,  0f, 1f,
      // Back face (z=-0.5, normal -Z)
       0.5f, -0.5f, -0.5f,  0f, 0f, -1f,  0f, 0f,
      -0.5f, -0.5f, -0.5f,  0f, 0f, -1f,  1f, 0f,
      -0.5f,  0.5f, -0.5f,  0f, 0f, -1f,  1f, 1f,
       0.5f,  0.5f, -0.5f,  0f, 0f, -1f,  0f, 1f,
      // Top face (y=0.5, normal +Y)
      -0.5f, 0.5f,  0.5f,  0f, 1f, 0f,  0f, 0f,
       0.5f, 0.5f,  0.5f,  0f, 1f, 0f,  1f, 0f,
       0.5f, 0.5f, -0.5f,  0f, 1f, 0f,  1f, 1f,
      -0.5f, 0.5f, -0.5f,  0f, 1f, 0f,  0f, 1f,
      // Bottom face (y=-0.5, normal -Y)
      -0.5f, -0.5f, -0.5f,  0f, -1f, 0f,  0f, 0f,
       0.5f, -0.5f, -0.5f,  0f, -1f, 0f,  1f, 0f,
       0.5f, -0.5f,  0.5f,  0f, -1f, 0f,  1f, 1f,
      -0.5f, -0.5f,  0.5f,  0f, -1f, 0f,  0f, 1f,
      // Right face (x=0.5, normal +X)
      0.5f, -0.5f,  0.5f,  1f, 0f, 0f,  0f, 0f,
      0.5f, -0.5f, -0.5f,  1f, 0f, 0f,  1f, 0f,
      0.5f,  0.5f, -0.5f,  1f, 0f, 0f,  1f, 1f,
      0.5f,  0.5f,  0.5f,  1f, 0f, 0f,  0f, 1f,
      // Left face (x=-0.5, normal -X)
      -0.5f, -0.5f, -0.5f,  -1f, 0f, 0f,  0f, 0f,
      -0.5f, -0.5f,  0.5f,  -1f, 0f, 0f,  1f, 0f,
      -0.5f,  0.5f,  0.5f,  -1f, 0f, 0f,  1f, 1f,
      -0.5f,  0.5f, -0.5f,  -1f, 0f, 0f,  0f, 1f
    )

    // 12 triangles (2 per face, 6 faces)
    val indexData = Array[Int](
      0, 1, 2,  0, 2, 3,     // Front
      4, 5, 6,  4, 6, 7,     // Back
      8, 9, 10, 8, 10, 11,   // Top
      12, 13, 14, 12, 14, 15, // Bottom
      16, 17, 18, 16, 18, 19, // Right
      20, 21, 22, 20, 22, 23  // Left
    )

    TriangleMeshData(vertexData, indexData, 8)
