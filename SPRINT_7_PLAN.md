# Sprint 7: Materials

**Created:** 2025-11-22
**Status:** 📋 PLANNED
**Estimated Effort:** 10-15 hours
**Branch:** TBD (create from `main` when starting)
**Prerequisites:** Sprint 6 complete (Full Geometry Support with IAS)

## Overview

Extend the material system with UV coordinates, texture support infrastructure, and richer material properties. Sprint 6 already provides per-instance color/IOR via `InstanceMaterial`. Sprint 7 adds UV coordinates, textures, and additional material properties for physically-based rendering.

### Sprint Goal

Enable textured materials with UV coordinates, material presets, and CLI-based material assignment. Complete the v0.5 milestone for full 3D mesh support.

### Success Criteria

- [ ] UV coordinates added to vertex format (8 floats: pos + normal + UV)
- [ ] Cube and sponge meshes generate valid UV coordinates
- [ ] Texture upload and sampling works (proof of concept)
- [ ] Material presets available (glass, metal, matte)
- [ ] CLI flags for material assignment (`--material`)
- [ ] All new code has tests
- [ ] Existing 897+ tests still pass

**🎯 MILESTONE: v0.5 - Full 3D Support** (after this sprint)

---

## Architectural Decisions

These decisions affect future sprints. Document rationale to avoid regret later.

### AD-1: Extend vertex format to include UV coordinates

**Decision:** Add 2 floats (UV) to vertex format, making it 8 floats per vertex.

**Rationale:**
- Sprint 5 established position (3) + normal (3) = 6 floats per vertex
- UVs needed for texture mapping
- Extension is straightforward (update stride, add data)

**Impact:**
- Vertex stride changes: 24 bytes → 32 bytes
- All geometry generators must provide UVs
- Shader must read UVs from vertex buffer

### AD-2: Material struct extends InstanceMaterial from Sprint 6

**Decision:** Build on `InstanceMaterial` from Sprint 6, adding optional texture references and PBR properties.

**Rationale:**
- Sprint 6's `InstanceMaterial` has color[4] and ior
- Extend rather than replace to maintain backward compatibility
- Optional properties for flexibility

**Impact:** Material struct grows; unused properties default to sensible values.

### AD-3: Texture objects in launch parameters (not SBT)

**Decision:** Store texture objects in `Params` struct, indexed by material texture IDs.

**Rationale:**
- Simpler than SBT-based texture binding
- Params are already copied to GPU each render
- Supports reasonable number of textures (e.g., 32)

**Impact:** `Params` struct grows with texture array; limit on simultaneous textures.

### AD-4: Simple UV mapping for primitives

**Decision:** Use per-face [0,1] UV mapping for cube and sponge.

**Rationale:**
- Each face maps to full [0,1]×[0,1] texture space
- Simple to implement, correct for tiling textures
- Future: can add projection modes (spherical, cylindrical)

**Impact:** All faces use same texture region; no atlas packing yet.

### AD-5: Material presets for common materials

**Decision:** Provide preset materials (glass, metal, plastic, matte) as static definitions.

**Rationale:**
- Easier for users than specifying all properties
- Physically accurate defaults from research
- CLI can reference by name

**Impact:** Preset library to maintain; users expect accurate behavior.

---

## Step 7.1: Extended Material System (3-4 hours)

### Goal
Extend material properties beyond color/IOR for richer rendering.

---

### Task 7.1.1: Define Material struct with extended properties

**File:** `optix-jni/src/main/native/include/OptiXData.h`

**Location:** After `InstanceMaterial` struct (around line 230)

**Add this extended structure:**

```cpp
// Extended material properties for physically-based rendering
// Builds on InstanceMaterial from Sprint 6
struct MaterialProperties {
    // Base properties (from Sprint 6)
    float color[4];              // RGBA (alpha: 0=transparent, 1=opaque)
    float ior;                   // Index of refraction

    // Extended PBR properties
    float roughness;             // 0=mirror, 1=diffuse (default: 0.5)
    float metallic;              // 0=dielectric, 1=metal (default: 0.0)
    float specular;              // Specular intensity (default: 0.5)

    // Texture indices (-1 = no texture)
    int base_color_texture;      // Albedo/diffuse texture index
    int normal_texture;          // Normal map texture index (future)
    int roughness_texture;       // Roughness map texture index (future)

    // Padding for alignment
    unsigned int padding[2];
};

// Material type for presets
enum MaterialType {
    MATERIAL_CUSTOM = 0,
    MATERIAL_GLASS = 1,
    MATERIAL_METAL = 2,
    MATERIAL_PLASTIC = 3,
    MATERIAL_MATTE = 4
};

// Maximum textures supported
constexpr unsigned int MAX_TEXTURES = 32;
```

**Update Params struct to include texture array:**

```cpp
struct Params {
    // ... existing fields ...

    // Material system (extended from Sprint 6)
    MaterialProperties* materials;      // Device pointer to material array
    unsigned int num_materials;

    // Texture system
    cudaTextureObject_t textures[MAX_TEXTURES];
    unsigned int num_textures;
};
```

**Test:** Compile should succeed with new structures.

---

### Task 7.1.2: Create material presets

**File:** `optix-jni/src/main/native/include/MaterialPresets.h` (new file)

