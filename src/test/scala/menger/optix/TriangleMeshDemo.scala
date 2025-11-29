package menger.optix

import menger.common.Color
import menger.common.ImageSize
import menger.common.TriangleMeshData
import menger.common.Vector

object TriangleMeshDemo:

  // Simple triangle facing camera
  private def singleTriangle: TriangleMeshData =
    val vertices = Array[Float](
      -0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 1.0f,
      0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 1.0f,
      0.0f, 0.5f, 0.0f, 0.0f, 0.0f, 1.0f
    )
    TriangleMeshData(vertices, Array(0, 1, 2))

  // Quad (two triangles)
  private def quad: TriangleMeshData =
    val vertices = Array[Float](
      -0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 1.0f,
      0.5f, -0.5f, 0.0f, 0.0f, 0.0f, 1.0f,
      0.5f, 0.5f, 0.0f, 0.0f, 0.0f, 1.0f,
      -0.5f, 0.5f, 0.0f, 0.0f, 0.0f, 1.0f
    )
    TriangleMeshData(vertices, Array(0, 1, 2, 0, 2, 3))

  // Simple cube (6 faces, 12 triangles)
  private def cube: TriangleMeshData =
    val s = 0.4f // half-size
    // 8 vertices with positions and normals
    // For a cube, each face needs its own vertices for correct normals
    val vertices = Array[Float](
      // Front face (z = +s, normal = 0,0,1)
      -s, -s, s, 0, 0, 1,
      s, -s, s, 0, 0, 1,
      s, s, s, 0, 0, 1,
      -s, s, s, 0, 0, 1,
      // Back face (z = -s, normal = 0,0,-1)
      s, -s, -s, 0, 0, -1,
      -s, -s, -s, 0, 0, -1,
      -s, s, -s, 0, 0, -1,
      s, s, -s, 0, 0, -1,
      // Top face (y = +s, normal = 0,1,0)
      -s, s, s, 0, 1, 0,
      s, s, s, 0, 1, 0,
      s, s, -s, 0, 1, 0,
      -s, s, -s, 0, 1, 0,
      // Bottom face (y = -s, normal = 0,-1,0)
      -s, -s, -s, 0, -1, 0,
      s, -s, -s, 0, -1, 0,
      s, -s, s, 0, -1, 0,
      -s, -s, s, 0, -1, 0,
      // Right face (x = +s, normal = 1,0,0)
      s, -s, s, 1, 0, 0,
      s, -s, -s, 1, 0, 0,
      s, s, -s, 1, 0, 0,
      s, s, s, 1, 0, 0,
      // Left face (x = -s, normal = -1,0,0)
      -s, -s, -s, -1, 0, 0,
      -s, -s, s, -1, 0, 0,
      -s, s, s, -1, 0, 0,
      -s, s, -s, -1, 0, 0
    )
    val indices = Array[Int](
      0, 1, 2, 0, 2, 3,       // Front
      4, 5, 6, 4, 6, 7,       // Back
      8, 9, 10, 8, 10, 11,    // Top
      12, 13, 14, 12, 14, 15, // Bottom
      16, 17, 18, 16, 18, 19, // Right
      20, 21, 22, 20, 22, 23  // Left
    )
    TriangleMeshData(vertices, indices)

  def main(args: Array[String]): Unit =
    println("Triangle Mesh Demo - Rendering to PNG files...")

    // Ensure library is loaded (trigger companion object initialization)
    require(OptiXRenderer.isLibraryLoaded, "OptiX native library failed to load")

    val renderer = new OptiXRenderer()
    renderer.initialize()

    val size = ImageSize(800, 600)

    // Set up camera
    renderer.setCamera(
      Vector[3](0.0f, 0.5f, 2.5f),
      Vector[3](0.0f, 0.0f, 0.0f),
      Vector[3](0.0f, 1.0f, 0.0f),
      60.0f
    )
    renderer.setLight(Vector[3](0.5f, 0.5f, -0.5f), 1.0f)

    // 1. Render opaque green triangle
    println("Rendering opaque triangle...")
    renderer.setTriangleMesh(singleTriangle)
    renderer.setTriangleMeshColor(Color(0.2f, 0.8f, 0.2f))
    val triangleImg = renderer.render(size).get
    TestUtilities.savePNG("/tmp/triangle_opaque.png", triangleImg, size.width, size.height)
    println("  -> /tmp/triangle_opaque.png")

    // 2. Render transparent glass triangle
    println("Rendering transparent triangle...")
    renderer.setTriangleMeshColor(Color(0.9f, 0.9f, 1.0f, 0.3f))
    renderer.setTriangleMeshIOR(1.5f)
    val glassTriImg = renderer.render(size).get
    TestUtilities.savePNG("/tmp/triangle_glass.png", glassTriImg, size.width, size.height)
    println("  -> /tmp/triangle_glass.png")

    // 3. Render opaque red quad
    println("Rendering quad...")
    renderer.setTriangleMesh(quad)
    renderer.setTriangleMeshColor(Color(0.8f, 0.2f, 0.2f))
    val quadImg = renderer.render(size).get
    TestUtilities.savePNG("/tmp/quad_opaque.png", quadImg, size.width, size.height)
    println("  -> /tmp/quad_opaque.png")

    // 4. Render opaque blue cube
    println("Rendering cube...")
    renderer.setTriangleMesh(cube)
    renderer.setTriangleMeshColor(Color(0.2f, 0.4f, 0.8f))
    val cubeImg = renderer.render(size).get
    TestUtilities.savePNG("/tmp/cube_opaque.png", cubeImg, size.width, size.height)
    println("  -> /tmp/cube_opaque.png")

    // 5. Render transparent glass cube
    println("Rendering glass cube...")
    renderer.setTriangleMeshColor(Color(0.95f, 0.95f, 1.0f, 0.2f))
    renderer.setTriangleMeshIOR(1.5f)
    val glassCubeImg = renderer.render(size).get
    TestUtilities.savePNG("/tmp/cube_glass.png", glassCubeImg, size.width, size.height)
    println("  -> /tmp/cube_glass.png")

    renderer.dispose()
    println("\nDone! Check /tmp/*.png for output images.")
