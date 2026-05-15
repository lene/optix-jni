package menger.optix

import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files

import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.Exception.catching

import com.typesafe.scalalogging.LazyLogging
import menger.common.Color
import menger.common.Const
import menger.common.ImageSize
import menger.common.TriangleMeshData
import menger.common.Vector
import menger.common.toArray
import menger.common.x
import menger.common.y
import menger.common.z
import menger.common.{Light => CommonLight}

// Light source types (must match C++ LightType enum)
private object LightTypeJNI:
  val DIRECTIONAL: Int = 0  // Parallel rays from infinity (sun-like), no distance attenuation
  val POINT: Int = 1         // Radiate from position, inverse-square falloff
  val AREA: Int = 2          // Finite-size disk emitter, soft shadows via multi-sample

// Area light shape (must match C++ AreaLightShape enum)
object AreaLightShape:
  val DISK: Int = 0  // Circular disk emitter (only shape currently supported)

// Package-private case class for JNI boundary (matches C++ expectations)
// Must be accessible to JNI (not private) but kept in menger.optix package
// The C++ code looks for "menger/optix/Light" class
case class Light(
  lightType: Int,
  direction: Array[Float],
  position: Array[Float],
  color: Array[Float],
  intensity: Float,
  shape: Int = AreaLightShape.DISK,
  normal: Array[Float] = Array(0f, -1f, 0f),
  radius: Float = 1.0f,
  shadowSamples: Int = 4
)

// Private helper to convert common.Light to JNI representation
private def toJNILight(light: CommonLight): Light =
  light match
    case CommonLight.Directional(dir, color, intensity) =>
      Light(
        lightType = LightTypeJNI.DIRECTIONAL,
        direction = dir.toArray,
        position = Array(0f, 0f, 0f),  // Unused for directional
        color = color.toRGBArray,
        intensity = intensity
      )
    case CommonLight.Point(pos, color, intensity) =>
      Light(
        lightType = LightTypeJNI.POINT,
        direction = Array(0f, 0f, 0f),  // Unused for point
        position = pos.toArray,
        color = color.toRGBArray,
        intensity = intensity
      )
    case CommonLight.Area(pos, norm, radius, shape, color, intensity, samples) =>
      Light(
        lightType = LightTypeJNI.AREA,
        direction = Array(0f, 0f, 0f),  // Unused for area
        position = pos.toArray,
        color = color.toRGBArray,
        intensity = intensity,
        shape = shape.id,
        normal = norm.toArray,
        radius = radius,
        shadowSamples = samples
      )

// Ray statistics from OptiX rendering
case class RayStats(
  totalRays: Long,
  primaryRays: Long,
  reflectedRays: Long,
  refractedRays: Long,
  shadowRays: Long,
  aaRays: Long,
  aaStackOverflows: Long,
  maxDepthReached: Int,
  minDepthReached: Int,
  frameMs: Float
):
  /** Milliseconds per million rays (0 if no rays traced). */
  def msPerMray: Float =
    if totalRays == 0L then 0.0f
    else frameMs / (totalRays.toFloat / 1_000_000f)