```cpp
#pragma once

#include "OptiXData.h"

namespace MaterialPresets {

// Clear glass - high transmission, standard glass IOR
inline MaterialProperties glass() {
    MaterialProperties mat = {};
    mat.color[0] = 1.0f;
    mat.color[1] = 1.0f;
    mat.color[2] = 1.0f;
    mat.color[3] = 0.1f;      // Low alpha = high transparency
    mat.ior = 1.5f;           // Standard glass
    mat.roughness = 0.0f;     // Smooth
    mat.metallic = 0.0f;      // Dielectric
    mat.specular = 1.0f;      // High specular
    mat.base_color_texture = -1;
    mat.normal_texture = -1;
    mat.roughness_texture = -1;
    return mat;
}

// Colored glass - tinted with absorption
inline MaterialProperties coloredGlass(float r, float g, float b) {
    MaterialProperties mat = glass();
    mat.color[0] = r;
    mat.color[1] = g;
    mat.color[2] = b;
    mat.color[3] = 0.3f;      // More absorption for tint
    return mat;
}

// Chrome metal - high reflectivity, no transmission
inline MaterialProperties metal(float r = 0.8f, float g = 0.8f, float b = 0.9f) {
    MaterialProperties mat = {};
    mat.color[0] = r;
    mat.color[1] = g;
    mat.color[2] = b;
    mat.color[3] = 1.0f;      // Fully opaque
    mat.ior = 1.0f;           // N/A for metals (use complex IOR in future)
    mat.roughness = 0.1f;     // Slightly rough
    mat.metallic = 1.0f;      // Fully metallic
    mat.specular = 1.0f;      // High specular
    mat.base_color_texture = -1;
    mat.normal_texture = -1;
    mat.roughness_texture = -1;
    return mat;
}

// Plastic - some specular, low metallic
inline MaterialProperties plastic(float r, float g, float b) {
    MaterialProperties mat = {};
    mat.color[0] = r;
    mat.color[1] = g;
    mat.color[2] = b;
    mat.color[3] = 1.0f;      // Opaque
    mat.ior = 1.4f;           // Typical plastic
    mat.roughness = 0.4f;     // Moderate roughness
    mat.metallic = 0.0f;      // Not metallic
    mat.specular = 0.5f;      // Medium specular
    mat.base_color_texture = -1;
    mat.normal_texture = -1;
    mat.roughness_texture = -1;
    return mat;
}

// Matte - purely diffuse, no specular
inline MaterialProperties matte(float r, float g, float b) {
    MaterialProperties mat = {};
    mat.color[0] = r;
    mat.color[1] = g;
    mat.color[2] = b;
    mat.color[3] = 1.0f;      // Opaque
    mat.ior = 1.0f;           // No refraction
    mat.roughness = 1.0f;     // Fully rough
    mat.metallic = 0.0f;      // Not metallic
    mat.specular = 0.0f;      // No specular
    mat.base_color_texture = -1;
    mat.normal_texture = -1;
    mat.roughness_texture = -1;
    return mat;
}

// Water - transparent with water IOR
inline MaterialProperties water() {
    MaterialProperties mat = {};
    mat.color[0] = 0.7f;
    mat.color[1] = 0.9f;
    mat.color[2] = 1.0f;
    mat.color[3] = 0.05f;     // Very transparent
    mat.ior = 1.33f;          // Water IOR
    mat.roughness = 0.0f;     // Smooth
    mat.metallic = 0.0f;      // Dielectric
    mat.specular = 1.0f;      // High specular
    mat.base_color_texture = -1;
    mat.normal_texture = -1;
    mat.roughness_texture = -1;
    return mat;
}

// Diamond - high IOR, sparkly
inline MaterialProperties diamond() {
    MaterialProperties mat = {};
    mat.color[0] = 1.0f;
    mat.color[1] = 1.0f;
    mat.color[2] = 1.0f;
    mat.color[3] = 0.02f;     // Very transparent
    mat.ior = 2.42f;          // Diamond IOR
    mat.roughness = 0.0f;     // Perfect polish
    mat.metallic = 0.0f;      // Dielectric
    mat.specular = 1.0f;      // Maximum specular
    mat.base_color_texture = -1;
    mat.normal_texture = -1;
    mat.roughness_texture = -1;
    return mat;
}

// Get preset by type
inline MaterialProperties fromType(MaterialType type, float r = 1.0f, float g = 1.0f, float b = 1.0f) {
    switch (type) {
        case MATERIAL_GLASS:   return glass();
        case MATERIAL_METAL:   return metal(r, g, b);
        case MATERIAL_PLASTIC: return plastic(r, g, b);
        case MATERIAL_MATTE:   return matte(r, g, b);
        default:               return matte(r, g, b);
    }
}

}  // namespace MaterialPresets
```

**Test:** Include header and compile.

---

### Task 7.1.3: Add material management to OptiXWrapper

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Location:** In `Impl` struct (add to existing from Sprint 6)

```cpp
struct OptiXWrapper::Impl {
    // ... existing members from Sprint 6 ...

    // Extended material system
    std::vector<MaterialProperties> materials;
    CUdeviceptr d_materials;           // GPU buffer for MaterialProperties array
    bool materials_dirty;

    // Texture system
    struct TextureData {
        CUarray cuda_array;
        cudaTextureObject_t texture_object;
        unsigned int width;
        unsigned int height;
    };
    std::map<std::string, int> texture_name_to_index;
    std::vector<TextureData> texture_data;
};
```

**Add material management methods:**

