package menger.optix.geometry

import menger.common.TriangleMeshData

object CubeGeometry:

  def generate(includeUVs: Boolean): TriangleMeshData =
    val stride = if includeUVs then 8 else 6
    
    // 24 vertices (4 per face * 6 faces) for separate normals/UVs per face
    val numVertices = 24
    val vertices = new Array[Float](numVertices * stride)
    
    // Helper to set vertex data
    def setVertex(idx: Int, x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, v: Float): Unit =
      val offset = idx * stride
      vertices(offset) = x
      vertices(offset + 1) = y
      vertices(offset + 2) = z
      vertices(offset + 3) = nx
      vertices(offset + 4) = ny
      vertices(offset + 5) = nz
      if includeUVs then
        vertices(offset + 6) = u
        vertices(offset + 7) = v

    // Front face (z=0.5)
    setVertex(0, -0.5f, -0.5f, 0.5f, 0f, 0f, 1f, 0f, 0f)
    setVertex(1, 0.5f, -0.5f, 0.5f, 0f, 0f, 1f, 1f, 0f)
    setVertex(2, 0.5f, 0.5f, 0.5f, 0f, 0f, 1f, 1f, 1f)
    setVertex(3, -0.5f, 0.5f, 0.5f, 0f, 0f, 1f, 0f, 1f)

    // Back face (z=-0.5)
    setVertex(4, 0.5f, -0.5f, -0.5f, 0f, 0f, -1f, 0f, 0f)
    setVertex(5, -0.5f, -0.5f, -0.5f, 0f, 0f, -1f, 1f, 0f)
    setVertex(6, -0.5f, 0.5f, -0.5f, 0f, 0f, -1f, 1f, 1f)
    setVertex(7, 0.5f, 0.5f, -0.5f, 0f, 0f, -1f, 0f, 1f)

    // Top face (y=0.5)
    setVertex(8, -0.5f, 0.5f, 0.5f, 0f, 1f, 0f, 0f, 0f)
    setVertex(9, 0.5f, 0.5f, 0.5f, 0f, 1f, 0f, 1f, 0f)
    setVertex(10, 0.5f, 0.5f, -0.5f, 0f, 1f, 0f, 1f, 1f)
    setVertex(11, -0.5f, 0.5f, -0.5f, 0f, 1f, 0f, 0f, 1f)

    // Bottom face (y=-0.5)
    setVertex(12, -0.5f, -0.5f, -0.5f, 0f, -1f, 0f, 0f, 0f)
    setVertex(13, 0.5f, -0.5f, -0.5f, 0f, -1f, 0f, 1f, 0f)
    setVertex(14, 0.5f, -0.5f, 0.5f, 0f, -1f, 0f, 1f, 1f)
    setVertex(15, -0.5f, -0.5f, 0.5f, 0f, -1f, 0f, 0f, 1f)

    // Right face (x=0.5)
    setVertex(16, 0.5f, -0.5f, 0.5f, 1f, 0f, 0f, 0f, 0f)
    setVertex(17, 0.5f, -0.5f, -0.5f, 1f, 0f, 0f, 1f, 0f)
    setVertex(18, 0.5f, 0.5f, -0.5f, 1f, 0f, 0f, 1f, 1f)
    setVertex(19, 0.5f, 0.5f, 0.5f, 1f, 0f, 0f, 0f, 1f)

    // Left face (x=-0.5)
    setVertex(20, -0.5f, -0.5f, -0.5f, -1f, 0f, 0f, 0f, 0f)
    setVertex(21, -0.5f, -0.5f, 0.5f, -1f, 0f, 0f, 1f, 0f)
    setVertex(22, -0.5f, 0.5f, 0.5f, -1f, 0f, 0f, 1f, 1f)
    setVertex(23, -0.5f, 0.5f, -0.5f, -1f, 0f, 0f, 0f, 1f)

    val indices = new Array[Int](36)
    for i <- 0 until 6 do
      val vOffset = i * 4
      val iOffset = i * 6
      indices(iOffset) = vOffset
      indices(iOffset + 1) = vOffset + 1
      indices(iOffset + 2) = vOffset + 2
      indices(iOffset + 3) = vOffset
      indices(iOffset + 4) = vOffset + 2
      indices(iOffset + 5) = vOffset + 3

    TriangleMeshData(vertices, indices, stride)