// Caustics statistics for validation (C1-C8 test ladder)
// Matches CausticsStats struct in OptiXData.h
case class CausticsStats(
  // C1: Photon emission
  photonsEmitted: Long,
  photonsTowardSphere: Long,
  // C2: Sphere hit rate
  sphereHits: Long,
  sphereMisses: Long,
  // C3: Refraction events
  refractionEvents: Long,
  tirEvents: Long,
  // C4: Deposition
  photonsDeposited: Long,
  hitPointsWithFlux: Long,
  // C5: Energy conservation
  totalFluxEmitted: Double,
  totalFluxDeposited: Double,
  totalFluxAbsorbed: Double,
  totalFluxReflected: Double,
  // C6: Convergence metrics
  avgRadius: Float,
  minRadius: Float,
  maxRadius: Float,
  fluxVariance: Float,
  // C7: Brightness metrics
  maxCausticBrightness: Float,
  avgFloorBrightness: Float,
  // Timing
  hitPointGenerationMs: Float,
  photonTracingMs: Float,
  radianceComputationMs: Float
):
  // Derived metrics for validation
  def sphereHitRate: Double =
    if photonsTowardSphere > 0 then sphereHits.toDouble / photonsTowardSphere else 0.0

  def energyConservationError: Double =
    if totalFluxEmitted > 0 then
      val totalOutput = totalFluxDeposited + totalFluxAbsorbed + totalFluxReflected
      math.abs(totalOutput - totalFluxEmitted) / totalFluxEmitted
    else 0.0

  def fresnelTransmission: Double =
    if totalFluxEmitted > 0 then totalFluxDeposited / totalFluxEmitted else 0.0

// Combined result from rendering with statistics
case class RenderResult(
  image: Array[Byte],
  totalRays: Long,
  primaryRays: Long,
  reflectedRays: Long,
  refractedRays: Long,
  shadowRays: Long,
  aaRays: Long,
  aaStackOverflows: Long,
  maxDepthReached: Int,
  minDepthReached: Int,
  frameMs: Float
):
  def stats: RayStats = RayStats(
    totalRays,
    primaryRays,
    reflectedRays,
    refractedRays,
    shadowRays,
    aaRays,
    aaStackOverflows,
    maxDepthReached,
    minDepthReached,
    frameMs
  )

