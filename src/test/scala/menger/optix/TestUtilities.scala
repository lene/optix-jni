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
    */
  def createUnitCubeMesh(): menger.common.TriangleMeshData =
    import menger.common.TriangleMeshData

    // 8 vertices of a cube centered at origin (position + normal, 6 floats each)
    val vertexData = Array[Float](
      // Position (x,y,z) + Normal (nx, ny, nz)
      -0.5f, -0.5f, -0.5f,  0f, 0f, -1f, // 0: left-bottom-back
       0.5f, -0.5f, -0.5f,  0f, 0f, -1f, // 1: right-bottom-back
       0.5f,  0.5f, -0.5f,  0f, 0f, -1f, // 2: right-top-back
      -0.5f,  0.5f, -0.5f,  0f, 0f, -1f, // 3: left-top-back
      -0.5f, -0.5f,  0.5f,  0f, 0f,  1f, // 4: left-bottom-front
       0.5f, -0.5f,  0.5f,  0f, 0f,  1f, // 5: right-bottom-front
       0.5f,  0.5f,  0.5f,  0f, 0f,  1f, // 6: right-top-front
      -0.5f,  0.5f,  0.5f,  0f, 0f,  1f  // 7: left-top-front
    )

    // 12 triangles (2 per face, 6 faces)
    val indexData = Array[Int](
      // Back face
      0, 1, 2,  0, 2, 3,
      // Front face
      4, 6, 5,  4, 7, 6,
      // Left face
      0, 3, 7,  0, 7, 4,
      // Right face
      1, 5, 6,  1, 6, 2,
      // Bottom face
      0, 4, 5,  0, 5, 1,
      // Top face
      3, 2, 6,  3, 6, 7
    )

    TriangleMeshData(vertexData, indexData)