```cpp
int OptiXWrapper::createMaterial(const MaterialProperties& props) {
    int id = static_cast<int>(impl->materials.size());
    impl->materials.push_back(props);
    impl->materials_dirty = true;
    return id;
}

void OptiXWrapper::setMaterial(int id, const MaterialProperties& props) {
    if (id >= 0 && id < static_cast<int>(impl->materials.size())) {
        impl->materials[id] = props;
        impl->materials_dirty = true;
    }
}

void OptiXWrapper::setInstanceMaterial(int instance_id, int material_id) {
    if (instance_id >= 0 && instance_id < static_cast<int>(impl->instances.size())) {
        impl->instances[instance_id].material_id = material_id;
        impl->ias_dirty = true;
    }
}

void OptiXWrapper::uploadMaterials() {
    if (!impl->materials_dirty || impl->materials.empty()) return;

    // Free existing buffer
    if (impl->d_materials) {
        CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_materials)));
    }

    // Upload materials
    size_t size = impl->materials.size() * sizeof(MaterialProperties);
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_materials), size));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(impl->d_materials),
        impl->materials.data(),
        size,
        cudaMemcpyHostToDevice
    ));

    impl->materials_dirty = false;
}
```

**Update header file with declarations.**

---

### Task 7.1.4: Scala Material API

**File:** `optix-jni/src/main/scala/menger/optix/Material.scala` (new file)

```scala
package menger.optix

import menger.common.Color

case class Material(
    color: Color,
    ior: Float = 1.0f,
    roughness: Float = 0.5f,
    metallic: Float = 0.0f,
    specular: Float = 0.5f,
    baseColorTexture: Option[String] = None
)

object Material:

  // Presets matching C++ MaterialPresets
  val Glass: Material = Material(
    color = Color.fromRGBA(1.0f, 1.0f, 1.0f, 0.1f),
    ior = 1.5f,
    roughness = 0.0f,
    metallic = 0.0f,
    specular = 1.0f
  )

  val Water: Material = Material(
    color = Color.fromRGBA(0.7f, 0.9f, 1.0f, 0.05f),
    ior = 1.33f,
    roughness = 0.0f,
    metallic = 0.0f,
    specular = 1.0f
  )

  val Diamond: Material = Material(
    color = Color.fromRGBA(1.0f, 1.0f, 1.0f, 0.02f),
    ior = 2.42f,
    roughness = 0.0f,
    metallic = 0.0f,
    specular = 1.0f
  )

  def metal(color: Color): Material = Material(
    color = color.copy(a = 1.0f),
    ior = 1.0f,
    roughness = 0.1f,
    metallic = 1.0f,
    specular = 1.0f
  )

  val Chrome: Material = metal(Color.fromRGB(0.8f, 0.8f, 0.9f))
  val Gold: Material = metal(Color.fromRGB(1.0f, 0.84f, 0.0f))
  val Copper: Material = metal(Color.fromRGB(0.72f, 0.45f, 0.20f))

  def plastic(color: Color): Material = Material(
    color = color.copy(a = 1.0f),
    ior = 1.4f,
    roughness = 0.4f,
    metallic = 0.0f,
    specular = 0.5f
  )

  def matte(color: Color): Material = Material(
    color = color.copy(a = 1.0f),
    ior = 1.0f,
    roughness = 1.0f,
    metallic = 0.0f,
    specular = 0.0f
  )

  def coloredGlass(color: Color): Material = Material(
    color = color.copy(a = 0.3f),
    ior = 1.5f,
    roughness = 0.0f,
    metallic = 0.0f,
    specular = 1.0f
  )
```

---

### Task 7.1.5: Material unit tests

**File:** `optix-jni/src/test/scala/menger/optix/MaterialTest.scala` (extend existing)

```scala
package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import menger.common.Color

class MaterialPresetTest extends AnyFlatSpec with Matchers:

  "Material.Glass" should "have correct properties" in:
    Material.Glass.ior shouldBe 1.5f
    Material.Glass.color.a shouldBe 0.1f +- 0.01f
    Material.Glass.roughness shouldBe 0.0f
    Material.Glass.metallic shouldBe 0.0f

  "Material.Water" should "have water IOR" in:
    Material.Water.ior shouldBe 1.33f

  "Material.Diamond" should "have high IOR" in:
    Material.Diamond.ior shouldBe 2.42f

  "Material.metal" should "create metallic material" in:
    val gold = Material.metal(Color.fromRGB(1.0f, 0.84f, 0.0f))
    gold.metallic shouldBe 1.0f
    gold.color.a shouldBe 1.0f  // Metals are opaque

  "Material.matte" should "have full roughness" in:
    val chalk = Material.matte(Color.WHITE)
    chalk.roughness shouldBe 1.0f
    chalk.specular shouldBe 0.0f

  "Material.coloredGlass" should "preserve color tint" in:
    val redGlass = Material.coloredGlass(Color.fromRGB(1.0f, 0.2f, 0.2f))
    redGlass.color.r shouldBe 1.0f
    redGlass.color.g shouldBe 0.2f
    redGlass.ior shouldBe 1.5f
```

**Run:** `sbt "testOnly menger.optix.MaterialPresetTest"`

---

## Step 7.2: UV Coordinates (3-4 hours)

### Goal
Add UV coordinates to vertex format and geometry generators.

---

### Task 7.2.1: Update vertex format in OptiXData.h

**File:** `optix-jni/src/main/native/include/OptiXData.h`

**Update comments in TriangleMeshData (from Sprint 5):**

