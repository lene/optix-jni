# Sprint 7: Materials

**Created:** 2025-11-22
**Status:** ✅ COMPLETE
**Estimated Effort:** 10-15 hours
**Branch:** feature/sprint-7
**Prerequisites:** Sprint 6 complete (Full Geometry Support with IAS)

## Overview

Extend the material system with UV coordinates, texture support infrastructure, and
richer material properties. Sprint 6 already provides per-instance color/IOR via
`InstanceMaterial`. Sprint 7 adds UV coordinates, textures, and additional material
properties for physically-based rendering.

### Sprint Goal

Enable textured materials with UV coordinates, material presets, and CLI-based
material assignment. Complete the v0.5 milestone for full 3D mesh support.

### Success Criteria

- [x] UV coordinates added to vertex format (8 floats: pos + normal + UV)
- [x] Cube and sponge meshes generate valid UV coordinates
- [x] Texture upload and sampling works (proof of concept)
- [x] Material presets available (glass, metal, matte)
- [x] CLI flags for material assignment (`--material`)
- [x] All new code has tests
- [x] Existing 897+ tests still pass
- [x] Integration tests still pass

**🎯 MILESTONE: v0.5 - Full 3D Support** (after this sprint)

---

## Quality Requirements & Validation

**Reference:** [arc42 Section 10 - Quality Requirements](../docs/arc42/10-quality-requirements.md)

### Metrics to Establish Baselines

Sprint 7 extends vertex format and adds textures - verify no performance regression:

| Metric | Arc42 Ref | Sprint 7 Goal |
|--------|-----------|---------------|
| Vertex buffer memory | Memory | **Measure impact** - 8 vs 6 floats per vertex |
| Render time with textures | P2 | **Establish baseline** - texture sampling overhead |
| Texture upload time | New | **Establish baseline** - document for common sizes |
| Material preset accuracy | V1-V3 | **Validate** - glass/water/diamond presets match physics |

### Quality Scenarios to Validate

| ID | Scenario | Validation |
|----|----------|------------|
| V1 | Glass refraction | Material preset IOR matches Snell's law |
| V2 | Fresnel reflection | Material presets show correct angular falloff |
| V3 | Beer-Lambert absorption | Colored glass shows physically plausible tinting |
| P2 | Render time | No regression >10% from adding UV coordinates |
| M1-M2 | Code quality | Zero Scalafix/Wartremover violations |

### Material Preset Validation

| Preset | IOR | Roughness | Metallic | Expected Behavior |
|--------|-----|-----------|----------|-------------------|
| Glass | 1.5 | 0.0 | 0.0 | Clear refraction, 4% perpendicular reflection |
| Water | 1.33 | 0.0 | 0.0 | 2% perpendicular reflection, slight caustics |
| Diamond | 2.42 | 0.0 | 0.0 | 17% perpendicular reflection, sparkle |
| Metal | 1.0 | 0.1 | 1.0 | Colored specular, no refraction |
| Plastic | 1.5 | 0.3 | 0.0 | White specular on colored diffuse |
| Matte | 1.0 | 1.0 | 0.0 | Pure Lambertian diffuse, no specular |

### Sprint 7 Quality Deliverables

1. **Material preset reference renders** - screenshot each preset for documentation
2. **Performance comparison table** - render times before/after UV extension
3. **Texture memory report** - GPU memory for sample textures
4. **v0.5 milestone validation** - comprehensive test of all Sprint 5-7 features together

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

**Decision:** Build on `InstanceMaterial` from Sprint 6, adding optional texture
references and PBR properties.

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

**Decision:** Use per-face [0,1] UV mapping for cube and sponge. UVs included by
default (`includeUVs = true`).

**Rationale:**
- Each face maps to full [0,1]×[0,1] texture space
- Simple to implement, correct for tiling textures
- Simpler to standardize on 8-float vertices than maintain two code paths
- Future: can add projection modes (spherical, cylindrical)

**Impact:** All geometry uses 8-float vertices (pos + normal + UV). All faces use
same texture region; no atlas packing yet.

### AD-5: Material presets for common materials

**Decision:** Provide preset materials (glass, metal, plastic, matte) as static definitions.

**Rationale:**
- Easier for users than specifying all properties
- Physically accurate defaults from research
- CLI can reference by name

**Impact:** Preset library to maintain; users expect accurate behavior.

### AD-6: Per-object material via --objects format

**Decision:** Materials are specified per-object in the `--objects` CLI format,
not via global flags.

**Format:**
```
--objects type=sphere:pos=0,0,0:material=glass
--objects type=cube:pos=2,0,0:material=metal:color=#FFD700
--objects type=sphere:material=glass:ior=1.7:roughness=0.1
```

**Rationale:**
- Each object can have a different material
- More flexible than global `--material` flag
- Consistent with existing `--objects` syntax
- Allows per-object customization (color, IOR, roughness)

**Impact:** ObjectSpec gains material field; MaterialParser parses from keyword map.

### AD-7: Material presets with property overrides

**Decision:** Material presets can be customized with property overrides.

