package io.github.lene.optix

import menger.common.Color
import menger.common.Const
import menger.common.TriangleMeshData
import menger.common.Vector
import menger.common.x
import menger.common.y
import menger.common.z

/** Mesh, primitive-instance, and 4D GPU-projection helpers exposed through [[OptiXRenderer]].
  *
  * Transform arrays are row-major 4x3 affine matrices with
  * [[menger.common.Const.Renderer.transformMatrixSize]] elements. An `add*` method returns
  * the new [[InstanceId]] or [[MeshSlot]] and throws [[OptiXException]] on native failure
  * (menger#110: it returned -1, which callers passed on as an index). Update methods throw
  * when native code reports an invalid id or failed projection update.
  */
private[optix] trait OptiXMeshApi:
  this: OptiXRenderer =>

  /** Uploads a triangle mesh to native scene state. Appends — does not replace an
    * already-uploaded mesh — and returns the new mesh's index.
    */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  def addTriangleMesh(mesh: TriangleMeshData): MeshSlot =
    val slot = setTriangleMeshNative(
      mesh.vertices,
      mesh.numVertices,
      mesh.indices,
      mesh.numTriangles,
      mesh.vertexStride
    )
    if slot < 0 then throw new OptiXException(s"addTriangleMesh failed with code $slot")
    MeshSlot(slot)

  /** @deprecated Use [[addTriangleMesh]]; the name "set" was misleading — this appends. */
  @deprecated("use addTriangleMesh; it appends and returns the new mesh's index", "0.4.1")
  def setTriangleMesh(mesh: TriangleMeshData): Unit =
    addTriangleMesh(mesh)
    ()

  /** Uploads a 4D face mesh and projects it on the GPU.
    *
    * The GPU applies rotation and perspective projection at upload time and on
    * later update paths. Returns the mesh index slot in the native triangle mesh
    * array, or throws on validation/native failure.
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
  ): MeshSlot =
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
    // -2: the projected vertices couldn't be synchronized or read back; nothing was registered.
    require(result >= 0, s"setProjectedMesh failed with code $result")
    MeshSlot(result)

  /** Backward-compatible quad-specific alias for [[setProjectedMesh]].
    *
    * Pass `null` for `uvs` to use computed UVs.
    *
    * @return mesh index slot in the native triangle mesh array
    */
  def setTriangleMesh4DQuads(
    quads4D: Array[Float],
    uvs: Array[Float],
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float,
    centerX: Float = 0f, centerY: Float = 0f, centerZ: Float = 0f
  ): MeshSlot =
    setProjectedMesh(quads4D, 4, uvs, eyeW, screenW, rotXW, rotYW, rotZW,
      centerX, centerY, centerZ)

  /** Re-projects a previously uploaded GPU-projected 4D mesh.
    *
    * Refits the mesh GAS, and the IAS if active, in place. Throws on native
    * error: invalid index, non-4D mesh slot, or projection kernel failure.
    */
  def updateMesh4DProjection(
    meshIndex: MeshSlot,
    eyeW: Float, screenW: Float,
    rotXW: Float, rotYW: Float, rotZW: Float,
    centerX: Float = 0f, centerY: Float = 0f, centerZ: Float = 0f
  ): Unit =
    val rc = updateMesh4DProjectionNative(
      meshIndex.value, eyeW, screenW, rotXW, rotYW, rotZW,
      centerX, centerY, centerZ
    )
    require(rc == 0, s"updateMesh4DProjection failed with code $rc (meshIndex=$meshIndex)")

  /** Replaces a CPU-uploaded mesh slot with new vertex/index data.
    *
    * Rebuilds its GAS in place and marks the IAS dirty without clearing all
    * instances. Throws when the index is invalid or the slot holds a GPU-projected
    * mesh.
    */
  def updateCpuTriangleMesh(
    meshIndex: MeshSlot,
    mesh: menger.common.TriangleMeshData
  ): Unit =
    val rc = updateCpuTriangleMeshNative(
      meshIndex.value,
      mesh.vertices, mesh.numVertices,
      mesh.indices, mesh.numTriangles,
      mesh.vertexStride
    )
    require(rc == 0, s"updateCpuTriangleMesh failed with code $rc (meshIndex=$meshIndex)")

  /** Sets the legacy single triangle-mesh RGBA color.
    *
    * @deprecated Use [[addTriangleMeshInstance]] with [[Material]] instead.
    */
  @deprecated("Use addTriangleMeshInstance with Material", "0.1.5")
  def setTriangleMeshColor(color: Color): Unit =
    setTriangleMeshColorNative(color.r, color.g, color.b, color.a)

  /** Adds a triangle-mesh IAS instance with an explicit transform.
    *
    * @return the new instance
    * @throws OptiXException on native failure
    */
  def addTriangleMeshInstance(
    transform: Array[Float],
    material: Material,
    textureIndex: TextureIndex = TextureIndex.NoTexture
  ): InstanceId =
    require(transform.length == Const.Renderer.transformMatrixSize, s"Transform must have ${Const.Renderer.transformMatrixSize} elements (4x3 matrix), got ${transform.length}")
    instanceOf("addTriangleMeshInstance",
      addTriangleMeshInstanceNative(transform, MaterialPayload.pack(material), textureIndex.value))

  /** Adds a triangle-mesh IAS instance from color, IOR, and texture index.
    *
    * @return the new instance
    * @throws OptiXException on native failure
    */
  def addTriangleMeshInstance(
    transform: Array[Float],
    color: Color,
    ior: Float,
    textureIndex: TextureIndex
  ): InstanceId =
    addTriangleMeshInstance(transform, Material(color, ior), textureIndex)

  /** Adds a triangle-mesh IAS instance from color and IOR.
    *
    * @return the new instance
    * @throws OptiXException on native failure
    */
  def addTriangleMeshInstance(
    transform: Array[Float],
    color: Color,
    ior: Float
  ): InstanceId =
    addTriangleMeshInstance(transform, Material(color, ior), TextureIndex.NoTexture)

  /** Adds a translated triangle-mesh IAS instance with a texture index.
    *
    * @return the new instance
    * @throws OptiXException on native failure
    */
  def addTriangleMeshInstance(
    position: Vector[3],
    material: Material,
    textureIndex: TextureIndex
  ): InstanceId =
    val transform = Array(
      1.0f, 0.0f, 0.0f, position.x,
      0.0f, 1.0f, 0.0f, position.y,
      0.0f, 0.0f, 1.0f, position.z
    )
    addTriangleMeshInstance(transform, material, textureIndex)

  /** Adds a translated triangle-mesh IAS instance from color, IOR, and texture index.
    *
    * @return the new instance
    * @throws OptiXException on native failure
    */
  def addTriangleMeshInstance(
    position: Vector[3],
    color: Color,
    ior: Float,
    textureIndex: TextureIndex
  ): InstanceId =
    addTriangleMeshInstance(position, Material(color, ior), textureIndex)

  /** Adds a translated triangle-mesh IAS instance from color and IOR.
    *
    * @return the new instance
    * @throws OptiXException on native failure
    */
  def addTriangleMeshInstance(position: Vector[3], color: Color, ior: Float): InstanceId =
    addTriangleMeshInstance(position, Material(color, ior), TextureIndex.NoTexture)

  /** Adds a recursive-IAS Menger sponge instance with an explicit transform.
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
    * @return the new instance
    * @throws OptiXException on native failure
    */
  def addRecursiveIASSpongeInstance(
    level: Int,
    transform: Array[Float],
    material: Material,
    textureIndex: TextureIndex = TextureIndex.NoTexture
  ): InstanceId =
    require(level >= 1 && level <= 14, s"Recursive IAS sponge level must be in [1, 14], got $level")
    require(transform.length == Const.Renderer.transformMatrixSize,
      s"Transform must have ${Const.Renderer.transformMatrixSize} elements (4x3 matrix), got ${transform.length}")
    instanceOf("addRecursiveIASSpongeInstance",
      addRecursiveIASSpongeInstanceNative(level, transform, MaterialPayload.pack(material), textureIndex.value))

  /** Adds a recursive-IAS sponge from a translated unit transform.
    *
    * @return the new instance
    * @throws OptiXException on native failure
    */
  def addRecursiveIASSpongeInstance(
    level: Int,
    position: Vector[3],
    material: Material,
    textureIndex: TextureIndex
  ): InstanceId =
    val transform = Array(
      1.0f, 0.0f, 0.0f, position.x,
      0.0f, 1.0f, 0.0f, position.y,
      0.0f, 0.0f, 1.0f, position.z
    )
    addRecursiveIASSpongeInstance(level, transform, material, textureIndex)

  /** Adds a recursive-IAS sponge from translated color and IOR.
    *
    * @return the new instance
    * @throws OptiXException on native failure
    */
  def addRecursiveIASSpongeInstance(level: Int, position: Vector[3], color: Color, ior: Float): InstanceId =
    addRecursiveIASSpongeInstance(level, position, Material(color, ior), TextureIndex.NoTexture)

  /** Adds a cylinder IAS instance between two endpoints.
    *
    * @param radius cylinder radius in world units
    * @return the new instance
    * @throws OptiXException on native failure
    */
  def addCylinderInstance(
    p0: Vector[3],
    p1: Vector[3],
    radius: Float,
    material: Material
  ): InstanceId =
    instanceOf("addCylinderInstance", addCylinderInstanceNative(
      p0.x, p0.y, p0.z,
      p1.x, p1.y, p1.z,
      radius,
      MaterialPayload.pack(material)
    ))

  /** Moves existing cylinder instances in place: new endpoints and radius per instance.
    *
    * Refits each cylinder's GAS and the IAS once instead of rebuilding the scene, for
    * per-frame updates such as interactively rotating an edge-rendered 4D object. `p0s`
    * and `p1s` hold 3 floats per cylinder. Nothing changes if any entry is invalid (an id
    * that is not a cylinder, non-finite endpoints, radius <= 0): that throws, as does a
    * native failure.
    */
  def updateCylinderInstances(
    ids: Array[InstanceId],
    p0s: Array[Float],
    p1s: Array[Float],
    radii: Array[Float]
  ): Unit =
    require(
      p0s.length == 3 * ids.length && p1s.length == 3 * ids.length && radii.length == ids.length,
      s"updateCylinderInstances: expected ${ids.length} ids, ${3 * ids.length} floats per endpoint " +
        s"array and ${ids.length} radii, got ${p0s.length}/${p1s.length}/${radii.length}"
    )
    val rc = updateCylinderInstancesNative(ids.values, p0s, p1s, radii)
    require(rc == 0, s"updateCylinderInstances failed with code $rc")

  /** Adds a cylinder IAS instance from color and IOR.
    *
    * @return the new instance
    * @throws OptiXException on native failure
    */
  def addCylinderInstance(
    p0: Vector[3],
    p1: Vector[3],
    radius: Float,
    color: Color,
    ior: Float
  ): InstanceId =
    addCylinderInstance(p0, p1, radius, Material(color, ior))

  /** Adds a cone IAS instance from apex to base center.
    *
    * @param radius base radius in world units
    * @return the new instance
    * @throws OptiXException on native failure
    */
  def addConeInstance(
    apex: Vector[3],
    base: Vector[3],
    radius: Float,
    material: Material
  ): InstanceId =
    instanceOf("addConeInstance", addConeInstanceNative(
      apex.x, apex.y, apex.z,
      base.x, base.y, base.z,
      radius,
      MaterialPayload.pack(material)
    ))

  /** Adds a cone IAS instance from color and IOR.
    *
    * @return the new instance
    * @throws OptiXException on native failure
    */
  def addConeInstance(
    apex: Vector[3],
    base: Vector[3],
    radius: Float,
    color: Color,
    ior: Float
  ): InstanceId =
    addConeInstance(apex, base, radius, Material(color, ior))

  /** Adds a round cubic B-spline curve IAS instance.
    *
    * Points are dense xyz control points in world space. Widths are OptiX curve
    * radii, one per control point.
    *
    * @return the new instance
    * @throws OptiXException on native failure
    */
  def addCurveInstance(
    points: Array[Float],
    widths: Array[Float],
    material: Material
  ): InstanceId =
    require(points != null, "points must not be null") // scalafix:ok DisableSyntax.null
    require(widths != null, "widths must not be null") // scalafix:ok DisableSyntax.null
    require(points.length % 3 == 0, s"points length must be a multiple of 3, got ${points.length}")
    val numPoints = points.length / 3
    require(numPoints >= 4, s"Curve must have at least 4 control points, got $numPoints")
    require(widths.length == numPoints,
      s"widths length (${widths.length}) must equal control point count ($numPoints)")
    require(points.forall(java.lang.Float.isFinite), "points must all be finite")
    require(widths.forall(width => java.lang.Float.isFinite(width) && width > 0.0f),
      "widths must all be finite and > 0")
    instanceOf("addCurveInstance", addCurveInstanceNative(
      points,
      widths,
      numPoints,
      MaterialPayload.pack(material)
    ))

  // 4D fractal instances (Menger/Sierpinski/Hexadecachoron) were removed from
  // optix-jni in Sprint 35 Task 1.2 — they now live in menger-geometry, registered
  // through the generic custom-geometry SPI (registerCustomGeometry).