```cpp
// Triangle mesh data - updated for materials (Sprint 7)
struct TriangleMeshData {
    float* vertices;              // Interleaved: pos(3) + normal(3) + uv(2) = 8 floats per vertex
    unsigned int* indices;        // Triangle indices (3 per triangle)
    unsigned int num_vertices;
    unsigned int num_triangles;
    unsigned int vertex_stride;   // Bytes per vertex (32 for pos+normal+uv)
};

// Constants for vertex format
constexpr unsigned int VERTEX_STRIDE_NO_UV = 6;    // 6 floats: pos + normal (Sprint 5/6)
constexpr unsigned int VERTEX_STRIDE_WITH_UV = 8;  // 8 floats: pos + normal + uv (Sprint 7)
```

---

### Task 7.2.2: Update CubeGeometry with UVs

**File:** `optix-jni/src/main/scala/menger/optix/geometry/CubeGeometry.scala`

**Add UV generation:**

```scala
package menger.optix.geometry

object CubeGeometry:

  case class MeshData(
      vertices: Array[Float],  // Interleaved pos + normal + uv (8 floats per vertex)
      indices: Array[Int]
  ):
    def numVertices: Int = vertices.length / 8
    def numTriangles: Int = indices.length / 3

  def generate(
      center: (Float, Float, Float) = (0.0f, 0.0f, 0.0f),
      size: Float = 1.0f,
      includeUVs: Boolean = true
  ): MeshData =
    val (cx, cy, cz) = center
    val half = size / 2.0f

    // 8 corner positions (same as before)
    val corners = Array(
      (cx - half, cy - half, cz - half),  // 0: left-bottom-back
      (cx + half, cy - half, cz - half),  // 1: right-bottom-back
      (cx + half, cy + half, cz - half),  // 2: right-top-back
      (cx - half, cy + half, cz - half),  // 3: left-top-back
      (cx - half, cy - half, cz + half),  // 4: left-bottom-front
      (cx + half, cy - half, cz + half),  // 5: right-bottom-front
      (cx + half, cy + half, cz + half),  // 6: right-top-front
      (cx - half, cy + half, cz + half)   // 7: left-top-front
    )

    // UV coordinates for each corner of a face (CCW from bottom-left)
    val faceUVs = Array(
      (0.0f, 0.0f),  // Bottom-left
      (1.0f, 0.0f),  // Bottom-right
      (1.0f, 1.0f),  // Top-right
      (0.0f, 1.0f)   // Top-left
    )

    // 6 faces with outward normals
    val faces = Array(
      // Front face (+Z)
      (Array(4, 5, 6, 7), (0.0f, 0.0f, 1.0f)),
      // Back face (-Z)
      (Array(1, 0, 3, 2), (0.0f, 0.0f, -1.0f)),
      // Right face (+X)
      (Array(5, 1, 2, 6), (1.0f, 0.0f, 0.0f)),
      // Left face (-X)
      (Array(0, 4, 7, 3), (-1.0f, 0.0f, 0.0f)),
      // Top face (+Y)
      (Array(7, 6, 2, 3), (0.0f, 1.0f, 0.0f)),
      // Bottom face (-Y)
      (Array(0, 1, 5, 4), (0.0f, -1.0f, 0.0f))
    )

    val floatsPerVertex = if includeUVs then 8 else 6
    val vertexList = scala.collection.mutable.ArrayBuffer[Float]()
    val indexList = scala.collection.mutable.ArrayBuffer[Int]()

    var vertexIndex = 0
    for (cornerIndices, normal) <- faces do
      val (nx, ny, nz) = normal

      // Add 4 vertices for this face
      for (i, ci) <- cornerIndices.zipWithIndex do
        val (px, py, pz) = corners(ci)
        val (u, v) = faceUVs(i)

        if includeUVs then
          vertexList ++= Array(px, py, pz, nx, ny, nz, u, v)
        else
          vertexList ++= Array(px, py, pz, nx, ny, nz)

      // Add 2 triangles (CCW winding)
      val base = vertexIndex
      indexList ++= Array(base, base + 1, base + 2)
      indexList ++= Array(base, base + 2, base + 3)

      vertexIndex += 4

    MeshData(vertexList.toArray, indexList.toArray)
```

---

### Task 7.2.3: Update SpongeGeometry with UVs

**File:** `optix-jni/src/main/scala/menger/optix/geometry/SpongeGeometry.scala`

**Add UV generation to existing code:**