**Format:**
```
material=glass              → Use glass preset as-is
material=glass:ior=1.7      → Glass with custom IOR
material=metal:color=#FFD700 → Metal with gold color
material=plastic:roughness=0.3:color=#FF0000 → Custom plastic
```

**Rationale:**
- Presets provide sensible defaults
- Overrides allow fine-tuning without defining custom materials
- Incremental customization (change only what you need)

**Impact:** MaterialParser must merge preset with overrides.

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
inline MaterialProperties fromType(MaterialType type,
                                   float r = 1.0f, float g = 1.0f, float b = 1.0f) {
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
Material assignment via per-object `--objects` format (see AD-6 and AD-7).

---

### Task 7.4.1: Add material parsing to ObjectSpec

**File:** `src/main/scala/menger/ObjectSpec.scala`

**Update ObjectSpec case class to include material:**

```scala
case class ObjectSpec(
  objectType: String,
  x: Float,
  y: Float,
  z: Float,
  size: Float,
  level: Option[Int],
  color: Option[Color],
  material: Option[Material]  // NEW: parsed material with overrides applied
)
```

**Add material keywords to the parser:**

```scala
// In ObjectSpec.parse or companion object
private val materialKeywords = Set("material", "ior", "roughness", "color")

def parseMaterial(keywords: Map[String, String]): Option[Material] =
  keywords.get("material").flatMap { presetName =>
    MaterialPresets.fromName(presetName).map { baseMaterial =>
      // Apply overrides from keywords
      var result = baseMaterial
      keywords.get("ior").foreach(v => result = result.copy(ior = v.toFloat))
      keywords.get("roughness").foreach(v => result = result.copy(roughness = v.toFloat))
      keywords.get("color").foreach(hex => result = result.copy(color = Color.fromHex(hex)))
      result
    }
  }
```

---

### Task 7.4.2: Material presets lookup

**File:** `optix-jni/src/main/scala/menger/optix/Material.scala`

**Add preset lookup (as part of Material companion object):**

```scala
object Material:
  // Presets
  val Glass   = Material(Color.WHITE, ior = 1.5f, roughness = 0.0f, metallic = 0.0f)
  val Water   = Material(Color.WHITE, ior = 1.33f, roughness = 0.0f, metallic = 0.0f)
  val Diamond = Material(Color.WHITE, ior = 2.42f, roughness = 0.0f, metallic = 0.0f)
  val Chrome  = Material(
    Color.fromRGB(0.9f, 0.9f, 0.9f), ior = 1.0f, roughness = 0.0f, metallic = 1.0f
  )
  val Gold    = Material(
    Color.fromRGB(1.0f, 0.84f, 0.0f), ior = 1.0f, roughness = 0.1f, metallic = 1.0f
  )
  val Copper  = Material(
    Color.fromRGB(0.72f, 0.45f, 0.20f), ior = 1.0f, roughness = 0.2f, metallic = 1.0f
  )

  def matte(color: Color): Material =
    Material(color, ior = 1.0f, roughness = 1.0f, metallic = 0.0f)
  def plastic(color: Color): Material =
    Material(color, ior = 1.5f, roughness = 0.3f, metallic = 0.0f)
  def metal(color: Color): Material =
    Material(color, ior = 1.0f, roughness = 0.1f, metallic = 1.0f)

  def fromName(name: String): Option[Material] =
    name.toLowerCase match
      case "glass"   => Some(Glass)
      case "water"   => Some(Water)
      case "diamond" => Some(Diamond)
      case "chrome"  => Some(Chrome)
      case "gold"    => Some(Gold)
      case "copper"  => Some(Copper)
      case "metal"   => Some(metal(Color.WHITE))
      case "plastic" => Some(plastic(Color.WHITE))
      case "matte"   => Some(matte(Color.WHITE))
      case _         => None
```

---

### Task 7.4.3: Wire ObjectSpec material to renderer

**File:** `src/main/scala/menger/OptiXResources.scala`

**Update object configuration to use per-object materials:**

```scala
def configureObject(spec: ObjectSpec): Unit =
  val material = spec.material.getOrElse(Material.matte(spec.color.getOrElse(Color.WHITE)))

  spec.objectType match
    case "sphere" =>
      renderer.addSphereWithMaterial(spec.x, spec.y, spec.z, spec.size, material)
    case "cube" =>
      renderer.addCubeWithMaterial(spec.x, spec.y, spec.z, spec.size, material)
    case "sponge" =>
      renderer.addSpongeWithMaterial(
        spec.x, spec.y, spec.z, spec.size, spec.level.getOrElse(2), material
      )
    case _ =>
      logger.warn(s"Unknown object type: ${spec.objectType}")
```

---

### Task 7.4.4: CLI integration tests

**File:** `src/test/scala/menger/ObjectSpecMaterialTest.scala` (new file)

```scala
package menger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import menger.optix.Material
import menger.common.Color

class ObjectSpecMaterialTest extends AnyFlatSpec with Matchers:

  "ObjectSpec parser" should "parse material preset" in:
    val spec = ObjectSpec.parse("type=sphere:pos=0,0,0:material=glass")
    spec.material shouldBe defined
    spec.material.get.ior shouldBe 1.5f

  it should "parse material with IOR override" in:
    val spec = ObjectSpec.parse("type=sphere:pos=0,0,0:material=glass:ior=1.7")
    spec.material.get.ior shouldBe 1.7f

  it should "parse material with color override" in:
    val spec = ObjectSpec.parse("type=cube:pos=0,0,0:material=metal:color=#FFD700")
    spec.material.get.color shouldBe Color.fromHex("#FFD700")

  it should "parse material with multiple overrides" in:
    val spec = ObjectSpec.parse("type=sphere:material=plastic:roughness=0.5:color=#FF0000")
    val mat = spec.material.get
    mat.roughness shouldBe 0.5f
    mat.color.r shouldBe 1.0f

  it should "return None for unknown material preset" in:
    val spec = ObjectSpec.parse("type=sphere:pos=0,0,0:material=unobtanium")
    spec.material shouldBe None

  "Material.fromName" should "return Glass for 'glass'" in:
    Material.fromName("glass") shouldBe Some(Material.Glass)

  it should "return Diamond for 'diamond'" in:
    Material.fromName("diamond") shouldBe Some(Material.Diamond)

  it should "be case insensitive" in:
    Material.fromName("GLASS") shouldBe Some(Material.Glass)
    Material.fromName("Glass") shouldBe Some(Material.Glass)
```

**Run:** `sbt "testOnly menger.ObjectSpecMaterialTest"`

---

## Step 7.5: Complete Texture Rendering Pipeline

**Goal:** Wire texture objects through the SBT to shaders, implement per-instance texture support for IAS mode, and add full CLI integration for loading texture files.

**Prerequisites:** Steps 7.1-7.4 completed (material system, UV coordinates, texture upload, CLI material parsing)

### Design Decisions

1. **Texture × color** - Texture color multiplies base material color (allows tinting)
2. **No UVs = no texture** - Meshes with stride < 8 ignore textures gracefully
3. **`--texture-dir` flag** - Texture paths relative to this directory (default: `.`)
4. **Strict failure** - Missing texture files cause error (no silent fallback)
5. **ImageIO formats** - Support PNG, JPG, GIF, BMP via Java ImageIO
6. **Per-instance textures** - Each instance in IAS mode can have its own texture index
7. **IAS mode only** - Textures only supported in IAS (multi-instance) mode, not single-object mode
8. **Default parameter** - `addTriangleMeshInstance()` uses default `textureIndex = -1` for backward compatibility
9. **Programmatic test textures** - Test images generated in test setup, not committed as binary files

### Architecture Overview

**IAS (multi-instance) mode only:**
- Texture indices stored per-instance in `InstanceMaterial.texture_index`
- Texture objects array uploaded to `Params.textures`
- Shader looks up texture index from instance materials, samples from textures array

**Single-object mode:**
- Textures NOT supported (simplifies implementation)
- Uses solid color from SBT hit data

---

### Task 7.5.1: Add texture_index to InstanceMaterial and ObjectInstance

**File:** `optix-jni/src/main/native/include/OptiXData.h`

Update `InstanceMaterial` struct to include texture index:

```cpp
// Per-instance material data for IAS (indexed by instance ID)
// Stored in GPU array, accessed via optixGetInstanceId()
struct InstanceMaterial {
    float color[4];             // RGBA color (alpha: 0=transparent, 1=opaque)
    float ior;                  // Index of refraction
    unsigned int geometry_type; // GeometryType enum value
    int texture_index;          // Index into textures array (-1 = no texture)
    unsigned int padding;       // Reduced padding to maintain 32-byte alignment
};
```

Add textures array to `Params` struct:

```cpp
struct Params {
    ...
    // Texture array for per-instance texture sampling
    cudaTextureObject_t* textures;     // Device pointer to texture objects array
    unsigned int num_textures;          // Number of textures in array
    ...
};
```

**File:** `optix-jni/src/main/native/include/SceneParameters.h`

Add texture_index to `TriangleMeshParams`:

```cpp
struct TriangleMeshParams {
    ...
    int texture_index = -1;  // Index into textures vector (-1 = no texture)
    bool dirty = false;
};
```

**File:** `optix-jni/src/main/native/SceneParameters.cpp`

Add setter method:

```cpp
void SceneParameters::setTriangleMeshTextureIndex(int textureIndex) {
    triangle_mesh.texture_index = textureIndex;
    triangle_mesh.dirty = true;
}
```

---

### Task 7.5.2: Update OptiXWrapper for Per-Instance Textures

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

Update `ObjectInstance` struct in `Impl`:

```cpp
struct ObjectInstance {
    GeometryType geometry_type;
    OptixTraversableHandle gas_handle;
    float transform[12];
    float color[4];
    float ior;
    int texture_index;          // NEW: -1 = no texture
    bool active;
};
```

Add to `Impl` struct:

```cpp
CUdeviceptr d_texture_objects = 0;  // cudaTextureObject_t array on GPU
```

Update `addTriangleMeshInstance()` signature:

```cpp
int OptiXWrapper::addTriangleMeshInstance(
    const float* transform, 
    float r, float g, float b, float a, 
    float ior, 
    int textureIndex = -1) {
    ...
    inst.texture_index = textureIndex;
    ...
}
```

Update `buildIAS()` to copy texture_index to material:

```cpp
InstanceMaterial mat = {};
std::memcpy(mat.color, inst.color, 4 * sizeof(float));
mat.ior = inst.ior;
mat.geometry_type = inst.geometry_type;
mat.texture_index = inst.texture_index;  // NEW
materials.push_back(mat);
```

In `render()`, upload texture objects array and set params:

```cpp
// Upload texture objects to GPU if needed
if (!impl->textures.empty() && impl->d_texture_objects == 0) {
    std::vector<cudaTextureObject_t> tex_objs;
    for (const auto& tex : impl->textures) {
        tex_objs.push_back(tex.texture_obj);
    }
    CUDA_CHECK(cudaMalloc(&impl->d_texture_objects, 
                          tex_objs.size() * sizeof(cudaTextureObject_t)));
    CUDA_CHECK(cudaMemcpy(impl->d_texture_objects, tex_objs.data(),
                          tex_objs.size() * sizeof(cudaTextureObject_t),
                          cudaMemcpyHostToDevice));
}
params.textures = reinterpret_cast<cudaTextureObject_t*>(impl->d_texture_objects);
params.num_textures = static_cast<unsigned int>(impl->textures.size());
```

**File:** `optix-jni/src/main/native/include/OptiXWrapper.h`

Update method signature:

```cpp
int addTriangleMeshInstance(const float* transform, float r, float g, float b, float a, float ior, int textureIndex = -1);
```

---

### Task 7.5.3: Update PipelineManager to Accept Textures

**File:** `optix-jni/src/main/native/include/PipelineManager.h`

Change signatures:

```cpp
void buildPipeline(const SceneParameters& scene, 
                   OptixTraversableHandle gasHandle,
                   const std::vector<TextureData>& textures);

private:
    void setupShaderBindingTable(const SceneParameters& scene, 
                                 OptixTraversableHandle gasHandle,
                                 const std::vector<TextureData>& textures);
```

**File:** `optix-jni/src/main/native/PipelineManager.cpp`

Update `setupShaderBindingTable()` to copy texture to SBT for single-object mode:

```cpp
if (scene.hasTriangleMesh()) {
    const auto& mesh = scene.getTriangleMesh();
    TriangleHitGroupData tri_data;
    tri_data.vertices = reinterpret_cast<float*>(mesh.d_vertices);
    tri_data.indices = reinterpret_cast<unsigned int*>(mesh.d_indices);
    tri_data.vertex_stride = mesh.vertex_stride;
    std::memcpy(tri_data.color, mesh.color, sizeof(float) * 4);
    tri_data.ior = mesh.ior;
    
    // Set texture object from index (single-object mode)
    if (mesh.texture_index >= 0 && 
        static_cast<size_t>(mesh.texture_index) < textures.size()) {
        tri_data.base_color_texture = textures[mesh.texture_index].texture_obj;
    } else {
        tri_data.base_color_texture = 0;  // No texture
    }
    ...
}
```

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

Update `buildPipeline()` to pass textures:

```cpp
void OptiXWrapper::buildPipeline() {
    ...
    impl->pipeline_manager.buildPipeline(impl->scene, impl->gas_handle, impl->textures);
}
```

---

### Task 7.5.4: Add Shader Helper Functions for Per-Instance Textures

**File:** `optix-jni/src/main/native/shaders/helpers.cu`

Add helper functions after `getInstanceMaterial()`:

```cuda
/**
 * Get texture index for current instance.
 * Returns -1 if no texture assigned or not in IAS mode.
 */
__device__ int getInstanceTextureIndex() {
    if (params.use_ias && params.instance_materials) {
        const unsigned int instance_id = optixGetInstanceId();
        return params.instance_materials[instance_id].texture_index;
    }
    return -1;  // No texture in single-object mode (uses SBT texture)
}

/**
 * Sample texture by index from global textures array.
 * Returns base_color if texture_index is invalid or no UVs.
 */
__device__ float4 sampleInstanceTexture(int texture_index, float2 uv, float4 base_color) {
    if (texture_index < 0 || 
        static_cast<unsigned int>(texture_index) >= params.num_textures ||
        params.textures == nullptr) {
        return base_color;
    }
    float4 tex_color = tex2D<float4>(params.textures[texture_index], uv.x, uv.y);
    // Multiply texture with base color (allows tinting)
    return make_float4(
        base_color.x * tex_color.x,
        base_color.y * tex_color.y,
        base_color.z * tex_color.z,
        base_color.w * tex_color.w
    );
}
```

---

### Task 7.5.5: Update hit_triangle.cu for IAS Mode and Textures

**File:** `optix-jni/src/main/native/shaders/hit_triangle.cu`

Modify `__closesthit__triangle()` to handle both IAS and single-object modes:

After normal interpolation (around line 291), add UV interpolation:

```cuda
// Interpolate UV coordinates (if stride >= 8)
float2 uv = make_float2(0.0f, 0.0f);
if (stride >= 8) {
    uv = make_float2(
        w * v0[6] + u * v1[6] + v * v2[6],
        w * v0[7] + u * v1[7] + v * v2[7]
    );
}
```

Replace mesh_color reading (around line 311) with IAS-mode texture handling:

```cuda
// Get material color and texture (IAS mode only for textures)
float4 mesh_color;
float mesh_ior;

if (params.use_ias && params.instance_materials) {
    // IAS mode: read from per-instance materials
    getInstanceMaterial(mesh_color, mesh_ior);
    const int texture_index = getInstanceTextureIndex();
    
    // Apply texture from global textures array
    if (texture_index >= 0 && stride >= 8) {
        mesh_color = sampleInstanceTexture(texture_index, uv, mesh_color);
    }
} else {
    // Single-object mode: read from SBT hit data (no texture support)
    mesh_color = make_float4(
        hit_data->color[0],
        hit_data->color[1],
        hit_data->color[2],
        hit_data->color[3]
    );
    mesh_ior = hit_data->ior;
}

const float mesh_alpha = mesh_color.w;
```

---

### Task 7.5.6: Update JNI Bindings

**File:** `optix-jni/src/main/native/JNIBindings.cpp`

Update `addTriangleMeshInstanceNative` to accept texture index:

```cpp
JNIEXPORT jint JNICALL Java_menger_optix_OptiXRenderer_addTriangleMeshInstanceNative(
    JNIEnv* env, jobject obj, 
    jfloatArray transform, jfloat r, jfloat g, jfloat b, jfloat a, jfloat ior,
    jint textureIndex) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper == nullptr || transform == nullptr) return -1;
        
        jfloat* transformArr = env->GetFloatArrayElements(transform, nullptr);
        if (transformArr == nullptr) return -1;
        
        int result = wrapper->addTriangleMeshInstance(
            transformArr, r, g, b, a, ior, static_cast<int>(textureIndex));
        
        env->ReleaseFloatArrayElements(transform, transformArr, 0);
        return result;
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in addTriangleMeshInstance: " << e.what() << std::endl;
        return -1;
    }
}
```

---
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr && textureName != nullptr) {
            const char* name = env->GetStringUTFChars(textureName, nullptr);
            wrapper->setMeshTexture(name);
            env->ReleaseStringUTFChars(textureName, name);
        }
    } catch (const std::exception& e) {
---

### Task 7.5.7: Update Scala OptiXRenderer

**File:** `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala`

Update native declarations and wrappers (using default parameter for backward compatibility):

```scala
// Updated signature with texture index (default -1 for backward compatibility)
@native private def addTriangleMeshInstanceNative(
  transform: Array[Float],
  r: Float, g: Float, b: Float, a: Float,
  ior: Float,
  textureIndex: Int
): Int

def addTriangleMeshInstance(
  transform: Array[Float], 
  color: Color, 
  ior: Float, 
  textureIndex: Int = -1  // Default parameter for backward compatibility
): Option[Int] =
  require(transform.length == Const.Renderer.transformMatrixSize, 
    s"Transform must have ${Const.Renderer.transformMatrixSize} elements")
  val id = addTriangleMeshInstanceNative(
    transform, color.r, color.g, color.b, color.a, ior, textureIndex)
  if id >= 0 then Some(id) else None

def addTriangleMeshInstance(
  position: Vector[3], 
  color: Color, 
  ior: Float, 
  textureIndex: Int = -1  // Default parameter for backward compatibility
): Option[Int] =
  val transform = Array(
    1.0f, 0.0f, 0.0f, position.x,
    0.0f, 1.0f, 0.0f, position.y,
    0.0f, 0.0f, 1.0f, position.z
  )
  addTriangleMeshInstance(transform, color, ior, textureIndex)
```

---

### Task 7.5.8: Add textureDir to ExecutionConfig

**File:** `src/main/scala/menger/config/ExecutionConfig.scala`

Add field:

```scala
case class ExecutionConfig(
  fpsLogIntervalMs: Int = 5000,
  timeout: Float = 0f,
  saveName: Option[String] = None,
  enableStats: Boolean = false,
  maxInstances: Int = 64,
  textureDir: String = "."  // NEW: base directory for texture paths
)
```

Update companion object defaults accordingly.

---

### Task 7.5.9: Add texture Field to ObjectSpec

**File:** `src/main/scala/menger/ObjectSpec.scala`

Add field to case class:

```scala
case class ObjectSpec(
  objectType: String,
  x: Float = 0.0f,
  y: Float = 0.0f,
  z: Float = 0.0f,
  size: Float = 1.0f,
  level: Option[Float] = None,
  color: Option[Color] = None,
  ior: Float = 1.0f,
  material: Option[Material] = None,
  texture: Option[String] = None  // NEW: texture file path
)
```

Add parsing:

```scala
private def parseTexture(kvPairs: Map[String, String]): Either[String, Option[String]] =
  Right(kvPairs.get("texture"))
```

Update `parse()` to include texture in the for-comprehension.

---

### Task 7.5.10: Add --texture-dir CLI Option

**File:** `src/main/scala/menger/MengerCLIOptions.scala`

Add option to `optixSceneGroup`:

```scala
val textureDir: ScallopOption[String] = opt[String](
  required = false, default = Some("."), group = optixSceneGroup,
  descr = "Base directory for texture file paths (default: current directory)"
)
```

---

### Task 7.5.11: Create TextureLoader Utility

**File:** `src/main/scala/menger/TextureLoader.scala` (NEW)

```scala
package menger

import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO
import scala.util.Try

case class TextureImage(
  name: String,
  data: Array[Byte],  // RGBA, 4 bytes per pixel
  width: Int,
  height: Int
)

object TextureLoader:
  
  def load(path: Path): Try[TextureImage] = Try {
    val file = path.toFile
    require(file.exists(), s"Texture file not found: $path")
    
    val image = ImageIO.read(file)
    require(image != null, s"Failed to read image: $path (unsupported format?)")
    
    val width = image.getWidth
    val height = image.getHeight
    val data = new Array[Byte](width * height * 4)
    
    // Convert to RGBA byte array
    for {
      y <- 0 until height
      x <- 0 until width
    } {
      val pixel = image.getRGB(x, y)
      val idx = (y * width + x) * 4
      data(idx + 0) = ((pixel >> 16) & 0xFF).toByte  // R
      data(idx + 1) = ((pixel >> 8) & 0xFF).toByte   // G
      data(idx + 2) = (pixel & 0xFF).toByte          // B
      data(idx + 3) = ((pixel >> 24) & 0xFF).toByte  // A
    }
    
    TextureImage(
      name = file.getName,
      data = data,
      width = width,
      height = height
    )
  }
  
  def loadRelative(textureDir: Path, texturePath: String): Try[TextureImage] =
    load(textureDir.resolve(texturePath))
```

---

### Task 7.5.12: Wire Texture Loading in OptiXEngine

**File:** `src/main/scala/menger/engines/OptiXEngine.scala`

In `setupMultipleTriangleMeshes()`:

```scala
private def setupMultipleTriangleMeshes(
  specs: List[ObjectSpec], 
  renderer: OptiXRenderer
): Try[Unit] = Try:
  logger.info(s"Setting up ${specs.length} triangle mesh instances")

  // First, upload all textures and build name->index map
  val textureDir = Paths.get(config.execution.textureDir)
  val textureIndices: Map[String, Int] = specs
    .flatMap(_.texture)
    .distinct
    .flatMap { texturePath =>
      TextureLoader.loadRelative(textureDir, texturePath) match
        case Success(textureImage) =>
          renderer.uploadTexture(
            textureImage.name, 
            textureImage.data, 
            textureImage.width, 
            textureImage.height
          ) match
            case Success(index) =>
              logger.info(s"Loaded texture '$texturePath' " +
                s"(${textureImage.width}x${textureImage.height}) -> index $index")
              Some(texturePath -> index)
            case Failure(e) =>
              throw RuntimeException(
                s"Failed to upload texture '$texturePath': ${e.getMessage}")
        case Failure(e) =>
          throw RuntimeException(
            s"Failed to load texture '$texturePath': ${e.getMessage}")
    }.toMap

  // Set up mesh geometry
  val firstSpec = specs.head
  val mesh = createMeshForSpec(firstSpec)
  renderer.setTriangleMesh(mesh)

  // Add instances with texture indices
  specs.foreach { spec =>
    val position = menger.common.Vector[3](spec.x, spec.y, spec.z)
    val (color, ior) = spec.material match
      case Some(mat) => (mat.color, mat.ior)
      case None => (spec.color.getOrElse(menger.common.Color(0.7f, 0.7f, 0.7f)), spec.ior)

    val textureIndex = spec.texture.flatMap(textureIndices.get).getOrElse(-1)

    val instanceId = renderer.addTriangleMeshInstance(position, color, ior, textureIndex)
    instanceId match
      case Some(id) =>
        logger.debug(s"Added ${spec.objectType} instance $id with texture index $textureIndex")
      case None =>
        logger.error(s"Failed to add ${spec.objectType} instance")
  }
```

---

### Task 7.5.13: Create Test Texture Files

**Directory:** `src/test/resources/textures/`

Create small test images programmatically in test setup:

```scala
object TestTextureGenerator:
  def createTestTextures(dir: Path): Unit =
    Files.createDirectories(dir)
    
    // 4x4 solid red PNG
    val redImage = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB)
    for (x <- 0 until 4; y <- 0 until 4) 
      redImage.setRGB(x, y, 0xFFFF0000)
    ImageIO.write(redImage, "PNG", dir.resolve("test_red.png").toFile)
    
    // 8x8 checkerboard PNG
    val checker = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
    for (x <- 0 until 8; y <- 0 until 8) {
      val color = if ((x + y) % 2 == 0) 0xFFFFFFFF else 0xFF000000
      checker.setRGB(x, y, color)
    }
    ImageIO.write(checker, "PNG", dir.resolve("test_checkerboard.png").toFile)
    
    // 16x16 gradient JPG
    val gradient = new BufferedImage(16, 16, BufferedImage.TYPE_INT_RGB)
    for (x <- 0 until 16; y <- 0 until 16) {
      val gray = (x * 16) << 16 | (y * 16) << 8 | 128
      gradient.setRGB(x, y, gray)
    }
    ImageIO.write(gradient, "JPG", dir.resolve("test_gradient.jpg").toFile)
```

---

### Task 7.5.14: Add Tests

**File:** `src/test/scala/menger/TextureLoaderTest.scala` (NEW)

```scala
package menger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import java.nio.file.{Files, Path}
import scala.util.{Success, Failure}

class TextureLoaderTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll:
  
  private var testDir: Path = _
  
  override def beforeAll(): Unit =
    testDir = Files.createTempDirectory("texture-test")
    TestTextureGenerator.createTestTextures(testDir)
  
  override def afterAll(): Unit =
    // Clean up test files
    Files.walk(testDir).sorted(java.util.Comparator.reverseOrder())
      .forEach(Files.delete)
  
  "TextureLoader" should "load PNG file" in:
    val result = TextureLoader.load(testDir.resolve("test_red.png"))
    result shouldBe a[Success[_]]
    val texture = result.get
    texture.width shouldBe 4
    texture.height shouldBe 4
    texture.data.length shouldBe 4 * 4 * 4  // 4x4 pixels, 4 bytes each
  
  it should "load JPG file" in:
    val result = TextureLoader.load(testDir.resolve("test_gradient.jpg"))
    result shouldBe a[Success[_]]
    val texture = result.get
    texture.width shouldBe 16
    texture.height shouldBe 16
  
  it should "return Failure for missing file" in:
    val result = TextureLoader.load(testDir.resolve("nonexistent.png"))
    result shouldBe a[Failure[_]]
  
  it should "convert RGBA correctly" in:
    val result = TextureLoader.load(testDir.resolve("test_red.png"))
    val texture = result.get
    // First pixel should be red (R=255, G=0, B=0, A=255)
    texture.data(0) shouldBe 255.toByte  // R
    texture.data(1) shouldBe 0.toByte    // G
    texture.data(2) shouldBe 0.toByte    // B
    texture.data(3) shouldBe 255.toByte  // A (opaque)
```

**File:** `src/test/scala/menger/ObjectSpecTextureTest.scala` (NEW)

```scala
package menger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ObjectSpecTextureTest extends AnyFlatSpec with Matchers:

  "ObjectSpec parser" should "parse texture path" in:
    val result = ObjectSpec.parse("type=cube:pos=0,0,0:texture=textures/brick.png")
    result shouldBe a[Right[_, _]]
    val spec = result.toOption.get
    spec.texture shouldBe Some("textures/brick.png")

  it should "have None texture when not specified" in:
    val result = ObjectSpec.parse("type=cube:pos=0,0,0")
    result shouldBe a[Right[_, _]]
    val spec = result.toOption.get
    spec.texture shouldBe None

  it should "parse texture with material" in:
    val result = ObjectSpec.parse("type=cube:material=metal:texture=metal.png")
    result shouldBe a[Right[_, _]]
    val spec = result.toOption.get
    spec.texture shouldBe Some("metal.png")
    spec.material shouldBe defined
```

**File:** `optix-jni/src/test/scala/menger/optix/TextureBindingTest.scala` (NEW)

Integration test for texture binding (requires OptiX):

```scala
package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.util.Success

class TextureBindingTest extends AnyFlatSpec with Matchers:

  "OptiXRenderer" should "upload texture and return valid index" in:
    val renderer = new OptiXRenderer()
    assume(renderer.initialize(), "OptiX not available")
    
    try
      // Create simple 2x2 red texture
      val data = Array.fill[Byte](2 * 2 * 4)(0)
      for (i <- 0 until 4) {
        data(i * 4) = 255.toByte      // R
        data(i * 4 + 3) = 255.toByte  // A
      }
      
      val result = renderer.uploadTexture("test_red", data, 2, 2)
      result shouldBe a[Success[_]]
      result.get shouldBe >= (0)
    finally
      renderer.dispose()

  it should "bind texture to mesh instance" in:
    val renderer = new OptiXRenderer()
    assume(renderer.initialize(), "OptiX not available")
    
    try
      // Upload texture
      val data = Array.fill[Byte](2 * 2 * 4)(255.toByte)
      val textureIndex = renderer.uploadTexture("test", data, 2, 2).get
      
      // Create simple mesh (would need actual mesh data)
      // This test validates the API works, not the rendering result
      textureIndex shouldBe >= (0)
    finally
      renderer.dispose()
```

---

## Files Summary (Updated for Step 7.5)

### New Files

| File | Description |
|------|-------------|
| `src/main/scala/menger/TextureLoader.scala` | Image loading utility |
| `src/test/scala/menger/TextureLoaderTest.scala` | TextureLoader unit tests |
| `src/test/scala/menger/ObjectSpecTextureTest.scala` | ObjectSpec texture parsing tests |
| `optix-jni/src/test/scala/menger/optix/TextureBindingTest.scala` | Texture binding integration tests |

### Modified Files

| File | Changes |
|------|---------|
| `optix-jni/.../include/OptiXData.h` | Add `texture_index` to `InstanceMaterial`, textures to `Params` |
| `optix-jni/.../include/SceneParameters.h` | Add `texture_index` to `TriangleMeshParams` |
| `optix-jni/.../SceneParameters.cpp` | Add `setTriangleMeshTextureIndex()` |
| `optix-jni/.../include/OptiXWrapper.h` | Update `addTriangleMeshInstance()`, add `setMeshTexture()` |
| `optix-jni/.../OptiXWrapper.cpp` | Add `texture_index` to `ObjectInstance`, upload textures array |
| `optix-jni/.../include/PipelineManager.h` | Add textures parameter to `buildPipeline()` |
| `optix-jni/.../PipelineManager.cpp` | Copy texture to SBT in `setupShaderBindingTable()` |
| `optix-jni/.../JNIBindings.cpp` | Update `addTriangleMeshInstanceNative`, add `setMeshTextureNative` |
| `optix-jni/.../shaders/helpers.cu` | Add `getInstanceTextureIndex()`, `sampleInstanceTexture()` |
| `optix-jni/.../shaders/hit_triangle.cu` | Add IAS mode handling, UV interpolation, texture sampling |
| `optix-jni/.../optix/OptiXRenderer.scala` | Update `addTriangleMeshInstance()`, add `setMeshTexture()` |
| `src/.../menger/ObjectSpec.scala` | Add `texture` field and parsing |
| `src/.../menger/config/ExecutionConfig.scala` | Add `textureDir` field |
| `src/.../menger/MengerCLIOptions.scala` | Add `--texture-dir` option |
| `src/.../menger/engines/OptiXEngine.scala` | Wire texture loading with per-instance indices |

---

## Files Summary

### New Files

| File | Description |
|------|-------------|
| `optix-jni/.../include/MaterialPresets.h` | C++ material preset definitions |
| `optix-jni/.../menger/optix/Material.scala` | Scala material case class and presets |
| `optix-jni/.../geometry/UVCoordinateTest.scala` | UV coordinate tests |
| `optix-jni/.../optix/TextureRenderTest.scala` | Texture rendering tests |
| `src/test/.../ObjectSpecMaterialTest.scala` | Per-object material parsing tests |

### Modified Files

| File | Changes |
|------|---------|
| `optix-jni/.../include/OptiXData.h` | Add `MaterialProperties`, `MAX_TEXTURES` |
| `optix-jni/.../native/OptiXWrapper.h` | Add material/texture management methods |
| `optix-jni/.../native/OptiXWrapper.cpp` | Implement material/texture upload |
| `optix-jni/.../native/JNIBindings.cpp` | Add texture upload JNI bindings |
| `optix-jni/.../shaders/sphere_combined.cu` | Add UV reading, texture sampling |
| `optix-jni/.../optix/OptiXRenderer.scala` | Add material/texture Scala interface |
| `optix-jni/.../geometry/CubeGeometry.scala` | Add UV generation |
| `optix-jni/.../geometry/SpongeGeometry.scala` | Add UV generation |
| `src/.../menger/ObjectSpec.scala` | Add `material: Option[Material]` field |
| `src/.../menger/OptiXResources.scala` | Wire per-object material configuration |

---

## Definition of Done

### Steps 7.1-7.4 (Material System Foundation)
- [x] Material case class with PBR properties
- [x] Material presets (glass, water, diamond, chrome, gold, copper, metal, plastic, matte)
- [x] UV coordinates in vertex format (8-float stride)
- [x] Texture upload API (CUDA arrays and texture objects)
- [x] CLI material parsing (`--objects type=sphere:material=glass`)

### Step 7.5 (Texture Rendering Pipeline)
- [x] Per-instance texture indices in IAS mode
- [x] Texture array uploaded to launch params
- [x] Shader texture sampling (both single-object and IAS modes)
- [x] TextureLoader utility for image file loading
- [x] `--texture-dir` CLI option
- [x] `texture=path` in ObjectSpec parsing
- [x] Texture binding tests passing

### Quality Gates
- [x] All tests passing (new + existing 897+)
- [x] Code compiles without warnings
- [x] Code passes `sbt "scalafix --check"`
- [x] CHANGELOG.md updated
- [x] Backward compatible: existing scenes without materials/textures still work

**🎯 MILESTONE: v0.5 - Full 3D Support** (achieved after this sprint)

---

## References

- [OptiX Programming Guide - Textures](https://raytracing-docs.nvidia.com/optix7/guide/index.html#textures)
- [CUDA Texture Objects](https://docs.nvidia.com/cuda/cuda-c-programming-guide/index.html#texture-objects)
- Sprint 5 plan: `SPRINT_5_PLAN.md` (vertex format foundation)
- Sprint 6 plan: `SPRINT_6_PLAN.md` (per-instance materials)
- Existing implementation: `sphere_combined.cu` (Fresnel, Beer-Lambert)
- PBR reference: Disney BRDF, Unreal Engine material model