// JNI interface to OptiX ray tracing renderer
class OptiXRenderer extends LazyLogging:

  // Native handle to the C++ OptiXWrapper instance (0 = not initialized)
  // Note: Must be accessible to JNI (not private) - JNI reads/writes this field directly
  // This var is required by the JNI handle pattern - no functional alternative exists
  @SuppressWarnings(Array("org.wartremover.warts.Var"))
  @volatile var nativeHandle: Long = 0L

  // Derive initialization state from nativeHandle (0 = not initialized)
  private def isInitialized: Boolean = nativeHandle != 0L

  // Native method declarations (private - use public wrappers)
  @native private def initializeNative(maxInstances: Int): Boolean
  @native private def disposeNative(): Unit


  @native private def setSphere(x: Float, y: Float, z: Float, radius: Float): Unit

  def setSphere(center: Vector[3], radius: Float): Unit =
    setSphere(center.x, center.y, center.z, radius)

  
  @native private def setSphereColorNative(r: Float, g: Float, b: Float, a: Float): Unit

  def setSphereColor(color: Color): Unit =
    setSphereColorNative(color.r, color.g, color.b, color.a)


  @native def setIOR(ior: Float): Unit

  
  @native def setScale(scale: Float): Unit


  @native private def setCameraNative(eye: Array[Float], lookAt: Array[Float], up: Array[Float], horizontalFovDegrees: Float): Unit

  def setCamera(eye: Vector[3], lookAt: Vector[3], up: Vector[3], horizontalFovDegrees: Float): Unit =
    require(
      horizontalFovDegrees > 0 && horizontalFovDegrees < 180,
      s"Horizontal FOV must be in range (0, 180), got $horizontalFovDegrees"
    )
    setCameraNative(eye.toArray, lookAt.toArray, up.toArray, horizontalFovDegrees)


  @native def updateImageDimensions(width: Int, height: Int): Unit

  def updateImageDimensions(size: ImageSize): Unit =
    updateImageDimensions(size.width, size.height)


  @native private def setLight(direction: Array[Float], intensity: Float): Unit

  def setLight(direction: Vector[3], intensity: Float): Unit =
    setLight(direction.toArray, intensity)

  @native private def setLights(lights: Array[Light]): Unit

  def setLights(lights: Seq[CommonLight]): Unit =
    val jniLights = lights.map(toJNILight).toArray
    setLights(jniLights)

  
  @native def setShadows(enabled: Boolean): Unit

  @native private def setTransparentShadowsNative(enabled: Boolean): Unit

  def setTransparentShadows(enabled: Boolean): Unit =
    setTransparentShadowsNative(enabled)

  @native private def setBackgroundColorNative(r: Float, g: Float, b: Float): Unit

  def setBackgroundColor(r: Float, g: Float, b: Float): Unit =
    setBackgroundColorNative(r, g, b)

  @native private def setEnvironmentMapNative(textureIndex: Int): Unit

  def setEnvironmentMap(textureIndex: Int): Unit =
    require(textureIndex >= 0, "textureIndex must be >= 0")
    setEnvironmentMapNative(textureIndex)


  @native def setAntialiasing(enabled: Boolean, maxDepth: Int, threshold: Float): Unit

  @native def setMaxRayDepth(depth: Int): Unit

  def setRenderConfig(config: RenderConfig): Unit =
    setShadows(config.shadows)
    setTransparentShadows(config.transparentShadows)
    setAntialiasing(config.antialiasing, config.aaMaxDepth, config.aaThreshold)
    setMaxRayDepth(config.maxRayDepth)

  @native def setCaustics(enabled: Boolean, photonsPerIter: Int, iterations: Int, initialRadius: Float, alpha: Float): Unit

  def setCausticsConfig(config: CausticsConfig): Unit =
    setCaustics(config.enabled, config.photonsPerIteration, config.iterations, config.initialRadius, config.alpha)

  // Convenience method with default PPM parameters
  def enableCaustics(
    photonsPerIter: Int = 100000,
    iterations: Int = 10,
    initialRadius: Float = 1.0f,
    alpha: Float = 0.7f
  ): Unit =
    setCaustics(true, photonsPerIter, iterations, initialRadius, alpha)

  def disableCaustics(): Unit =
    setCaustics(false, 0, 0, 0.0f, 0.0f)

  // Get caustics statistics for validation (C1-C8 test ladder)
  // Returns None if caustics are disabled or stats not available
  @native private def getCausticsStatsNative(): CausticsStats

  def getCausticsStats: Option[CausticsStats] =
    Try(getCausticsStatsNative()).toOption.filter(_.photonsEmitted > 0)

  @native private def clearPlanesNative(): Unit
  @native private def addPlaneNative(axis: Int, positive: Boolean, value: Float): Unit
  @native private def addPlaneSolidColorNative(
    axis: Int, positive: Boolean, value: Float, r: Float, g: Float, b: Float): Unit
  @native private def addPlaneCheckerColorsNative(
    axis: Int, positive: Boolean, value: Float,
    r1: Float, g1: Float, b1: Float, r2: Float, g2: Float, b2: Float): Unit
  @native private def addPlaneSolidColorWithMaterialNative(
    axis: Int, positive: Boolean, value: Float,
    r: Float, g: Float, b: Float,
    roughness: Float, metallic: Float, specular: Float, emission: Float,
    textureIndex: Int): Unit
  @native private def addPlaneCheckerColorsWithMaterialNative(
    axis: Int, positive: Boolean, value: Float,
    r1: Float, g1: Float, b1: Float, r2: Float, g2: Float, b2: Float,
    roughness: Float, metallic: Float, specular: Float, emission: Float,
    textureIndex: Int): Unit

  def clearPlanes(): Unit = clearPlanesNative()

  def addPlane(axis: Int, positive: Boolean, value: Float): Unit = addPlaneNative(axis, positive, value)

  def addPlaneSolidColor(axis: Int, positive: Boolean, value: Float, r: Float, g: Float, b: Float): Unit =
    addPlaneSolidColorNative(axis, positive, value, r, g, b)

  def addPlaneCheckerColors(axis: Int, positive: Boolean, value: Float,
                            r1: Float, g1: Float, b1: Float,
                            r2: Float, g2: Float, b2: Float): Unit =
    addPlaneCheckerColorsNative(axis, positive, value, r1, g1, b1, r2, g2, b2)

  def addPlaneSolidColorWithMaterial(
    axis: Int, positive: Boolean, value: Float,
    color: Color, material: Material, textureIndex: Int = -1): Unit =
    addPlaneSolidColorWithMaterialNative(
      axis, positive, value,
      color.r, color.g, color.b,
      material.roughness, material.metallic, material.specular, material.emission,
      textureIndex)

  def addPlaneCheckerColorsWithMaterial(
    axis: Int, positive: Boolean, value: Float,
    color1: Color, color2: Color, material: Material, textureIndex: Int = -1): Unit =
    addPlaneCheckerColorsWithMaterialNative(
      axis, positive, value,
      color1.r, color1.g, color1.b,
      color2.r, color2.g, color2.b,
      material.roughness, material.metallic, material.specular, material.emission,
      textureIndex)

  // Triangle mesh methods
  @native private def setTriangleMeshNative(
    vertices: Array[Float],
    numVertices: Int,
    indices: Array[Int],
    numTriangles: Int,
    vertexStride: Int
  ): Unit

  // Sprint 18.3 Cut A: GPU-side 4D rotation + projection. Returns mesh index.
  // Generalized: vertsPerFace allows non-quad faces (3=tris, 5=pentagons).
  @native private def setProjectedMeshNative(
    facesData: Array[Float],
    numFaces: Int,
    vertsPerFace: Int,
    uvs: Array[Float],         // null permitted
    eyeW: Float,
    screenW: Float,
    rotXW: Float,
    rotYW: Float,
    rotZW: Float,
    centerX: Float,
    centerY: Float,
    centerZ: Float
  ): Int

  // Sprint 18.3 Cut F: per-frame update of 4D rotation + projection.
  @native private def updateMesh4DProjectionNative(
    meshIndex: Int,
    eyeW: Float,
    screenW: Float,
    rotXW: Float,
    rotYW: Float,
    rotZW: Float,
    centerX: Float,
    centerY: Float,
    centerZ: Float
  ): Int

  @native private def setTriangleMeshColorNative(r: Float, g: Float, b: Float, a: Float): Unit

  @native def setTriangleMeshIOR(ior: Float): Unit

  @native def clearTriangleMesh(): Unit

  @native def hasTriangleMesh(): Boolean

  // Texture methods
  @native private def uploadTextureNative(
    name: String,
    imageData: Array[Byte],
    width: Int,
    height: Int
  ): Int

  @native private def uploadTextureFromFileNative(path: String): Int

  @native private def releaseTexturesNative(): Unit

  def uploadTexture(name: String, imageData: Array[Byte], width: Int, height: Int): Try[Int] =
    // JNI boundary validation - null checks required for native method safety
    require(name != null && name.nonEmpty, "Texture name must not be null or empty") // scalafix:ok DisableSyntax.null
    require(imageData != null, "Image data must not be null") // scalafix:ok DisableSyntax.null
    require(width > 0, s"Width must be positive, got $width")
    require(height > 0, s"Height must be positive, got $height")
    val expectedSize = width * height * 4  // RGBA, 4 bytes per pixel
    require(
      imageData.length == expectedSize,
      s"Image data size mismatch: expected $expectedSize bytes (${width}x${height}x4), got ${imageData.length}"
    )
    val index = uploadTextureNative(name, imageData, width, height)
    if index < 0 then
      Failure(TextureUploadException(s"Failed to upload texture '$name': error code $index"))
    else
      Success(index)

  def uploadTextureFromFile(path: String): Int =
    require(path != null && path.nonEmpty, "Path must not be null or empty") // scalafix:ok DisableSyntax.null
    uploadTextureFromFileNative(path)

  def releaseTextures(): Unit =
    releaseTexturesNative()

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
    * @param uvs optional length vertsPerFace*2*numFaces; None uses computed UVs
    */
  def setProjectedMesh(
    facesData: Array[Float],
    vertsPerFace: Int,
    uvs: Option[Array[Float]],
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
    uvs.foreach { u =>
      require(u.length == numFaces * vertsPerFace * 2,
        s"uvs length (${u.length}) must equal numFaces*vertsPerFace*2 (${numFaces * vertsPerFace * 2})")
    }
    val result = setProjectedMeshNative(
      facesData, numFaces, vertsPerFace, uvs.orNull,
      eyeW, screenW, rotXW, rotYW, rotZW,
      centerX, centerY, centerZ
    )
    require(result >= 0, s"setProjectedMesh failed with code $result")
    result

  // Backward-compatible alias
  def setTriangleMesh4DQuads(
    quads4D: Array[Float],
    uvs: Option[Array[Float]],
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

  @native private def updateCpuTriangleMeshNative(
    meshIndex: Int,
    vertices: Array[Float], numVertices: Int,
    indices: Array[Int], numTriangles: Int,
    vertexStride: Int
  ): Int

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

  @native def renderWithStats(width: Int, height: Int): RenderResult

  def renderWithStats(size: ImageSize): RenderResult =
    val startNs = System.nanoTime()
    val raw = renderWithStats(size.width, size.height)
    val elapsedMs = (System.nanoTime() - startNs).toFloat / 1_000_000f
    Option(raw).map(_.copy(frameMs = elapsedMs)).orNull


  def render(size: ImageSize): Option[Array[Byte]] =
    Option(renderWithStats(size)).map(_.image)

  def render(width: Int, height: Int): Option[Array[Byte]] =
    render(ImageSize(width, height))

  // Instance Acceleration Structure (IAS) API for multi-object scenes
  @native private def addSphereInstanceNative(
    transform: Array[Float],
    r: Float,
    g: Float,
    b: Float,
    a: Float,
    ior: Float,
    roughness: Float,
    metallic: Float,
    specular: Float,
    emission: Float,
    filmThickness: Float
  ): Int

  @native private def addTriangleMeshInstanceNative(
    transform: Array[Float],
    r: Float,
    g: Float,
    b: Float,
    a: Float,
    ior: Float,
    roughness: Float,
    metallic: Float,
    specular: Float,
    emission: Float,
    textureIndex: Int,
    filmThickness: Float
  ): Int

  @native private def addCylinderInstanceNative(
    p0_x: Float, p0_y: Float, p0_z: Float,
    p1_x: Float, p1_y: Float, p1_z: Float,
    radius: Float,
    r: Float,
    g: Float,
    b: Float,
    a: Float,
    ior: Float,
    roughness: Float,
    metallic: Float,
    specular: Float,
    emission: Float,
    filmThickness: Float
  ): Int

  @native private def addConeInstanceNative(
    apex_x: Float, apex_y: Float, apex_z: Float,
    base_x: Float, base_y: Float, base_z: Float,
    radius: Float,
    r: Float,
    g: Float,
    b: Float,
    a: Float,
    ior: Float,
    roughness: Float,
    metallic: Float,
    specular: Float,
    emission: Float,
    filmThickness: Float
  ): Int

  @native private def addRecursiveIASSpongeInstanceNative(
    level: Int,
    transform: Array[Float],
    r: Float,
    g: Float,
    b: Float,
    a: Float,
    ior: Float,
    roughness: Float,
    metallic: Float,
    specular: Float,
    emission: Float,
    textureIndex: Int,
    filmThickness: Float
  ): Int

  @native def removeInstance(instanceId: Int): Unit

  @native def clearAllInstances(): Unit

  @native def getInstanceCount(): Int

  @native def isIASMode(): Boolean

  @native def setIASMode(enabled: Boolean): Unit

  def addSphereInstance(transform: Array[Float], material: Material): Option[Int] =
    require(transform.length == Const.Renderer.transformMatrixSize, s"Transform must have ${Const.Renderer.transformMatrixSize} elements (4x3 matrix), got ${transform.length}")
    val id = addSphereInstanceNative(
      transform,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      material.filmThickness
    )
    if id >= 0 then Some(id) else None

  def addSphereInstance(transform: Array[Float], color: Color, ior: Float): Option[Int] =
    addSphereInstance(transform, Material(color, ior))

  def addSphereInstance(position: Vector[3], material: Material): Option[Int] =
    val transform = Array(
      1.0f, 0.0f, 0.0f, position.x,
      0.0f, 1.0f, 0.0f, position.y,
      0.0f, 0.0f, 1.0f, position.z
    )
    addSphereInstance(transform, material)

  def addSphereInstance(position: Vector[3], color: Color, ior: Float): Option[Int] =
    addSphereInstance(position, Material(color, ior))

  def addTriangleMeshInstance(
    transform: Array[Float],
    material: Material,
    textureIndex: Int = -1
  ): Option[Int] =
    require(transform.length == Const.Renderer.transformMatrixSize, s"Transform must have ${Const.Renderer.transformMatrixSize} elements (4x3 matrix), got ${transform.length}")
    val id = addTriangleMeshInstanceNative(
      transform,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      textureIndex, material.filmThickness
    )
    if id >= 0 then Some(id) else None

  def addTriangleMeshInstance(
    transform: Array[Float],
    color: Color,
    ior: Float,
    textureIndex: Int
  ): Option[Int] =
    addTriangleMeshInstance(transform, Material(color, ior), textureIndex)

  def addTriangleMeshInstance(
    transform: Array[Float],
    color: Color,
    ior: Float
  ): Option[Int] =
    addTriangleMeshInstance(transform, Material(color, ior), -1)

  def addTriangleMeshInstance(
    position: Vector[3],
    material: Material,
    textureIndex: Int
  ): Option[Int] =
    val transform = Array(
      1.0f, 0.0f, 0.0f, position.x,
      0.0f, 1.0f, 0.0f, position.y,
      0.0f, 0.0f, 1.0f, position.z
    )
    addTriangleMeshInstance(transform, material, textureIndex)

  def addTriangleMeshInstance(
    position: Vector[3],
    color: Color,
    ior: Float,
    textureIndex: Int
  ): Option[Int] =
    addTriangleMeshInstance(position, Material(color, ior), textureIndex)

  def addTriangleMeshInstance(position: Vector[3], color: Color, ior: Float): Option[Int] =
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
  def addRecursiveIASSpongeInstance(
    level: Int,
    transform: Array[Float],
    material: Material,
    textureIndex: Int = -1
  ): Option[Int] =
    require(level >= 1 && level <= 14, s"Recursive IAS sponge level must be in [1, 14], got $level")
    require(transform.length == Const.Renderer.transformMatrixSize,
      s"Transform must have ${Const.Renderer.transformMatrixSize} elements (4x3 matrix), got ${transform.length}")
    val id = addRecursiveIASSpongeInstanceNative(
      level, transform,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      textureIndex, material.filmThickness
    )
    if id >= 0 then Some(id) else None

  def addRecursiveIASSpongeInstance(
    level: Int,
    position: Vector[3],
    material: Material,
    textureIndex: Int
  ): Option[Int] =
    val transform = Array(
      1.0f, 0.0f, 0.0f, position.x,
      0.0f, 1.0f, 0.0f, position.y,
      0.0f, 0.0f, 1.0f, position.z
    )
    addRecursiveIASSpongeInstance(level, transform, material, textureIndex)

  def addRecursiveIASSpongeInstance(level: Int, position: Vector[3], color: Color, ior: Float): Option[Int] =
    addRecursiveIASSpongeInstance(level, position, Material(color, ior), -1)

  // Cylinder instance management
  def addCylinderInstance(
    p0: Vector[3],
    p1: Vector[3],
    radius: Float,
    material: Material
  ): Option[Int] =
    val id = addCylinderInstanceNative(
      p0.x, p0.y, p0.z,
      p1.x, p1.y, p1.z,
      radius,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      material.filmThickness
    )
    if id >= 0 then Some(id) else None

  def addCylinderInstance(
    p0: Vector[3],
    p1: Vector[3],
    radius: Float,
    color: Color,
    ior: Float
  ): Option[Int] =
    addCylinderInstance(p0, p1, radius, Material(color, ior))

  // Cone instance management
  def addConeInstance(
    apex: Vector[3],
    base: Vector[3],
    radius: Float,
    material: Material
  ): Option[Int] =
    val id = addConeInstanceNative(
      apex.x, apex.y, apex.z,
      base.x, base.y, base.z,
      radius,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      material.filmThickness
    )
    if id >= 0 then Some(id) else None

  def addConeInstance(
    apex: Vector[3],
    base: Vector[3],
    radius: Float,
    color: Color,
    ior: Float
  ): Option[Int] =
    addConeInstance(apex, base, radius, Material(color, ior))

  @native private def addPlaneInstanceNative(
    normal_x: Float, normal_y: Float, normal_z: Float,
    distance: Float,
    r: Float, g: Float, b: Float, a: Float, ior: Float,
    roughness: Float, metallic: Float, specular: Float, emission: Float,
    filmThickness: Float,
    r2: Float, g2: Float, b2: Float,
    solidColor: Int, checkerSize: Float
  ): Int

  def addPlaneInstance(
    normal: Vector[3],
    distance: Float,
    material: Material,
    checkerColor: Option[Color] = None,
    checkerSize: Float = 1.0f
  ): Option[Int] =
    val (r2, g2, b2, solidColor) = checkerColor match
      case Some(c) => (c.r, c.g, c.b, 0)
      case None    => (0f, 0f, 0f, 1)
    val id = addPlaneInstanceNative(
      normal.x, normal.y, normal.z,
      distance,
      material.color.r, material.color.g, material.color.b, material.color.a,
      material.ior, material.roughness, material.metallic, material.specular, material.emission,
      material.filmThickness,
      r2, g2, b2, solidColor, checkerSize
    )
    if id >= 0 then Some(id) else None

  def addPlaneInstance(
    normal: Vector[3],
    distance: Float,
    color: Color,
    ior: Float
  ): Option[Int] =
    addPlaneInstance(normal, distance, Material(color, ior), None, 1.0f)

  // Idempotent initialization - safe to call multiple times
  def initialize(maxInstances: Int = 64): Boolean =
    if isInitialized then
      true  // Already initialized, return success
    else
      val result = initializeNative(maxInstances)
      if !result then
        logger.error("Failed to initialize OptiX renderer")
      result

  /**
   * Reinitialize the renderer with a new maxInstances value.
   * Disposes the current renderer and creates a new one.
   * Use this when auto-adjustment determines a higher limit is needed.
   */
  def reinitialize(newMaxInstances: Int): Boolean =
    if isInitialized then
      dispose()
    initialize(newMaxInstances)

  // Can be re-initialized after dispose by calling initialize() again
  def dispose(): Unit =
    if isInitialized then
      disposeNative()

  def isAvailable: Boolean =
    Try(initialize()).recover:
      case e: UnsatisfiedLinkError =>
        logger.warn("OptiX native library not available", e)
        false
      case e: Exception =>
        logger.error("Error checking OptiX availability", e)
        false
    .getOrElse(false)

  // Ensures OptiX is available, returns Try with error details
  def ensureAvailable(): Try[OptiXRenderer] =
    if !OptiXRenderer.isLibraryLoaded then
      Failure(OptiXNotAvailableException(
        "OptiX native library failed to load - ensure CUDA and OptiX are available"
      ))
    else if !initialize() then
      Failure(OptiXNotAvailableException("Failed to initialize OptiX renderer"))
    else
      Success(this)

object OptiXRenderer extends LazyLogging:
  private val libraryName = "optixjni"

  private val libraryLoaded: Boolean = loadNativeLibrary().isSuccess

  def isLibraryLoaded: Boolean = libraryLoaded

  // Functional helper methods for library loading
  private def loadFromSystemPath(): Try[Unit] =
    catching(classOf[UnsatisfiedLinkError]).withTry:
      System.loadLibrary(libraryName)
      logger.info(s"Loaded $libraryName from java.library.path")

  private def detectPlatform(): Try[String] =
    val os = System.getProperty("os.name").toLowerCase
    val arch = System.getProperty("os.arch").toLowerCase
    (os, arch) match
      case (o, a) if o.contains("linux") && (a.contains("amd64") || a.contains("x86_64")) =>
        Success("x86_64-linux")
      case _ =>
        Failure(new UnsupportedOperationException(s"Unsupported platform: $os/$arch"))

  private def copyStreamToFile(stream: InputStream, out: FileOutputStream): Try[Unit] = Try:
    val buffer = new Array[Byte](8192)
    @scala.annotation.tailrec
    def copyLoop(): Unit =
      stream.read(buffer) match
        case -1 => // done
        case bytesRead =>
          out.write(buffer, 0, bytesRead)
          copyLoop()
    copyLoop()

  private def extractPTX(platform: String): Try[Unit] = Try:
    val ptxResourcePath = s"/native/$platform/optix_shaders.ptx"
    Option(getClass.getResourceAsStream(ptxResourcePath)) match
      case Some(ptxStream) =>
        val ptxDir = new java.io.File("target/native/x86_64-linux/bin")
        ptxDir.mkdirs()
        val ptxFile = new java.io.File(ptxDir, "optix_shaders.ptx")
        val ptxOut = new FileOutputStream(ptxFile)
        try
          copyStreamToFile(ptxStream, ptxOut).get
          logger.debug(s"Extracted PTX file to: ${ptxFile.getAbsolutePath}")
        finally
          ptxOut.close()
          ptxStream.close()
      case None =>
        logger.debug(s"PTX resource not found: $ptxResourcePath")

  private def extractAndLoadLibrary(stream: InputStream, resourcePath: String): Try[Unit] = Try:
    val tempFile = Files.createTempFile(s"lib$libraryName", ".so")
    tempFile.toFile.deleteOnExit()
    val out = new FileOutputStream(tempFile.toFile)
    try
      copyStreamToFile(stream, out).get
    finally
      out.close()
      stream.close()
    System.load(tempFile.toAbsolutePath.toString)
    logger.debug(s"Loaded $libraryName from classpath via temp file: ${tempFile.toAbsolutePath}")

  private def loadFromClasspath(): Try[Unit] =
    for
      platform <- detectPlatform()
      resourcePath = s"/native/$platform/lib$libraryName.so"
      _ = logger.debug(s"Attempting to load library from resource: $resourcePath")
      stream <- Option(getClass.getResourceAsStream(resourcePath))
        .toRight(new IllegalStateException(s"Library resource not found: $resourcePath"))
        .toTry
      _ <- extractAndLoadLibrary(stream, resourcePath)
      _ <- extractPTX(platform)
    yield ()

  private def loadNativeLibrary(): Try[Unit] =
    loadFromSystemPath().recoverWith:
      case _: UnsatisfiedLinkError =>
        logger.debug(s"Failed to load $libraryName from java.library.path, trying classpath")
        loadFromClasspath()
    .recoverWith:
      case e: Exception =>
        logger.error(s"Failed to load native library '$libraryName'", e)
        Failure(e)

// Exception thrown when OptiX is not available
case class OptiXNotAvailableException(message: String) extends Exception(message)

// Exception thrown when texture upload fails
case class TextureUploadException(message: String) extends Exception(message)