```scala
private def facesToMesh(faces: Seq[Face], size: Float, includeUVs: Boolean = true): MeshData =
    val floatsPerVertex = if includeUVs then 8 else 6
    val vertexList = scala.collection.mutable.ArrayBuffer[Float]()
    val indexList = scala.collection.mutable.ArrayBuffer[Int]()

    var vertexIndex = 0

    // UV coordinates for face corners
    val faceUVs = Array(
      (0.0f, 0.0f),
      (1.0f, 0.0f),
      (1.0f, 1.0f),
      (0.0f, 1.0f)
    )

    for face <- faces do
      val cx = face.xCen * size
      val cy = face.yCen * size
      val cz = face.zCen * size
      val halfSize = face.scale * size / 2.0f

      val (nx, ny, nz) = face.normal match
        case Direction.X    => (1.0f, 0.0f, 0.0f)
        case Direction.Y    => (0.0f, 1.0f, 0.0f)
        case Direction.Z    => (0.0f, 0.0f, 1.0f)
        case Direction.negX => (-1.0f, 0.0f, 0.0f)
        case Direction.negY => (0.0f, -1.0f, 0.0f)
        case Direction.negZ => (0.0f, 0.0f, -1.0f)

      val corners = face.normal match
        case Direction.Z | Direction.negZ =>
          Seq(
            (cx - halfSize, cy - halfSize, cz),
            (cx + halfSize, cy - halfSize, cz),
            (cx + halfSize, cy + halfSize, cz),
            (cx - halfSize, cy + halfSize, cz)
          )
        case Direction.X | Direction.negX =>
          Seq(
            (cx, cy - halfSize, cz - halfSize),
            (cx, cy - halfSize, cz + halfSize),
            (cx, cy + halfSize, cz + halfSize),
            (cx, cy + halfSize, cz - halfSize)
          )
        case Direction.Y | Direction.negY =>
          Seq(
            (cx - halfSize, cy, cz - halfSize),
            (cx + halfSize, cy, cz - halfSize),
            (cx + halfSize, cy, cz + halfSize),
            (cx - halfSize, cy, cz + halfSize)
          )

      // Add vertices with UVs
      for (i, (px, py, pz)) <- corners.zipWithIndex do
        val (u, v) = faceUVs(i)
        if includeUVs then
          vertexList ++= Array(px, py, pz, nx, ny, nz, u, v)
        else
          vertexList ++= Array(px, py, pz, nx, ny, nz)

      // Add triangles with correct winding
      val base = vertexIndex
      val sign = nx + ny + nz
      if sign > 0 then
        indexList ++= Array(base, base + 1, base + 2)
        indexList ++= Array(base, base + 2, base + 3)
      else
        indexList ++= Array(base, base + 2, base + 1)
        indexList ++= Array(base, base + 3, base + 2)

      vertexIndex += 4

    MeshData(vertexList.toArray, indexList.toArray)
```

---

### Task 7.2.4: Update shader to read UV coordinates

**File:** `optix-jni/src/main/native/shaders/sphere_combined.cu`

**In `__closesthit__triangle`, add UV reading:**

```cuda
extern "C" __global__ void __closesthit__triangle()
{
    // ... existing hit data retrieval ...

    // Get barycentric coordinates for UV interpolation
    const float2 barycentrics = optixGetTriangleBarycentrics();
    const float w0 = 1.0f - barycentrics.x - barycentrics.y;
    const float w1 = barycentrics.x;
    const float w2 = barycentrics.y;

    // Get vertex indices
    const unsigned int prim_idx = optixGetPrimitiveIndex();
    const unsigned int idx0 = hit_data->indices[prim_idx * 3 + 0];
    const unsigned int idx1 = hit_data->indices[prim_idx * 3 + 1];
    const unsigned int idx2 = hit_data->indices[prim_idx * 3 + 2];

    // Read UVs (vertex stride = 8 floats: pos(3) + normal(3) + uv(2))
    const unsigned int stride = 8;
    const float2 uv0 = make_float2(
        hit_data->vertices[idx0 * stride + 6],
        hit_data->vertices[idx0 * stride + 7]
    );
    const float2 uv1 = make_float2(
        hit_data->vertices[idx1 * stride + 6],
        hit_data->vertices[idx1 * stride + 7]
    );
    const float2 uv2 = make_float2(
        hit_data->vertices[idx2 * stride + 6],
        hit_data->vertices[idx2 * stride + 7]
    );

    // Interpolate UV
    const float2 uv = w0 * uv0 + w1 * uv1 + w2 * uv2;

    // Use UV for texture sampling (if texture available)
    float3 texture_color = make_float3(1.0f, 1.0f, 1.0f);
    if (params.num_textures > 0 && material.base_color_texture >= 0) {
        const cudaTextureObject_t tex = params.textures[material.base_color_texture];
        const float4 tex_sample = tex2D<float4>(tex, uv.x, uv.y);
        texture_color = make_float3(tex_sample.x, tex_sample.y, tex_sample.z);
    }

    // Multiply base color by texture color
    object_color = object_color * texture_color;

    // ... rest of shading ...
}
```

---

### Task 7.2.5: UV coordinate tests

**File:** `optix-jni/src/test/scala/menger/optix/geometry/UVCoordinateTest.scala` (new file)

```scala
package menger.optix.geometry

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class UVCoordinateTest extends AnyFlatSpec with Matchers:

  "CubeGeometry with UVs" should "have 8 floats per vertex" in:
    val mesh = CubeGeometry.generate(includeUVs = true)
    mesh.vertices.length shouldBe mesh.numVertices * 8

  it should "have UV coordinates in [0,1] range" in:
    val mesh = CubeGeometry.generate(includeUVs = true)

    for i <- 0 until mesh.numVertices do
      val u = mesh.vertices(i * 8 + 6)
      val v = mesh.vertices(i * 8 + 7)

      u should be >= 0.0f
      u should be <= 1.0f
      v should be >= 0.0f
      v should be <= 1.0f

  it should "have all four UV corners per face" in:
    val mesh = CubeGeometry.generate(includeUVs = true)

    // 6 faces, each should have (0,0), (1,0), (1,1), (0,1)
    val uvs = (0 until mesh.numVertices).map { i =>
      (mesh.vertices(i * 8 + 6), mesh.vertices(i * 8 + 7))
    }

    // Group by face (4 vertices each)
    val faceUVs = uvs.grouped(4).toList
    faceUVs.length shouldBe 6

    for faceUV <- faceUVs do
      val uvSet = faceUV.toSet
      uvSet should contain ((0.0f, 0.0f))
      uvSet should contain ((1.0f, 0.0f))
      uvSet should contain ((1.0f, 1.0f))
      uvSet should contain ((0.0f, 1.0f))

  "CubeGeometry without UVs" should "have 6 floats per vertex" in:
    val mesh = CubeGeometry.generate(includeUVs = false)
    mesh.vertices.length shouldBe mesh.numVertices * 6
```

