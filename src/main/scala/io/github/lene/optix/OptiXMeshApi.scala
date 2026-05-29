package io.github.lene.optix

import menger.common.Color
import menger.common.Const
import menger.common.TriangleMeshData
import menger.common.Vector
import menger.common.x
import menger.common.y
import menger.common.z

private[optix] trait OptiXMeshApi:
  this: OptiXRenderer =>

  def setTriangleMesh(mesh: TriangleMeshData): Unit =
    setTriangleMeshNative(
      mesh.vertices,
      mesh.numVertices,
      mesh.indices,
      mesh.numTriangles,
      mesh.vertexStride
    )

  /** Upload a 4D face mesh; the GPU does rotation + perspective projection at
    * upload time (and on Cut F's update path). Returns the mesh index slot in
    * triangle_meshes[], or throws on validation failure.
    *
    * @param facesData length V*4*numFaces — N faces × V corners × (x,y,z,w)
    * @param vertsPerFace number of vertices per face (3=tri, 4=quad, 5=pentagon)
    * @param uvs UV coords, length vertsPerFace*2*numFaces; pass null for computed UVs
    * @return mesh index slot in triangle_meshes[]
    */
  def setProjectedMesh(
    facesData: Array[Float],
    vertsPerFace: Int,
    uvs: Array[Float],
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float,
    centerX: Float = 0f, centerY: Float = 0f, centerZ: Float = 0f
  ): Int =
    require(facesData != null, "facesData must not be null")  // scalafix:ok DisableSyntax.null
    require(vertsPerFace >= 3, s"vertsPerFace must be >= 3, got $vertsPerFace")
    val stride = vertsPerFace * 4
    require(facesData.length % stride == 0,
      s"facesData length must be a multiple of vertsPerFace*4 ($stride), got ${facesData.length}")
    require(facesData.length > 0, "facesData must not be empty")
    val numFaces = facesData.length / stride
    if uvs != null then // scalafix:ok DisableSyntax.null
      require(uvs.length == numFaces * vertsPerFace * 2,
        s"uvs length (${uvs.length}) must equal numFaces*vertsPerFace*2 (${numFaces * vertsPerFace * 2})")
    val result = setProjectedMeshNative(
      facesData, numFaces, vertsPerFace, uvs,
      eyeW, screenW, rotXW, rotYW, rotZW,
      centerX, centerY, centerZ
    )
    require(result >= 0, s"setProjectedMesh failed with code $result")
    result

  /** Backward-compatible alias. Pass null for uvs to use computed UVs.
    * @return mesh index slot in triangle_meshes[]
    */
  def setTriangleMesh4DQuads(
    quads4D: Array[Float],
    uvs: Array[Float],
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float,
    centerX: Float = 0f, centerY: Float = 0f, centerZ: Float = 0f
  ): Int =
    setProjectedMesh(quads4D, 4, uvs, eyeW, screenW, rotXW, rotYW, rotZW,
      centerX, centerY, centerZ)

  /** Re-project a previously-uploaded 4D-quad mesh with new rotation/projection
    * params, refitting its GAS (and the IAS, if active) in place. Throws on
    * native error (invalid index, mesh not 4D-projected, kernel launch failure).
    */
  def updateMesh4DProjection(
    meshIndex: Int,
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float,
    centerX: Float = 0f, centerY: Float = 0f, centerZ: Float = 0f
  ): Unit =
    require(meshIndex >= 0, s"meshIndex must be non-negative, got $meshIndex")
    val rc = updateMesh4DProjectionNative(
      meshIndex, eyeW, screenW, rotXW, rotYW, rotZW,
      centerX, centerY, centerZ
    )
    require(rc == 0, s"updateMesh4DProjection failed with code $rc (meshIndex=$meshIndex)")

  /** Replace the CPU-uploaded mesh in slot `meshIndex` with new vertex/index data,
    * rebuild its GAS in place, and mark the IAS dirty — without calling
    * clearAllInstances(). Used by the CPU-mode 4D-rotation fast path in
    * InteractiveEngine to avoid the full rebuild that causes a hang.
    * Throws if the mesh_index is invalid or the slot holds a GPU-projected mesh.
    */
  def updateCpuTriangleMesh(
    meshIndex: Int,
    mesh: menger.common.TriangleMeshData
  ): Unit =
    require(meshIndex >= 0, s"meshIndex must be non-negative, got $meshIndex")
    val rc = updateCpuTriangleMeshNative(
      meshIndex,
      mesh.vertices, mesh.numVertices,
      mesh.indices, mesh.numTriangles,
      mesh.vertexStride
    )
    require(rc == 0, s"updateCpuTriangleMesh failed with code $rc (meshIndex=$meshIndex)")

  def setTriangleMeshColor(color: Color): Unit =
    setTriangleMeshColorNative(color.r, color.g, color.b, color.a)

  /** @return instance ID (>= 0), or -1 on failure */
  def addTriangleMeshInstance(
    transform: Array[Float],
    material: Material,
    textureIndex: Int = -1
  ): Int =
    require(transform.length == Const.Renderer.transformMatrixSize, s"Transform must have ${Const.Renderer.transformMatrixSize} elements (4x3 matrix), got ${transform.length}")
    addTriangleMeshInstanceNative(
      transform,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      textureIndex, material.filmThickness
    )

  /** @return instance ID (>= 0), or -1 on failure */
  def addTriangleMeshInstance(
    transform: Array[Float],
    color: Color,
    ior: Float,
    textureIndex: Int
  ): Int =
    addTriangleMeshInstance(transform, Material(color, ior), textureIndex)

  /** @return instance ID (>= 0), or -1 on failure */
  def addTriangleMeshInstance(
    transform: Array[Float],
    color: Color,
    ior: Float
  ): Int =
    addTriangleMeshInstance(transform, Material(color, ior), -1)

  /** @return instance ID (>= 0), or -1 on failure */
  def addTriangleMeshInstance(
    position: Vector[3],
    material: Material,
    textureIndex: Int
  ): Int =
    val transform = Array(
      1.0f, 0.0f, 0.0f, position.x,
      0.0f, 1.0f, 0.0f, position.y,
      0.0f, 0.0f, 1.0f, position.z
    )
    addTriangleMeshInstance(transform, material, textureIndex)

  /** @return instance ID (>= 0), or -1 on failure */
  def addTriangleMeshInstance(
    position: Vector[3],
    color: Color,
    ior: Float,
    textureIndex: Int
  ): Int =
    addTriangleMeshInstance(position, Material(color, ior), textureIndex)

  /** @return instance ID (>= 0), or -1 on failure */
  def addTriangleMeshInstance(position: Vector[3], color: Color, ior: Float): Int =
    addTriangleMeshInstance(position, Material(color, ior), -1)

  /** Add a recursive-IAS Menger sponge instance (Sprint 18.4).
    *
    * Wraps the most-recently-uploaded triangle mesh (caller must have called
    * `setTriangleMesh` with a unit cube) in `level` nested IAS layers using
    * the 20 Menger generator transforms. VRAM scales O(level * 20) instead
    * of O(20^level).
    *
    * Constraint: do not deactivate any instance between this call and
    * the next render — the leaf-IAS instances embed a predicted instanceId
    * that becomes stale if the active-instance list shifts.
    *
    * @param level recursion depth; must be in [1, 14]
    */
  /** @return instance ID (>= 0), or -1 on failure */
  def addRecursiveIASSpongeInstance(
    level: Int,
    transform: Array[Float],
    material: Material,
    textureIndex: Int = -1
  ): Int =
    require(level >= 1 && level <= 14, s"Recursive IAS sponge level must be in [1, 14], got $level")
    require(transform.length == Const.Renderer.transformMatrixSize,
      s"Transform must have ${Const.Renderer.transformMatrixSize} elements (4x3 matrix), got ${transform.length}")
    addRecursiveIASSpongeInstanceNative(
      level, transform,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      textureIndex, material.filmThickness
    )

  /** @return instance ID (>= 0), or -1 on failure */
  def addRecursiveIASSpongeInstance(
    level: Int,
    position: Vector[3],
    material: Material,
    textureIndex: Int
  ): Int =
    val transform = Array(
      1.0f, 0.0f, 0.0f, position.x,
      0.0f, 1.0f, 0.0f, position.y,
      0.0f, 0.0f, 1.0f, position.z
    )
    addRecursiveIASSpongeInstance(level, transform, material, textureIndex)

  /** @return instance ID (>= 0), or -1 on failure */
  def addRecursiveIASSpongeInstance(level: Int, position: Vector[3], color: Color, ior: Float): Int =
    addRecursiveIASSpongeInstance(level, position, Material(color, ior), -1)

  /** @return instance ID (>= 0), or -1 on failure */
  def addCylinderInstance(
    p0: Vector[3],
    p1: Vector[3],
    radius: Float,
    material: Material
  ): Int =
    addCylinderInstanceNative(
      p0.x, p0.y, p0.z,
      p1.x, p1.y, p1.z,
      radius,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      material.filmThickness
    )

  /** @return instance ID (>= 0), or -1 on failure */
  def addCylinderInstance(
    p0: Vector[3],
    p1: Vector[3],
    radius: Float,
    color: Color,
    ior: Float
  ): Int =
    addCylinderInstance(p0, p1, radius, Material(color, ior))

  /** @return instance ID (>= 0), or -1 on failure */
  def addConeInstance(
    apex: Vector[3],
    base: Vector[3],
    radius: Float,
    material: Material
  ): Int =
    addConeInstanceNative(
      apex.x, apex.y, apex.z,
      base.x, base.y, base.z,
      radius,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      material.filmThickness
    )

  /** @return instance ID (>= 0), or -1 on failure */
  def addConeInstance(
    apex: Vector[3],
    base: Vector[3],
    radius: Float,
    color: Color,
    ior: Float
  ): Int =
    addConeInstance(apex, base, radius, Material(color, ior))

  /** @return instance ID (>= 0), or -1 on failure */
  def addMenger4DInstance(
    level: Int,
    distanceThreshold: Int,
    position: Vector[3],
    scale: Float,
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float,
    material: Material
  ): Int =
    addMenger4DInstanceNative(
      level, distanceThreshold,
      position.x, position.y, position.z, scale,
      eyeW, screenW, rotXW, rotYW, rotZW,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      material.filmThickness
    )

  def updateMenger4DProjection(
    instanceId: Int,
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float
  ): Unit =
    require(instanceId >= 0, s"instanceId must be non-negative, got $instanceId")
    val rc = updateMenger4DProjectionNative(instanceId, eyeW, screenW, rotXW, rotYW, rotZW)
    require(rc == 0, s"updateMenger4DProjection failed with code $rc (instanceId=$instanceId)")

  /** @return instance ID (>= 0), or -1 on failure */
  def addSierpinski4DInstance(
    level: Int,
    position: Vector[3],
    scale: Float,
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float,
    material: Material
  ): Int =
    addSierpinski4DInstanceNative(
      level,
      position.x, position.y, position.z, scale,
      eyeW, screenW, rotXW, rotYW, rotZW,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      material.filmThickness
    )

  def updateSierpinski4DProjection(
    instanceId: Int,
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float
  ): Unit =
    require(instanceId >= 0, s"instanceId must be non-negative, got $instanceId")
    val rc = updateSierpinski4DProjectionNative(instanceId, eyeW, screenW, rotXW, rotYW, rotZW)
    require(rc == 0, s"updateSierpinski4DProjection failed with code $rc (instanceId=$instanceId)")

  /** @return instance ID (>= 0), or -1 on failure */
  def addHexadecachoron4DInstance(
    level: Int,
    position: Vector[3],
    scale: Float,
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float,
    material: Material
  ): Int =
    addHexadecachoron4DInstanceNative(
      level,
      position.x, position.y, position.z, scale,
      eyeW, screenW, rotXW, rotYW, rotZW,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      material.filmThickness
    )

  def updateHexadecachoron4DProjection(
    instanceId: Int,
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float
  ): Unit =
    require(instanceId >= 0, s"instanceId must be non-negative, got $instanceId")
    val rc = updateHexadecachoron4DProjectionNative(instanceId, eyeW, screenW, rotXW, rotYW, rotZW)
    require(rc == 0, s"updateHexadecachoron4DProjection failed with code $rc (instanceId=$instanceId)")