**Run:** `sbt "testOnly menger.optix.geometry.UVCoordinateTest"`

---

## Step 7.3: Texture Support (2-3 hours)

### Goal
Basic texture upload and sampling infrastructure.

---

### Task 7.3.1: Texture upload in OptiXWrapper

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Add texture management:**

```cpp
int OptiXWrapper::uploadTexture(
    const std::string& name,
    const unsigned char* image_data,
    unsigned int width,
    unsigned int height
) {
    // Check if texture already exists
    auto it = impl->texture_name_to_index.find(name);
    if (it != impl->texture_name_to_index.end()) {
        return it->second;  // Return existing index
    }

    if (impl->texture_data.size() >= MAX_TEXTURES) {
        return -1;  // Too many textures
    }

    // Create CUDA array
    cudaChannelFormatDesc channel_desc = cudaCreateChannelDesc<uchar4>();
    CUarray cuda_array;
    CUDA_CHECK(cudaMallocArray(&cuda_array, &channel_desc, width, height));

    // Copy image data
    CUDA_CHECK(cudaMemcpyToArray(
        cuda_array, 0, 0,
        image_data,
        width * height * 4,
        cudaMemcpyHostToDevice
    ));

    // Create texture object
    cudaResourceDesc res_desc = {};
    res_desc.resType = cudaResourceTypeArray;
    res_desc.res.array.array = cuda_array;

    cudaTextureDesc tex_desc = {};
    tex_desc.addressMode[0] = cudaAddressModeWrap;
    tex_desc.addressMode[1] = cudaAddressModeWrap;
    tex_desc.filterMode = cudaFilterModeLinear;
    tex_desc.readMode = cudaReadModeNormalizedFloat;
    tex_desc.normalizedCoords = 1;

    cudaTextureObject_t texture_object;
    CUDA_CHECK(cudaCreateTextureObject(&texture_object, &res_desc, &tex_desc, nullptr));

    // Store texture data
    int index = static_cast<int>(impl->texture_data.size());
    impl->texture_data.push_back({cuda_array, texture_object, width, height});
    impl->texture_name_to_index[name] = index;

    return index;
}

void OptiXWrapper::releaseTextures() {
    for (auto& tex : impl->texture_data) {
        CUDA_CHECK(cudaDestroyTextureObject(tex.texture_object));
        CUDA_CHECK(cudaFreeArray(tex.cuda_array));
    }
    impl->texture_data.clear();
    impl->texture_name_to_index.clear();
}
```

---

### Task 7.3.2: JNI bindings for texture upload

**File:** `optix-jni/src/main/native/JNIBindings.cpp`

```cpp
JNIEXPORT jint JNICALL Java_menger_optix_OptiXRenderer_uploadTextureNative(
    JNIEnv* env, jobject obj,
    jstring name,
    jbyteArray imageData,
    jint width, jint height
) {
    OptiXWrapper* wrapper = getWrapper(env, obj);
    if (!wrapper) return -1;

    const char* name_str = env->GetStringUTFChars(name, nullptr);
    jbyte* data = env->GetByteArrayElements(imageData, nullptr);

    int result = wrapper->uploadTexture(
        name_str,
        reinterpret_cast<unsigned char*>(data),
        static_cast<unsigned int>(width),
        static_cast<unsigned int>(height)
    );

    env->ReleaseByteArrayElements(imageData, data, 0);
    env->ReleaseStringUTFChars(name, name_str);

    return result;
}
```

---

### Task 7.3.3: Scala texture interface

**File:** `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala`

**Add texture methods:**

```scala
// Texture support
@native private def uploadTextureNative(
    name: String,
    imageData: Array[Byte],
    width: Int,
    height: Int
): Int

def uploadTexture(name: String, imageData: Array[Byte], width: Int, height: Int): Int =
    require(imageData.length == width * height * 4, "Image data must be RGBA format")
    uploadTextureNative(name, imageData, width, height)

def uploadTextureFromFile(name: String, path: String): Int =
    // Load image using standard Java ImageIO
    import java.io.File
    import javax.imageio.ImageIO
    import java.awt.image.BufferedImage

    val image = ImageIO.read(new File(path))
    val width = image.getWidth
    val height = image.getHeight

    // Convert to RGBA byte array
    val data = new Array[Byte](width * height * 4)
    for
      y <- 0 until height
      x <- 0 until width
    do
      val rgb = image.getRGB(x, y)
      val idx = (y * width + x) * 4
      data(idx + 0) = ((rgb >> 16) & 0xFF).toByte  // R
      data(idx + 1) = ((rgb >> 8) & 0xFF).toByte   // G
      data(idx + 2) = (rgb & 0xFF).toByte          // B
      data(idx + 3) = ((rgb >> 24) & 0xFF).toByte  // A

    uploadTexture(name, data, width, height)
```

---

### Task 7.3.4: Texture rendering test

**File:** `optix-jni/src/test/scala/menger/optix/TextureRenderTest.scala` (new file)

```scala
package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import menger.common.Color

class TextureRenderTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private var renderer: OptiXRenderer = _
  private var initialized: Boolean = false

  override def beforeAll(): Unit =
    if OptiXRenderer.isLibraryLoaded then
      renderer = new OptiXRenderer()
      initialized = renderer.initialize()
      if initialized then
        renderer.setCamera(Array(0f, 2f, 5f), Array(0f, 0f, 0f), Array(0f, 1f, 0f), 45f)
        renderer.setLight(Array(-1f, 1f, 1f), 1f)

  override def afterAll(): Unit =
    if initialized then renderer.dispose()

  "uploadTexture" should "accept valid texture data" in:
    assume(initialized, "OptiX not available")

    // Create simple 2x2 checkerboard texture
    val width = 2
    val height = 2
    val data = Array[Byte](
      // Row 0: white, black
      -1, -1, -1, -1,    // White (RGBA)
      0, 0, 0, -1,       // Black
      // Row 1: black, white
      0, 0, 0, -1,       // Black
      -1, -1, -1, -1     // White
    )

    val index = renderer.uploadTexture("checkerboard", data, width, height)
    index should be >= 0

  "Textured cube" should "render without errors" in:
    assume(initialized, "OptiX not available")

    // Create gradient texture
    val width = 64
    val height = 64
    val data = new Array[Byte](width * height * 4)
    for
      y <- 0 until height
      x <- 0 until width
    do
      val idx = (y * width + x) * 4
      data(idx + 0) = (x * 4).toByte    // R increases left to right
      data(idx + 1) = (y * 4).toByte    // G increases top to bottom
      data(idx + 2) = 128.toByte        // B constant
      data(idx + 3) = -1                // A = 255

    val texIndex = renderer.uploadTexture("gradient", data, width, height)
    texIndex should be >= 0

    // Create cube with material referencing texture
    renderer.clearAllInstances()
    val material = Material(
      color = Color.WHITE,
      baseColorTexture = Some("gradient")
    )
    renderer.addCubeWithMaterial(0f, 0f, 0f, 2f, material)

    val result = renderer.renderWithStats(800, 600)
    result.image should not be empty
```

**Run:** `sbt "testOnly menger.optix.TextureRenderTest"`

---

## Step 7.4: CLI Integration (2-3 hours)

### Goal
Material assignment via CLI flags.

---

### Task 7.4.1: Add material CLI options

**File:** `src/main/scala/menger/MengerCLIOptions.scala`

**Add material options:**

```scala
// Material presets (applies to all objects or specific object)
val material: ScallopOption[String] = opt[String](
  name = "material",
  required = false,
  descr = "Material preset: glass, metal, plastic, matte, water, diamond, chrome, gold, copper"
)

// Material color (overrides preset base color)
val materialColor: ScallopOption[String] = opt[String](
  name = "material-color",
  required = false,
  descr = "Material color as #RRGGBB or #RRGGBBAA hex"
)

// Material roughness
val materialRoughness: ScallopOption[Float] = opt[Float](
  name = "roughness",
  required = false,
  validate = r => r >= 0 && r <= 1,
  descr = "Material roughness (0=mirror, 1=diffuse)"
)

// Material IOR (overrides preset)
val materialIor: ScallopOption[Float] = opt[Float](
  name = "ior",
  required = false,
  validate = _ > 0,
  descr = "Index of refraction (glass=1.5, water=1.33, diamond=2.42)"
)

// Validation
validateOpt(material) { mat =>
  val valid = Set("glass", "metal", "plastic", "matte", "water", "diamond", "chrome", "gold", "copper")
  if valid.contains(mat.toLowerCase) then Right(())
  else Left(s"Unknown material: $mat. Valid: ${valid.mkString(", ")}")
}
```

---

### Task 7.4.2: Parse material from CLI

**File:** `src/main/scala/menger/MaterialParser.scala` (new file)

```scala
package menger

import menger.optix.Material
import menger.common.Color

object MaterialParser:

  def fromName(name: String): Option[Material] =
    name.toLowerCase match
      case "glass"   => Some(Material.Glass)
      case "water"   => Some(Material.Water)
      case "diamond" => Some(Material.Diamond)
      case "chrome"  => Some(Material.Chrome)
      case "gold"    => Some(Material.Gold)
      case "copper"  => Some(Material.Copper)
      case "metal"   => Some(Material.metal(Color.fromRGB(0.8f, 0.8f, 0.8f)))
      case "plastic" => Some(Material.plastic(Color.WHITE))
      case "matte"   => Some(Material.matte(Color.WHITE))
      case _         => None

  def fromOptions(options: MengerCLIOptions): Material =
    val baseMaterial = options.material.toOption
      .flatMap(fromName)
      .getOrElse(Material.matte(Color.WHITE))

    // Apply overrides
    val withColor = options.materialColor.toOption
      .map(hex => baseMaterial.copy(color = Color.fromHex(hex)))
      .getOrElse(baseMaterial)

    val withRoughness = options.materialRoughness.toOption
      .map(r => withColor.copy(roughness = r))
      .getOrElse(withColor)

    val withIor = options.materialIor.toOption
      .map(ior => withRoughness.copy(ior = ior))
      .getOrElse(withRoughness)

    withIor
```

---

### Task 7.4.3: Wire CLI to renderer

**File:** `src/main/scala/menger/OptiXResources.scala`

**Update object configuration to use materials:**

```scala
def configureObjectWithMaterial(spec: ObjectSpec, material: Material): Unit =
  spec.objectType match
    case "sphere" =>
      renderer.addSphereWithMaterial(
        spec.x, spec.y, spec.z, spec.size, material
      )
    case "cube" =>
      renderer.addCubeWithMaterial(
        spec.x, spec.y, spec.z, spec.size, material
      )
    case "sponge" =>
      renderer.addSpongeWithMaterial(
        spec.x, spec.y, spec.z, spec.size, spec.level.getOrElse(2), material
      )
    case _ =>
      logger.warn(s"Unknown object type: ${spec.objectType}")
```

---

### Task 7.4.4: CLI integration tests

**File:** `src/test/scala/menger/CLIMaterialTest.scala` (new file)

```scala
package menger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import menger.optix.Material

class CLIMaterialTest extends AnyFlatSpec with Matchers:

  "MaterialParser.fromName" should "return Glass for 'glass'" in:
    MaterialParser.fromName("glass") shouldBe Some(Material.Glass)

  it should "return Diamond for 'diamond'" in:
    MaterialParser.fromName("diamond") shouldBe Some(Material.Diamond)

  it should "return None for unknown material" in:
    MaterialParser.fromName("unobtanium") shouldBe None

  "CLI parser" should "accept --material glass" in:
    val args = Array("--optix", "--material", "glass")
    val options = new MengerCLIOptions(args)
    options.material() shouldBe "glass"

  it should "accept --material with color override" in:
    val args = Array("--optix", "--material", "metal", "--material-color", "#FFD700")
    val options = new MengerCLIOptions(args)
    options.material() shouldBe "metal"
    options.materialColor() shouldBe "#FFD700"

  it should "accept --roughness" in:
    val args = Array("--optix", "--roughness", "0.7")
    val options = new MengerCLIOptions(args)
    options.materialRoughness() shouldBe 0.7f

  it should "reject invalid roughness" in:
    val args = Array("--optix", "--roughness", "1.5")
    an[Exception] should be thrownBy:
      val options = new MengerCLIOptions(args)
      options.verify()

  "MaterialParser.fromOptions" should "apply color override" in:
    val args = Array("--optix", "--material", "glass", "--material-color", "#FF0000")
    val options = new MengerCLIOptions(args)
    val material = MaterialParser.fromOptions(options)

    material.ior shouldBe 1.5f  // From glass preset
    material.color.r shouldBe 1.0f  // From override
```

**Run:** `sbt "testOnly menger.CLIMaterialTest"`

---

## Files Summary

### New Files

| File | Description |
|------|-------------|
| `optix-jni/src/main/native/include/MaterialPresets.h` | C++ material preset definitions |
| `optix-jni/src/main/scala/menger/optix/Material.scala` | Scala material case class and presets |
| `optix-jni/src/test/scala/menger/optix/geometry/UVCoordinateTest.scala` | UV coordinate tests |
| `optix-jni/src/test/scala/menger/optix/TextureRenderTest.scala` | Texture rendering tests |
| `src/main/scala/menger/MaterialParser.scala` | CLI material parsing |
| `src/test/scala/menger/CLIMaterialTest.scala` | CLI material tests |

### Modified Files

| File | Changes |
|------|---------|
| `optix-jni/src/main/native/include/OptiXData.h` | Add `MaterialProperties`, `MAX_TEXTURES`, UV vertex format |
| `optix-jni/src/main/native/OptiXWrapper.h` | Add material and texture management methods |
| `optix-jni/src/main/native/OptiXWrapper.cpp` | Implement material/texture upload and management |
| `optix-jni/src/main/native/JNIBindings.cpp` | Add texture upload JNI bindings |
| `optix-jni/src/main/native/shaders/sphere_combined.cu` | Add UV reading and texture sampling |
| `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala` | Add material and texture Scala interface |
| `optix-jni/src/main/scala/menger/optix/geometry/CubeGeometry.scala` | Add UV generation |
| `optix-jni/src/main/scala/menger/optix/geometry/SpongeGeometry.scala` | Add UV generation |
| `src/main/scala/menger/MengerCLIOptions.scala` | Add material CLI options |
| `src/main/scala/menger/OptiXResources.scala` | Wire material configuration |

---

## Definition of Done

- [ ] All tasks completed
- [ ] All tests passing (new + existing 897+)
- [ ] Code compiles without warnings
- [ ] Code passes `sbt "scalafix --check"`
- [ ] CHANGELOG.md updated
- [ ] UV coordinates work for cube and sponge
- [ ] At least one texture renders correctly (proof of concept)
- [ ] Material presets work via CLI
- [ ] Backward compatible: existing scenes without materials still work

**🎯 MILESTONE: v0.5 - Full 3D Support** (achieved after this sprint)

---

## References

- [OptiX Programming Guide - Textures](https://raytracing-docs.nvidia.com/optix7/guide/index.html#textures)
- [CUDA Texture Objects](https://docs.nvidia.com/cuda/cuda-c-programming-guide/index.html#texture-objects)
- Sprint 5 plan: `SPRINT_5_PLAN.md` (vertex format foundation)
- Sprint 6 plan: `SPRINT_6_PLAN.md` (per-instance materials)
- Existing implementation: `sphere_combined.cu` (Fresnel, Beer-Lambert)
- PBR reference: Disney BRDF, Unreal Engine material model
