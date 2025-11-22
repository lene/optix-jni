# Sprint 5: Triangle Mesh Foundation + Cube

**Created:** 2025-11-22
**Status:** 📋 PLANNED
**Estimated Effort:** 12-18 hours
**Branch:** TBD (create from `main` when starting)

## Overview

Establish the infrastructure for triangle mesh rendering in OptiX with a working cube primitive. This sprint lays the foundation for all future mesh-based geometry (sponges, 4D projections, arbitrary objects).

### Sprint Goal

Render an opaque and glass cube via `--object cube`, proving the triangle mesh pipeline works end-to-end.

### Success Criteria

**Core Features:**
- [ ] `--object cube` renders a solid colored cube
- [ ] `--object cube` with transparency/IOR renders a glass cube with refraction
- [ ] Triangle mesh infrastructure is reusable for future geometry types

**Polish (Step 5.5):**
- [ ] PTX packaging works for distribution
- [ ] CLI options organized by category in help output
- [ ] Render options encapsulated in config objects
- [ ] Window resize doesn't change render resolution
- [ ] CLI errors show usage hints

**Quality:**
- [ ] All new code has tests
- [ ] Existing 897+ tests still pass

---

## Quality Requirements & Validation

**Reference:** [arc42 Section 10 - Quality Requirements](../docs/arc42/10-quality-requirements.md)

### Metrics to Establish Baselines

This sprint introduces new geometry type, requiring establishment of baseline metrics:

| Metric | Arc42 Ref | Sprint 5 Goal |
|--------|-----------|---------------|
| Cube render time (800×600) | P2 | **Establish baseline** - measure and document |
| New geometry type implementation time | M4 | **Validate** - target < 1 day (sprint proves architecture) |
| Triangle GAS build time | New | **Establish baseline** - measure for 12 triangles |
| Triangle shader performance | P2 | Should not exceed sphere render time by >50% |

### Quality Scenarios to Validate

| ID | Scenario | Validation |
|----|----------|------------|
| V4 | Shadow edges (no acne) | Visual test: cube shadows should be clean |
| V1-V3 | Physics accuracy | Glass cube refraction should match sphere behavior |
| R1 | Test count | Sprint adds ~15-20 new tests, total remains 897+ |
| M1-M2 | Code quality | Zero Scalafix/Wartremover violations |

### Sprint 5 Quality Deliverables

1. **Document baseline render time** for cube at 800×600 in Definition of Done
2. **Document GAS build time** for 12-triangle cube mesh
3. **Visual regression test** - screenshot comparison with expected output
4. **Performance regression** - ensure existing sphere render time unchanged

---

## Architectural Decisions

These decisions affect future sprints. Document rationale to avoid regret later.

### AD-1: Triangle geometry coexists with analytical sphere

**Decision:** Keep the existing analytical sphere; add triangle mesh as a parallel path.

**Rationale:**
- Analytical sphere is optimized and working well
- Breaking change would disrupt existing functionality
- Different geometry types can coexist in same scene (Sprint 6)

**Impact:** Need to handle both sphere and triangle closest-hit shaders in SBT.

### AD-2: Build GAS for triangles, prepare for IAS in Sprint 6

**Decision:** Single Geometry Acceleration Structure (GAS) for Sprint 5. Instance Acceleration Structure (IAS) deferred to Sprint 6.

**Rationale:**
- Single cube doesn't need instancing
- IAS adds complexity (transforms, instance SBT offsets)
- Sprint 6 (multiple objects) is the right place for IAS

**Impact:** Sprint 5 cube has no independent transform; Sprint 6 will refactor to IAS.

### AD-3: Vertex format = position + normal (UVs in Sprint 7)

**Decision:** Vertex buffer contains position (float3) and normal (float3). No UVs yet.

**Rationale:**
- Position needed for geometry
- Normal needed for lighting (diffuse shading, refraction)
- UVs only needed for textures (Sprint 7: Materials)
- Adding UVs later is straightforward (extend vertex struct)

**Impact:** Vertex stride = 24 bytes (6 floats). Will change in Sprint 7.

### AD-4: Per-face normals for cube (24 vertices)

**Decision:** Use per-face normals, meaning 24 vertices (4 per face × 6 faces) not 8.

**Rationale:**
- Cube has sharp edges; per-vertex normals would smooth them incorrectly
- Per-face = duplicate vertices at corners, each with face's normal
- Future smooth objects can use per-vertex normals (same infrastructure)

**Impact:** Cube vertex count = 24, index count = 36 (12 triangles × 3).

### AD-5: 32-bit indices from the start

**Decision:** Use 32-bit (unsigned int) indices, not 16-bit.

**Rationale:**
- Sponge at level 3+ exceeds 65535 vertices (16-bit limit)
- 32-bit has negligible performance impact on modern GPUs
- Avoids breaking change later

**Impact:** Index buffer uses `unsigned int` throughout.

### AD-6: Add triangle shader to sphere_combined.cu

**Decision:** Add triangle closest-hit shader to existing `sphere_combined.cu`.

**Rationale:**
- Single PTX file simplifies build and loading
- Shader can share utility functions (refraction, Fresnel, etc.)
- Refactor to separate files in Sprint 6 if file becomes unwieldy

**Impact:** `sphere_combined.cu` grows; may need refactoring later.

### AD-7: Cube refraction uses face-based inside/outside tracking

**Decision:** Track ray inside/outside state based on face normal dot product, not analytical containment.

**Rationale:**
- Cube doesn't have analytical inside test like sphere
- `dot(ray_dir, normal) < 0` means entering (ray against normal)
- Same approach works for any convex mesh

**Impact:** Refraction code differs slightly from sphere; shared Fresnel/Beer-Lambert.

---

## Step 5.1: Triangle Mesh Infrastructure (4-6 hours)

### Goal
OptiX can receive vertex/index buffers from Scala and build acceleration structure.

---

### Task 5.1.1: Add TriangleMeshData struct to OptiXData.h

**File:** `optix-jni/src/main/native/include/OptiXData.h`

**Location:** After the `Light` struct (around line 50)

**Add this code:**

```cpp
// Triangle mesh data for rendering arbitrary geometry
struct TriangleMeshData {
    float* vertices;              // Interleaved position (x,y,z) + normal (nx,ny,nz)
    unsigned int* indices;        // Triangle indices (3 per triangle)
    unsigned int num_vertices;    // Number of vertices
    unsigned int num_triangles;   // Number of triangles

    // Material properties (same as sphere for consistency)
    float color[4];               // RGBA (alpha: 0=transparent, 1=opaque)
    float ior;                    // Index of refraction (1.0 = no refraction)
};

// Hit group data for triangle mesh (stored in SBT)
struct TriangleHitGroupData {
    float* vertices;              // Device pointer to vertex data
    unsigned int* indices;        // Device pointer to index data
    float color[4];               // Material color
    float ior;                    // Index of refraction
};
```

**Test:** Compile `sbt "project optixJni" compile` - should succeed with new struct.

---

### Task 5.1.2: Add triangle mesh state to OptiXWrapper

**File:** `optix-jni/src/main/native/include/OptiXWrapper.h`

**Location:** In the public section of `OptiXWrapper` class

**Add these method declarations:**

```cpp
// Triangle mesh support
void setTriangleMesh(
    const float* vertices,        // Interleaved pos+normal, 6 floats per vertex
    unsigned int num_vertices,
    const unsigned int* indices,  // 3 indices per triangle
    unsigned int num_triangles
);
void setTriangleMeshColor(float r, float g, float b, float a);
void setTriangleMeshIOR(float ior);
void clearTriangleMesh();         // Remove mesh (render sphere instead)
bool hasTriangleMesh() const;     // Check if mesh is set
```

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Location:** In the `Impl` struct (around line 45), add mesh state:

```cpp
struct OptiXWrapper::Impl {
    // ... existing members ...

    // Triangle mesh state
    struct TriangleMeshParams {
        CUdeviceptr d_vertices;       // GPU vertex buffer
        CUdeviceptr d_indices;        // GPU index buffer
        unsigned int num_vertices;
        unsigned int num_triangles;
        float color[4];               // RGBA
        float ior;
        bool has_mesh;                // True if mesh is set
        bool dirty;                   // True if rebuild needed
        OptixTraversableHandle gas_handle;
        CUdeviceptr d_gas_output_buffer;
    } triangle_mesh;
};
```

**Initialize in constructor** (around line 80):

```cpp
OptiXWrapper::OptiXWrapper() : impl(new Impl()) {
    // ... existing init ...

    // Initialize triangle mesh state
    impl->triangle_mesh.d_vertices = 0;
    impl->triangle_mesh.d_indices = 0;
    impl->triangle_mesh.num_vertices = 0;
    impl->triangle_mesh.num_triangles = 0;
    impl->triangle_mesh.color[0] = 0.8f;  // Default gray
    impl->triangle_mesh.color[1] = 0.8f;
    impl->triangle_mesh.color[2] = 0.8f;
    impl->triangle_mesh.color[3] = 1.0f;  // Opaque
    impl->triangle_mesh.ior = 1.0f;
    impl->triangle_mesh.has_mesh = false;
    impl->triangle_mesh.dirty = false;
    impl->triangle_mesh.gas_handle = 0;
    impl->triangle_mesh.d_gas_output_buffer = 0;
}
```

**Test:** Compile should succeed.

---

### Task 5.1.3: Implement setTriangleMesh in OptiXWrapper.cpp

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Location:** After `setSphere()` method (around line 180)

**Add this implementation:**

```cpp
void OptiXWrapper::setTriangleMesh(
    const float* vertices,
    unsigned int num_vertices,
    const unsigned int* indices,
    unsigned int num_triangles
) {
    // Free existing GPU buffers if any
    if (impl->triangle_mesh.d_vertices) {
        CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->triangle_mesh.d_vertices)));
    }
    if (impl->triangle_mesh.d_indices) {
        CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->triangle_mesh.d_indices)));
    }

    // Allocate and copy vertex buffer (6 floats per vertex: pos + normal)
    size_t vertex_size = num_vertices * 6 * sizeof(float);
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->triangle_mesh.d_vertices), vertex_size));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(impl->triangle_mesh.d_vertices),
        vertices,
        vertex_size,
        cudaMemcpyHostToDevice
    ));

    // Allocate and copy index buffer (3 indices per triangle)
    size_t index_size = num_triangles * 3 * sizeof(unsigned int);
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->triangle_mesh.d_indices), index_size));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(impl->triangle_mesh.d_indices),
        indices,
        index_size,
        cudaMemcpyHostToDevice
    ));

    impl->triangle_mesh.num_vertices = num_vertices;
    impl->triangle_mesh.num_triangles = num_triangles;
    impl->triangle_mesh.has_mesh = true;
    impl->triangle_mesh.dirty = true;  // Need to rebuild GAS
}

void OptiXWrapper::setTriangleMeshColor(float r, float g, float b, float a) {
    impl->triangle_mesh.color[0] = r;
    impl->triangle_mesh.color[1] = g;
    impl->triangle_mesh.color[2] = b;
    impl->triangle_mesh.color[3] = a;
}

void OptiXWrapper::setTriangleMeshIOR(float ior) {
    impl->triangle_mesh.ior = ior;
}

void OptiXWrapper::clearTriangleMesh() {
    if (impl->triangle_mesh.d_vertices) {
        CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->triangle_mesh.d_vertices)));
        impl->triangle_mesh.d_vertices = 0;
    }
    if (impl->triangle_mesh.d_indices) {
        CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->triangle_mesh.d_indices)));
        impl->triangle_mesh.d_indices = 0;
    }
    impl->triangle_mesh.has_mesh = false;
    impl->triangle_mesh.dirty = true;
}

bool OptiXWrapper::hasTriangleMesh() const {
    return impl->triangle_mesh.has_mesh;
}
```

**Test:** Compile should succeed. Add unit test (see Task 5.1.7).

---

### Task 5.1.4: Implement buildTriangleMeshGAS

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Location:** After `buildGeometryAccelerationStructure()` (around line 270)

**Add this new method:**

```cpp
void OptiXWrapper::buildTriangleMeshGAS() {
    if (!impl->triangle_mesh.has_mesh) {
        return;  // Nothing to build
    }

    // Free existing GAS if any
    if (impl->triangle_mesh.d_gas_output_buffer) {
        CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->triangle_mesh.d_gas_output_buffer)));
    }

    // === Build triangle GAS using OptixBuildInputTriangleArray ===

    // Vertex buffer descriptor - positions only (normals stored separately)
    // Vertices are interleaved: [px, py, pz, nx, ny, nz, px, py, pz, nx, ny, nz, ...]
    // OptiX needs just positions, so we use stride of 6 floats
    OptixBuildInputTriangleArray triangle_input = {};
    triangle_input.vertexFormat = OPTIX_VERTEX_FORMAT_FLOAT3;
    triangle_input.vertexStrideInBytes = 6 * sizeof(float);  // Skip normals
    triangle_input.numVertices = impl->triangle_mesh.num_vertices;
    triangle_input.vertexBuffers = &impl->triangle_mesh.d_vertices;

    // Index buffer descriptor
    triangle_input.indexFormat = OPTIX_INDICES_FORMAT_UNSIGNED_INT3;
    triangle_input.indexStrideInBytes = 3 * sizeof(unsigned int);
    triangle_input.numIndexTriplets = impl->triangle_mesh.num_triangles;
    triangle_input.indexBuffer = impl->triangle_mesh.d_indices;

    // Flags - allow any hit for transparency support
    unsigned int triangle_flags[1] = { OPTIX_GEOMETRY_FLAG_NONE };
    triangle_input.flags = triangle_flags;
    triangle_input.numSbtRecords = 1;

    // Build input
    OptixBuildInput build_input = {};
    build_input.type = OPTIX_BUILD_INPUT_TYPE_TRIANGLES;
    build_input.triangleArray = triangle_input;

    // Acceleration structure options
    OptixAccelBuildOptions accel_options = {};
    accel_options.buildFlags = OPTIX_BUILD_FLAG_ALLOW_COMPACTION |
                               OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;
    accel_options.operation = OPTIX_BUILD_OPERATION_BUILD;

    // Query memory requirements
    OptixAccelBufferSizes gas_buffer_sizes;
    OPTIX_CHECK(optixAccelComputeMemoryUsage(
        impl->optix_context.getContext(),
        &accel_options,
        &build_input,
        1,  // num build inputs
        &gas_buffer_sizes
    ));

    // Allocate temp and output buffers
    CUdeviceptr d_temp_buffer;
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&d_temp_buffer),
        gas_buffer_sizes.tempSizeInBytes
    ));

    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&impl->triangle_mesh.d_gas_output_buffer),
        gas_buffer_sizes.outputSizeInBytes
    ));

    // Build GAS
    OPTIX_CHECK(optixAccelBuild(
        impl->optix_context.getContext(),
        0,  // CUDA stream
        &accel_options,
        &build_input,
        1,  // num build inputs
        d_temp_buffer,
        gas_buffer_sizes.tempSizeInBytes,
        impl->triangle_mesh.d_gas_output_buffer,
        gas_buffer_sizes.outputSizeInBytes,
        &impl->triangle_mesh.gas_handle,
        nullptr,  // emitted property (for compaction, not used here)
        0
    ));

    // Free temp buffer
    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(d_temp_buffer)));

    impl->triangle_mesh.dirty = false;
}
```

**Note:** Add declaration to `OptiXWrapper.h` (private section):
```cpp
private:
    void buildTriangleMeshGAS();
```

**Test:** Compile should succeed.

---

### Task 5.1.5: Add triangle program groups and update SBT

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Location:** In `createProgramGroups()` method (around line 300)

**Add triangle hit group after sphere hit group:**

```cpp
void OptiXWrapper::createProgramGroups() {
    // ... existing raygen, miss, sphere hit group code ...

    // === Triangle closest-hit program group ===
    OptixProgramGroupDesc triangle_hit_desc = {};
    triangle_hit_desc.kind = OPTIX_PROGRAM_GROUP_KIND_HITGROUP;
    triangle_hit_desc.hitgroup.moduleCH = impl->module;
    triangle_hit_desc.hitgroup.entryFunctionNameCH = "__closesthit__triangle";
    // No intersection program needed - OptiX has built-in triangle intersection
    triangle_hit_desc.hitgroup.moduleIS = nullptr;
    triangle_hit_desc.hitgroup.entryFunctionNameIS = nullptr;

    OptixProgramGroupOptions pg_options = {};
    OPTIX_CHECK(optixProgramGroupCreate(
        impl->optix_context.getContext(),
        &triangle_hit_desc,
        1,
        &pg_options,
        nullptr, nullptr,  // log
        &impl->triangle_hit_group
    ));

    // === Triangle shadow hit group ===
    OptixProgramGroupDesc triangle_shadow_desc = {};
    triangle_shadow_desc.kind = OPTIX_PROGRAM_GROUP_KIND_HITGROUP;
    triangle_shadow_desc.hitgroup.moduleCH = impl->module;
    triangle_shadow_desc.hitgroup.entryFunctionNameCH = "__closesthit__triangle_shadow";

    OPTIX_CHECK(optixProgramGroupCreate(
        impl->optix_context.getContext(),
        &triangle_shadow_desc,
        1,
        &pg_options,
        nullptr, nullptr,
        &impl->triangle_shadow_hit_group
    ));
}
```

**Add to Impl struct:**
```cpp
OptixProgramGroup triangle_hit_group;
OptixProgramGroup triangle_shadow_hit_group;
```

**Update SBT in `createShaderBindingTable()`:**

The SBT needs hit records for both sphere and triangle. The key is setting up the correct SBT offset when tracing rays:
- SBT offset 0 = sphere hit group
- SBT offset 1 = triangle hit group

```cpp
void OptiXWrapper::createShaderBindingTable() {
    // ... existing raygen, miss records ...

    // Hit group records - need both sphere and triangle
    // Record 0: Sphere (primary ray)
    // Record 1: Sphere (shadow ray)
    // Record 2: Triangle (primary ray)
    // Record 3: Triangle (shadow ray)

    struct HitGroupRecord {
        __align__(OPTIX_SBT_RECORD_ALIGNMENT) char header[OPTIX_SBT_RECORD_HEADER_SIZE];
        union {
            HitGroupData sphere_data;
            TriangleHitGroupData triangle_data;
        };
    };

    HitGroupRecord hit_records[4];

    // Sphere primary
    OPTIX_CHECK(optixSbtRecordPackHeader(impl->sphere_hit_group, &hit_records[0]));
    hit_records[0].sphere_data = /* ... existing sphere data ... */;

    // Sphere shadow
    OPTIX_CHECK(optixSbtRecordPackHeader(impl->sphere_shadow_hit_group, &hit_records[1]));

    // Triangle primary
    OPTIX_CHECK(optixSbtRecordPackHeader(impl->triangle_hit_group, &hit_records[2]));
    hit_records[2].triangle_data.vertices = reinterpret_cast<float*>(impl->triangle_mesh.d_vertices);
    hit_records[2].triangle_data.indices = reinterpret_cast<unsigned int*>(impl->triangle_mesh.d_indices);
    std::memcpy(hit_records[2].triangle_data.color, impl->triangle_mesh.color, 4 * sizeof(float));
    hit_records[2].triangle_data.ior = impl->triangle_mesh.ior;

    // Triangle shadow
    OPTIX_CHECK(optixSbtRecordPackHeader(impl->triangle_shadow_hit_group, &hit_records[3]));

    // Upload to GPU and update SBT
    // ...
}
```

**Important:** When tracing rays, use SBT offset based on geometry type:
- Sphere: `sbt_offset = 0` (primary) or `1` (shadow)
- Triangle: `sbt_offset = 2` (primary) or `3` (shadow)

**Test:** Compile should succeed.

---

### Task 5.1.6: Add JNI bindings for triangle mesh

**File:** `optix-jni/src/main/native/JNIBindings.cpp`

**Location:** After existing setter methods (around line 280)

**Add these JNI methods:**

```cpp
JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setTriangleMeshNative(
    JNIEnv* env, jobject obj,
    jfloatArray vertices,
    jint numVertices,
    jintArray indices,
    jint numTriangles
) {
    OptiXWrapper* wrapper = getWrapper(env, obj);
    if (!wrapper) return;

    // Get vertex array
    jfloat* vertexData = env->GetFloatArrayElements(vertices, nullptr);
    if (!vertexData) return;

    // Get index array
    jint* indexData = env->GetIntArrayElements(indices, nullptr);
    if (!indexData) {
        env->ReleaseFloatArrayElements(vertices, vertexData, 0);
        return;
    }

    // Call wrapper (indices need cast from jint to unsigned int)
    wrapper->setTriangleMesh(
        vertexData,
        static_cast<unsigned int>(numVertices),
        reinterpret_cast<unsigned int*>(indexData),
        static_cast<unsigned int>(numTriangles)
    );

    env->ReleaseFloatArrayElements(vertices, vertexData, 0);
    env->ReleaseIntArrayElements(indices, indexData, 0);
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setTriangleMeshColorNative(
    JNIEnv* env, jobject obj,
    jfloat r, jfloat g, jfloat b, jfloat a
) {
    OptiXWrapper* wrapper = getWrapper(env, obj);
    if (wrapper) {
        wrapper->setTriangleMeshColor(r, g, b, a);
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setTriangleMeshIOR(
    JNIEnv* env, jobject obj,
    jfloat ior
) {
    OptiXWrapper* wrapper = getWrapper(env, obj);
    if (wrapper) {
        wrapper->setTriangleMeshIOR(ior);
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_clearTriangleMesh(
    JNIEnv* env, jobject obj
) {
    OptiXWrapper* wrapper = getWrapper(env, obj);
    if (wrapper) {
        wrapper->clearTriangleMesh();
    }
}

JNIEXPORT jboolean JNICALL Java_menger_optix_OptiXRenderer_hasTriangleMesh(
    JNIEnv* env, jobject obj
) {
    OptiXWrapper* wrapper = getWrapper(env, obj);
    return wrapper ? wrapper->hasTriangleMesh() : JNI_FALSE;
}
```

**Test:** Compile should succeed.

---

### Task 5.1.7: Add Scala interface for triangle mesh

**File:** `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala`

**Location:** After existing native method declarations (around line 35)

**Add native declarations:**

```scala
// Triangle mesh support
@native private def setTriangleMeshNative(
    vertices: Array[Float],
    numVertices: Int,
    indices: Array[Int],
    numTriangles: Int
): Unit

@native private def setTriangleMeshColorNative(r: Float, g: Float, b: Float, a: Float): Unit
@native def setTriangleMeshIOR(ior: Float): Unit
@native def clearTriangleMesh(): Unit
@native def hasTriangleMesh(): Boolean
```

**Add public convenience methods (around line 90):**

```scala
def setTriangleMesh(vertices: Array[Float], indices: Array[Int]): Unit =
    require(vertices.length % 6 == 0, "Vertices must have 6 floats per vertex (pos + normal)")
    require(indices.length % 3 == 0, "Indices must have 3 per triangle")
    val numVertices = vertices.length / 6
    val numTriangles = indices.length / 3
    setTriangleMeshNative(vertices, numVertices, indices, numTriangles)

def setTriangleMeshColor(color: Color): Unit =
    setTriangleMeshColorNative(color.r, color.g, color.b, color.a)
```

**Test:** Compile `sbt compile` should succeed.

---

### Task 5.1.8: Unit tests for triangle mesh infrastructure

**File:** `optix-jni/src/test/scala/menger/optix/TriangleMeshTest.scala` (new file)

```scala
package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll

class TriangleMeshTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private var renderer: OptiXRenderer = _
  private var initialized: Boolean = false

  override def beforeAll(): Unit =
    if OptiXRenderer.isLibraryLoaded then
      renderer = new OptiXRenderer()
      initialized = renderer.initialize()

  override def afterAll(): Unit =
    if initialized then renderer.dispose()

  "setTriangleMesh" should "accept valid vertex and index data" in:
    assume(initialized, "OptiX not available")

    // Simple triangle: 3 vertices, 1 triangle
    val vertices = Array[Float](
      // pos x, y, z, normal x, y, z
      0.0f, 0.0f, 0.0f,  0.0f, 1.0f, 0.0f,  // vertex 0
      1.0f, 0.0f, 0.0f,  0.0f, 1.0f, 0.0f,  // vertex 1
      0.5f, 1.0f, 0.0f,  0.0f, 1.0f, 0.0f   // vertex 2
    )
    val indices = Array[Int](0, 1, 2)

    noException should be thrownBy:
      renderer.setTriangleMesh(vertices, indices)

    renderer.hasTriangleMesh() shouldBe true

  it should "reject vertices not divisible by 6" in:
    assume(initialized, "OptiX not available")

    val badVertices = Array[Float](0.0f, 0.0f, 0.0f)  // Only 3 floats
    val indices = Array[Int](0, 0, 0)

    an[IllegalArgumentException] should be thrownBy:
      renderer.setTriangleMesh(badVertices, indices)

  it should "reject indices not divisible by 3" in:
    assume(initialized, "OptiX not available")

    val vertices = Array[Float](
      0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f
    )
    val badIndices = Array[Int](0, 1)  // Only 2 indices

    an[IllegalArgumentException] should be thrownBy:
      renderer.setTriangleMesh(vertices, badIndices)

  "clearTriangleMesh" should "remove mesh" in:
    assume(initialized, "OptiX not available")

    val vertices = Array[Float](
      0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f,
      1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f,
      0.5f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f
    )
    val indices = Array[Int](0, 1, 2)

    renderer.setTriangleMesh(vertices, indices)
    renderer.hasTriangleMesh() shouldBe true

    renderer.clearTriangleMesh()
    renderer.hasTriangleMesh() shouldBe false

  "setTriangleMeshColor" should "accept valid color" in:
    assume(initialized, "OptiX not available")

    noException should be thrownBy:
      renderer.setTriangleMeshColor(Color.fromRGBA(1.0f, 0.5f, 0.0f, 0.8f))

  "setTriangleMeshIOR" should "accept valid IOR" in:
    assume(initialized, "OptiX not available")

    noException should be thrownBy:
      renderer.setTriangleMeshIOR(1.5f)
```

**Run:** `sbt "testOnly menger.optix.TriangleMeshTest"`

---

## Step 5.2: Basic Opaque Cube Rendering (3-4 hours)

### Goal
Render a solid-colored cube using the triangle mesh infrastructure.

---

### Task 5.2.1: Create CubeGeometry.scala

**File:** `optix-jni/src/main/scala/menger/optix/geometry/CubeGeometry.scala` (new file)

```scala
package menger.optix.geometry

object CubeGeometry:

  case class MeshData(
      vertices: Array[Float],  // Interleaved pos + normal
      indices: Array[Int]
  ):
    def numVertices: Int = vertices.length / 6
    def numTriangles: Int = indices.length / 3

  def generate(
      center: (Float, Float, Float) = (0.0f, 0.0f, 0.0f),
      size: Float = 1.0f
  ): MeshData =
    val (cx, cy, cz) = center
    val half = size / 2.0f

    // 8 corner positions
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

    // 6 faces with outward normals
    // Each face: (corner indices CCW, normal)
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

    // Build vertex array: 4 vertices per face × 6 faces = 24 vertices
    // Each vertex: px, py, pz, nx, ny, nz (6 floats)
    val vertexList = scala.collection.mutable.ArrayBuffer[Float]()
    val indexList = scala.collection.mutable.ArrayBuffer[Int]()

    var vertexIndex = 0
    for (cornerIndices, normal) <- faces do
      val (nx, ny, nz) = normal

      // Add 4 vertices for this face
      for ci <- cornerIndices do
        val (px, py, pz) = corners(ci)
        vertexList ++= Array(px, py, pz, nx, ny, nz)

      // Add 2 triangles (CCW winding)
      // Quad vertices: 0, 1, 2, 3 -> triangles (0,1,2) and (0,2,3)
      val base = vertexIndex
      indexList ++= Array(base, base + 1, base + 2)  // First triangle
      indexList ++= Array(base, base + 2, base + 3)  // Second triangle

      vertexIndex += 4

    MeshData(vertexList.toArray, indexList.toArray)
```

**Test:** Add unit test for geometry generation.

---

### Task 5.2.2: Create CubeGeometryTest.scala

**File:** `optix-jni/src/test/scala/menger/optix/geometry/CubeGeometryTest.scala` (new file)

```scala
package menger.optix.geometry

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CubeGeometryTest extends AnyFlatSpec with Matchers:

  "CubeGeometry.generate" should "produce 24 vertices" in:
    val mesh = CubeGeometry.generate()
    mesh.numVertices shouldBe 24

  it should "produce 12 triangles" in:
    val mesh = CubeGeometry.generate()
    mesh.numTriangles shouldBe 12

  it should "produce 144 floats for vertices (24 × 6)" in:
    val mesh = CubeGeometry.generate()
    mesh.vertices.length shouldBe 144

  it should "produce 36 indices (12 × 3)" in:
    val mesh = CubeGeometry.generate()
    mesh.indices.length shouldBe 36

  it should "have all indices in valid range" in:
    val mesh = CubeGeometry.generate()
    all(mesh.indices) should be >= 0
    all(mesh.indices) should be < mesh.numVertices

  it should "have normalized normals" in:
    val mesh = CubeGeometry.generate()
    for i <- 0 until mesh.numVertices do
      val nx = mesh.vertices(i * 6 + 3)
      val ny = mesh.vertices(i * 6 + 4)
      val nz = mesh.vertices(i * 6 + 5)
      val length = math.sqrt(nx * nx + ny * ny + nz * nz)
      length shouldBe 1.0f +- 0.001f

  it should "respect center parameter" in:
    val mesh = CubeGeometry.generate(center = (1.0f, 2.0f, 3.0f), size = 2.0f)

    // Find min/max positions
    val xs = (0 until mesh.numVertices).map(i => mesh.vertices(i * 6))
    val ys = (0 until mesh.numVertices).map(i => mesh.vertices(i * 6 + 1))
    val zs = (0 until mesh.numVertices).map(i => mesh.vertices(i * 6 + 2))

    // Center should be at (1, 2, 3), size 2 means half = 1
    xs.min shouldBe 0.0f +- 0.001f
    xs.max shouldBe 2.0f +- 0.001f
    ys.min shouldBe 1.0f +- 0.001f
    ys.max shouldBe 3.0f +- 0.001f
    zs.min shouldBe 2.0f +- 0.001f
    zs.max shouldBe 4.0f +- 0.001f
```

**Run:** `sbt "testOnly menger.optix.geometry.CubeGeometryTest"`

---

### Task 5.2.3: Implement triangle closest-hit shader

**File:** `optix-jni/src/main/native/shaders/sphere_combined.cu`

**Location:** After `__closesthit__ch` (around line 950)

**Add this shader:**

```cuda
// === TRIANGLE CLOSEST-HIT SHADER ===
extern "C" __global__ void __closesthit__triangle()
{
    // Get triangle data from SBT
    const TriangleHitGroupData* hit_data =
        reinterpret_cast<const TriangleHitGroupData*>(optixGetSbtDataPointer());

    // Get hit information
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    const float t_hit = optixGetRayTmax();
    const float3 hit_point = ray_origin + ray_direction * t_hit;

    // Get primitive index and barycentric coordinates
    const unsigned int prim_idx = optixGetPrimitiveIndex();
    const float2 barycentrics = optixGetTriangleBarycentrics();

    // Get triangle indices
    const unsigned int idx0 = hit_data->indices[prim_idx * 3 + 0];
    const unsigned int idx1 = hit_data->indices[prim_idx * 3 + 1];
    const unsigned int idx2 = hit_data->indices[prim_idx * 3 + 2];

    // Get normals (interleaved: pos, pos, pos, norm, norm, norm)
    const float3 n0 = make_float3(
        hit_data->vertices[idx0 * 6 + 3],
        hit_data->vertices[idx0 * 6 + 4],
        hit_data->vertices[idx0 * 6 + 5]
    );
    const float3 n1 = make_float3(
        hit_data->vertices[idx1 * 6 + 3],
        hit_data->vertices[idx1 * 6 + 4],
        hit_data->vertices[idx1 * 6 + 5]
    );
    const float3 n2 = make_float3(
        hit_data->vertices[idx2 * 6 + 3],
        hit_data->vertices[idx2 * 6 + 4],
        hit_data->vertices[idx2 * 6 + 5]
    );

    // Interpolate normal using barycentric coordinates
    // barycentrics.x = weight for v1, barycentrics.y = weight for v2
    // weight for v0 = 1 - barycentrics.x - barycentrics.y
    const float w0 = 1.0f - barycentrics.x - barycentrics.y;
    const float w1 = barycentrics.x;
    const float w2 = barycentrics.y;
    float3 normal = normalize(w0 * n0 + w1 * n1 + w2 * n2);

    // Determine if ray is entering or exiting (for refraction)
    // If ray direction is against normal, we're entering
    const bool entering = dot(ray_direction, normal) < 0.0f;
    if (!entering) {
        normal = -normal;  // Flip normal for consistent shading
    }

    const unsigned int depth = optixGetPayload_3();

    // Get material properties
    const float4 mesh_color = make_float4(
        hit_data->color[0], hit_data->color[1],
        hit_data->color[2], hit_data->color[3]
    );
    const float mesh_alpha = mesh_color.w;
    const float mesh_ior = hit_data->ior;

    // === FULLY TRANSPARENT ===
    if (mesh_alpha < RayTracingConstants::ALPHA_FULLY_TRANSPARENT_THRESHOLD) {
        // Continue ray through object
        const float3 continue_origin = hit_point + ray_direction * RayTracingConstants::CONTINUATION_RAY_OFFSET;
        unsigned int p0, p1, p2, p3 = depth;
        optixTrace(
            params.handle, continue_origin, ray_direction,
            0.0f, RayTracingConstants::MAX_RAY_DISTANCE, 0.0f,
            OptixVisibilityMask(255), OPTIX_RAY_FLAG_NONE,
            0, 1, 0,  // SBT offset, stride, miss
            p0, p1, p2, p3
        );
        optixSetPayload_0(p0);
        optixSetPayload_1(p1);
        optixSetPayload_2(p2);
        return;
    }

    // === FULLY OPAQUE ===
    if (mesh_alpha >= RayTracingConstants::ALPHA_FULLY_OPAQUE_THRESHOLD) {
        // Simple diffuse shading
        float3 color = make_float3(mesh_color.x, mesh_color.y, mesh_color.z);

        // Calculate lighting (reuse existing function)
        float3 lighting = calculateLighting(hit_point, normal, params);
        color = color * lighting;

        // Clamp and output
        optixSetPayload_0(static_cast<unsigned int>(fminf(color.x, 1.0f) * 255.99f));
        optixSetPayload_1(static_cast<unsigned int>(fminf(color.y, 1.0f) * 255.99f));
        optixSetPayload_2(static_cast<unsigned int>(fminf(color.z, 1.0f) * 255.99f));
        return;
    }

    // === SEMI-TRANSPARENT (GLASS) ===
    // This will be implemented in Step 5.3
    // For now, treat as opaque
    float3 color = make_float3(mesh_color.x, mesh_color.y, mesh_color.z);
    float3 lighting = calculateLighting(hit_point, normal, params);
    color = color * lighting;

    optixSetPayload_0(static_cast<unsigned int>(fminf(color.x, 1.0f) * 255.99f));
    optixSetPayload_1(static_cast<unsigned int>(fminf(color.y, 1.0f) * 255.99f));
    optixSetPayload_2(static_cast<unsigned int>(fminf(color.z, 1.0f) * 255.99f));
}

// Triangle shadow hit - returns alpha for shadow attenuation
extern "C" __global__ void __closesthit__triangle_shadow()
{
    const TriangleHitGroupData* hit_data =
        reinterpret_cast<const TriangleHitGroupData*>(optixGetSbtDataPointer());

    const float alpha = hit_data->color[3];
    optixSetPayload_0(__float_as_uint(alpha));
}
```

**Note:** You'll need to add `calculateLighting()` as a device function if it doesn't exist, or adapt from the sphere shader's lighting code.

**Test:** Compile `sbt "project optixJni" nativeCompile`

---

### Task 5.2.4: Visual test for opaque cube rendering

**File:** `optix-jni/src/test/scala/menger/optix/CubeRenderTest.scala` (new file)

```scala
package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import menger.optix.geometry.CubeGeometry
import menger.common.Color

class CubeRenderTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private var renderer: OptiXRenderer = _
  private var initialized: Boolean = false

  override def beforeAll(): Unit =
    if OptiXRenderer.isLibraryLoaded then
      renderer = new OptiXRenderer()
      initialized = renderer.initialize()
      if initialized then
        // Set up camera looking at origin
        renderer.setCamera(
          Array(0.0f, 2.0f, 5.0f),   // eye
          Array(0.0f, 0.0f, 0.0f),   // lookAt
          Array(0.0f, 1.0f, 0.0f),   // up
          45.0f                       // fov
        )
        // Set up light
        renderer.setLight(Array(-1.0f, 1.0f, 1.0f), 1.0f)

  override def afterAll(): Unit =
    if initialized then renderer.dispose()

  "Opaque cube" should "render without errors" in:
    assume(initialized, "OptiX not available")

    val mesh = CubeGeometry.generate(center = (0.0f, 0.0f, 0.0f), size = 2.0f)
    renderer.setTriangleMesh(mesh.vertices, mesh.indices)
    renderer.setTriangleMeshColor(Color.fromRGB(0.8f, 0.2f, 0.2f))  // Red
    renderer.setTriangleMeshIOR(1.0f)  // No refraction

    val result = renderer.renderWithStats(800, 600)

    result.image should not be empty
    result.image.length shouldBe 800 * 600 * 4
    result.totalRays should be > 0L

  it should "have different colors on different faces" in:
    assume(initialized, "OptiX not available")

    val mesh = CubeGeometry.generate()
    renderer.setTriangleMesh(mesh.vertices, mesh.indices)
    renderer.setTriangleMeshColor(Color.fromRGB(0.8f, 0.8f, 0.8f))  // Gray

    val result = renderer.renderWithStats(800, 600)

    // Check that image has variation (different faces = different shading)
    val pixels = result.image.grouped(4).map(p => (p(0) & 0xFF, p(1) & 0xFF, p(2) & 0xFF)).toArray
    val uniqueColors = pixels.toSet

    // Should have more than just background color
    uniqueColors.size should be > 10

  it should "cast shadows on plane" in:
    assume(initialized, "OptiX not available")

    val mesh = CubeGeometry.generate(center = (0.0f, 0.0f, 0.0f), size = 1.5f)
    renderer.setTriangleMesh(mesh.vertices, mesh.indices)
    renderer.setTriangleMeshColor(Color.fromRGB(0.5f, 0.5f, 0.8f))
    renderer.setPlane(1, true, -2.0f)  // Y-axis plane below cube
    renderer.setShadows(true)

    val withShadows = renderer.renderWithStats(400, 300)

    renderer.setShadows(false)
    val withoutShadows = renderer.renderWithStats(400, 300)

    // Images should differ (shadow visible)
    withShadows.image should not equal withoutShadows.image
```

**Run:** `sbt "testOnly menger.optix.CubeRenderTest"`

---

## Step 5.3: Glass Cube Support (3-5 hours)

### Goal
Render a transparent cube with refraction, reusing existing Fresnel/Beer-Lambert code.

---

### Task 5.3.1: Extend triangle closest-hit for glass

**File:** `optix-jni/src/main/native/shaders/sphere_combined.cu`

**Location:** Replace the "SEMI-TRANSPARENT (GLASS)" section in `__closesthit__triangle`

```cuda
    // === SEMI-TRANSPARENT (GLASS) ===
    if (depth >= RayTracingConstants::MAX_TRACE_DEPTH) {
        // Max depth reached - return black
        optixSetPayload_0(0);
        optixSetPayload_1(0);
        optixSetPayload_2(0);
        return;
    }

    // Fresnel calculation (Schlick approximation)
    const float n1 = entering ? 1.0f : mesh_ior;
    const float n2 = entering ? mesh_ior : 1.0f;

    const float r0 = (n1 - n2) / (n1 + n2);
    const float R0 = r0 * r0;

    const float cos_theta = fabsf(dot(ray_direction, normal));
    const float one_minus_cos = 1.0f - cos_theta;
    const float fresnel = R0 + (1.0f - R0) *
        one_minus_cos * one_minus_cos * one_minus_cos *
        one_minus_cos * one_minus_cos;

    // === Reflection ray ===
    const float3 reflect_dir = ray_direction - 2.0f * dot(ray_direction, normal) * normal;
    const float3 reflect_origin = hit_point + reflect_dir * RayTracingConstants::CONTINUATION_RAY_OFFSET;

    unsigned int reflect_r, reflect_g, reflect_b, reflect_depth = depth + 1;
    optixTrace(
        params.handle, reflect_origin, reflect_dir,
        0.0f, RayTracingConstants::MAX_RAY_DISTANCE, 0.0f,
        OptixVisibilityMask(255), OPTIX_RAY_FLAG_NONE,
        0, 1, 0,
        reflect_r, reflect_g, reflect_b, reflect_depth
    );

    // === Refraction ray (Snell's law) ===
    unsigned int refract_r = 0, refract_g = 0, refract_b = 0;
    const float eta = n1 / n2;
    const float k = 1.0f - eta * eta * (1.0f - cos_theta * cos_theta);

    if (k >= 0.0f) {
        // Refraction possible
        const float3 refract_dir = eta * ray_direction +
            (eta * cos_theta - sqrtf(k)) * normal;
        const float3 refract_origin = hit_point + refract_dir * RayTracingConstants::CONTINUATION_RAY_OFFSET;

        unsigned int refract_depth = depth + 1;
        optixTrace(
            params.handle, refract_origin, refract_dir,
            0.0f, RayTracingConstants::MAX_RAY_DISTANCE, 0.0f,
            OptixVisibilityMask(255), OPTIX_RAY_FLAG_NONE,
            0, 1, 0,
            refract_r, refract_g, refract_b, refract_depth
        );
    } else {
        // Total internal reflection
        refract_r = reflect_r;
        refract_g = reflect_g;
        refract_b = reflect_b;
    }

    // === Beer-Lambert absorption (on exit) ===
    float3 refract_color = make_float3(
        refract_r / 255.0f, refract_g / 255.0f, refract_b / 255.0f
    );

    if (!entering) {
        // Apply absorption based on distance traveled and glass color
        const float3 glass_color = make_float3(mesh_color.x, mesh_color.y, mesh_color.z);
        const float absorption_scale = 2.0f;  // Tune for visual effect

        const float3 extinction = make_float3(
            -logf(fmaxf(glass_color.x, 0.001f)) * mesh_alpha * absorption_scale,
            -logf(fmaxf(glass_color.y, 0.001f)) * mesh_alpha * absorption_scale,
            -logf(fmaxf(glass_color.z, 0.001f)) * mesh_alpha * absorption_scale
        );

        const float3 beer_attenuation = make_float3(
            expf(-extinction.x * t_hit),
            expf(-extinction.y * t_hit),
            expf(-extinction.z * t_hit)
        );

        refract_color = refract_color * beer_attenuation;
    }

    // === Blend reflection and refraction ===
    const float3 reflect_color = make_float3(
        reflect_r / 255.0f, reflect_g / 255.0f, reflect_b / 255.0f
    );

    const float3 final_color = fresnel * reflect_color + (1.0f - fresnel) * refract_color;

    optixSetPayload_0(static_cast<unsigned int>(fminf(final_color.x, 1.0f) * 255.99f));
    optixSetPayload_1(static_cast<unsigned int>(fminf(final_color.y, 1.0f) * 255.99f));
    optixSetPayload_2(static_cast<unsigned int>(fminf(final_color.z, 1.0f) * 255.99f));
```

**Test:** Compile and run visual tests.

---

### Task 5.3.2: Glass cube tests

**File:** `optix-jni/src/test/scala/menger/optix/GlassCubeTest.scala` (new file)

```scala
package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import menger.optix.geometry.CubeGeometry
import menger.common.Color

class GlassCubeTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private var renderer: OptiXRenderer = _
  private var initialized: Boolean = false

  override def beforeAll(): Unit =
    if OptiXRenderer.isLibraryLoaded then
      renderer = new OptiXRenderer()
      initialized = renderer.initialize()
      if initialized then
        renderer.setCamera(
          Array(0.0f, 2.0f, 5.0f),
          Array(0.0f, 0.0f, 0.0f),
          Array(0.0f, 1.0f, 0.0f),
          45.0f
        )
        renderer.setLight(Array(-1.0f, 1.0f, 1.0f), 1.0f)
        renderer.setPlane(1, true, -2.0f)

  override def afterAll(): Unit =
    if initialized then renderer.dispose()

  "Glass cube" should "render with refraction" in:
    assume(initialized, "OptiX not available")

    val mesh = CubeGeometry.generate(size = 1.5f)
    renderer.setTriangleMesh(mesh.vertices, mesh.indices)
    renderer.setTriangleMeshColor(Color.fromRGBA(0.9f, 0.9f, 1.0f, 0.3f))  // Light blue, transparent
    renderer.setTriangleMeshIOR(1.5f)  // Glass

    val result = renderer.renderWithStats(800, 600)

    result.image should not be empty
    // Glass should generate refracted rays
    result.refractedRays should be > 0L

  it should "look different with different IOR values" in:
    assume(initialized, "OptiX not available")

    val mesh = CubeGeometry.generate(size = 1.5f)
    renderer.setTriangleMesh(mesh.vertices, mesh.indices)
    renderer.setTriangleMeshColor(Color.fromRGBA(1.0f, 1.0f, 1.0f, 0.2f))

    renderer.setTriangleMeshIOR(1.0f)  // No refraction
    val ior1 = renderer.renderWithStats(400, 300)

    renderer.setTriangleMeshIOR(1.5f)  // Glass
    val ior15 = renderer.renderWithStats(400, 300)

    renderer.setTriangleMeshIOR(2.4f)  // Diamond
    val ior24 = renderer.renderWithStats(400, 300)

    // All should be different
    ior1.image should not equal ior15.image
    ior15.image should not equal ior24.image

  it should "show colored absorption for tinted glass" in:
    assume(initialized, "OptiX not available")

    val mesh = CubeGeometry.generate(size = 1.5f)
    renderer.setTriangleMesh(mesh.vertices, mesh.indices)
    renderer.setTriangleMeshIOR(1.5f)

    // Clear glass
    renderer.setTriangleMeshColor(Color.fromRGBA(1.0f, 1.0f, 1.0f, 0.3f))
    val clear = renderer.renderWithStats(400, 300)

    // Red tinted glass
    renderer.setTriangleMeshColor(Color.fromRGBA(1.0f, 0.3f, 0.3f, 0.5f))
    val red = renderer.renderWithStats(400, 300)

    // Should be visually different
    clear.image should not equal red.image
```

**Run:** `sbt "testOnly menger.optix.GlassCubeTest"`

---

## Step 5.4: CLI Integration (2-3 hours)

### Goal
`--object cube` works from command line with proper validation and help.

---

### Task 5.4.1: Add --object option to CLI

**File:** `src/main/scala/menger/MengerCLIOptions.scala`

**Location:** After `optix` option (around line 50)

```scala
// Object type selection (OptiX only)
val objectType: ScallopOption[String] = opt[String](
  name = "object",
  required = false,
  default = Some("sphere"),
  descr = "Object to render (OptiX only): sphere, cube"
)

// Cube-specific options (mirror sphere options)
val cubeColor: ScallopOption[String] = opt[String](
  name = "cube-color",
  required = false,
  descr = "Cube color as #RRGGBB or #RRGGBBAA hex (OptiX only)"
)

val cubeIor: ScallopOption[Float] = opt[Float](
  name = "cube-ior",
  required = false,
  default = Some(1.0f),
  validate = _ > 0,
  descr = "Cube index of refraction (default: 1.0, glass: 1.5)"
)

// Validations
validateOpt(objectType) { obj =>
  if Set("sphere", "cube").contains(obj.toLowerCase) then Right(())
  else Left(s"Unknown object type: $obj. Valid: sphere, cube")
}

validateOpt(objectType, optix) { (obj, ox) =>
  if obj != "sphere" && !ox.getOrElse(false) then
    Left(s"--object $obj requires --optix flag")
  else Right(())
}

validateOpt(cubeColor, objectType) { (color, obj) =>
  if color.isDefined && obj != "cube" then
    Left("--cube-color only valid with --object cube")
  else Right(())
}
```

**Test:** `sbt "run --help"` should show new options.

---

### Task 5.4.2: Wire CLI to renderer in OptiXResources

**File:** `src/main/scala/menger/OptiXResources.scala`

**Location:** In configuration section (around line 80)

```scala
// Object type configuration
def configureObject(objectType: String, options: MengerCLIOptions): Unit =
  objectType.toLowerCase match
    case "sphere" =>
      // Existing sphere configuration
      configureSphere(options)

    case "cube" =>
      configureCube(options)

    case other =>
      logger.warn(s"Unknown object type: $other, defaulting to sphere")
      configureSphere(options)

private def configureCube(options: MengerCLIOptions): Unit =
  import menger.optix.geometry.CubeGeometry

  // Generate cube geometry
  val mesh = CubeGeometry.generate(
    center = (0.0f, 0.0f, 0.0f),
    size = options.radius() * 2  // Use radius as half-size for consistency
  )

  renderer.setTriangleMesh(mesh.vertices, mesh.indices)

  // Set color
  options.cubeColor.toOption match
    case Some(hexColor) =>
      val color = parseHexColor(hexColor)
      renderer.setTriangleMeshColor(color)
    case None =>
      // Default: use sphere color if specified, else gray
      options.sphereColor.toOption match
        case Some(hexColor) =>
          val color = parseHexColor(hexColor)
          renderer.setTriangleMeshColor(color)
        case None =>
          renderer.setTriangleMeshColor(Color.fromRGBA(0.7f, 0.7f, 0.8f, 1.0f))

  // Set IOR
  renderer.setTriangleMeshIOR(options.cubeIor())
```

---

### Task 5.4.3: CLI integration test

**File:** `src/test/scala/menger/CLICubeTest.scala` (new file)

```scala
package menger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CLICubeTest extends AnyFlatSpec with Matchers:

  "CLI parser" should "accept --object cube" in:
    val args = Array("--optix", "--object", "cube")
    val options = new MengerCLIOptions(args)

    options.objectType() shouldBe "cube"

  it should "accept --cube-color" in:
    val args = Array("--optix", "--object", "cube", "--cube-color", "#FF0000")
    val options = new MengerCLIOptions(args)

    options.cubeColor() shouldBe "#FF0000"

  it should "accept --cube-ior" in:
    val args = Array("--optix", "--object", "cube", "--cube-ior", "1.5")
    val options = new MengerCLIOptions(args)

    options.cubeIor() shouldBe 1.5f

  it should "reject --object cube without --optix" in:
    val args = Array("--object", "cube")

    an[Exception] should be thrownBy:
      val options = new MengerCLIOptions(args)
      options.verify()

  it should "reject unknown object type" in:
    val args = Array("--optix", "--object", "pyramid")

    an[Exception] should be thrownBy:
      val options = new MengerCLIOptions(args)
      options.verify()

  it should "default to sphere" in:
    val args = Array("--optix")
    val options = new MengerCLIOptions(args)

    options.objectType() shouldBe "sphere"
```

**Run:** `sbt "testOnly menger.CLICubeTest"`

---

## Step 5.5: Polish & Maintenance (2-3 hours)

### Goal
Address accumulated maintenance items after core features are complete.

---

### Task 5.5.1: Fix PTX packaging for distribution

**Problem:** Packaged application can't find optixjni native library - loads from system path instead of bundled location.

**File:** `build.sbt` and potentially `optix-jni/build.sbt`

**Investigation:**
1. Check how native libraries are packaged in JAR
2. Verify PTX files are included in resources
3. Ensure `NativeLoader` looks in correct locations

**Acceptance:** Running packaged JAR finds PTX without manual file copying.

---

### Task 5.5.2: Encapsulate render options (CLI)

**Problem:** Growing number of CLI flags (`--shadows`, `--antialiasing`, `--aa-max-depth`, `--aa-threshold`, etc.) clutters help output.

**File:** `src/main/scala/menger/MengerCLIOptions.scala`

**Changes:**
1. Group related options logically in help output
2. Consider composite flags (e.g., `--quality high` sets multiple options)
3. Add option categories/sections to Scallop configuration

**Example:**
```scala
// Group render quality options
val qualityOptions = group("Render Quality")
val antialiasing = opt[Boolean](group = qualityOptions, ...)
val aaMaxDepth = opt[Int](group = qualityOptions, ...)
```

**Acceptance:** `--help` output is organized by category.

---

### Task 5.5.3: Encapsulate render options (Internal)

**Problem:** `Params` struct and render configuration have many individual parameters passed separately.

**Files:**
- `optix-jni/src/main/native/include/OptiXData.h`
- `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala`

**Changes:**
1. Create `RenderConfig` case class grouping related options
2. Create `LightingConfig` for light-related parameters
3. Refactor setter methods to accept config objects

**Example:**
```scala
case class RenderConfig(
    shadows: Boolean = true,
    antialiasing: Boolean = false,
    aaMaxDepth: Int = 2,
    aaThreshold: Float = 0.1f
)

def setRenderConfig(config: RenderConfig): Unit = ...
```

**Acceptance:** Related parameters grouped in config objects; individual setters still work for backward compatibility.

---

### Task 5.5.4: Disable runtime resolution change on window resize

**Problem:** Resizing the main window can trigger resolution changes that cause rendering artifacts or performance issues.

**File:** `src/main/scala/menger/MengerMain.scala` or LibGDX configuration

**Changes:**
1. Lock render resolution when OptiX is active
2. Window can resize but internal render target stays fixed
3. Scale output to fit window (letterbox if needed)

**Acceptance:** Window resize doesn't trigger OptiX buffer reallocation.

---

### Task 5.5.5: Print CLI help on errors

**Problem:** Invalid CLI arguments show error but not usage help, making it hard for users to correct mistakes.

**File:** `src/main/scala/menger/MengerCLIOptions.scala`

**Changes:**
1. Catch Scallop validation errors
2. Print short usage summary with error
3. Suggest `--help` for full options

**Example output:**
```
Error: Unknown option '--objekt'
Did you mean '--object'?

Usage: menger [options]
Run with --help for full options list.
```

**Acceptance:** Invalid arguments show helpful error with usage hint.

---

### Task 5.5.6: Tests for polish tasks

**File:** `src/test/scala/menger/CLIHelpTest.scala` (new)

```scala
class CLIHelpTest extends AnyFlatSpec with Matchers:

  "CLI" should "show help on invalid option" in:
    // Capture stderr when parsing invalid args
    val args = Array("--invalid-option")
    // Verify error message includes usage hint

  it should "suggest similar options for typos" in:
    val args = Array("--objekt")  // typo for --object
    // Verify suggestion is shown
```

---

## Files Summary

### New Files

| File | Description |
|------|-------------|
| `optix-jni/src/main/scala/menger/optix/geometry/CubeGeometry.scala` | Cube mesh generation |
| `optix-jni/src/test/scala/menger/optix/geometry/CubeGeometryTest.scala` | Cube geometry tests |
| `optix-jni/src/test/scala/menger/optix/TriangleMeshTest.scala` | Triangle infrastructure tests |
| `optix-jni/src/test/scala/menger/optix/CubeRenderTest.scala` | Opaque cube render tests |
| `optix-jni/src/test/scala/menger/optix/GlassCubeTest.scala` | Glass cube render tests |
| `src/test/scala/menger/CLICubeTest.scala` | CLI integration tests |
| `src/test/scala/menger/CLIHelpTest.scala` | CLI error/help tests (Step 5.5) |
| `optix-jni/src/main/scala/menger/optix/RenderConfig.scala` | Render config case class (Step 5.5) |

### Modified Files

| File | Changes |
|------|---------|
| `optix-jni/src/main/native/include/OptiXData.h` | Add `TriangleMeshData`, `TriangleHitGroupData` |
| `optix-jni/src/main/native/include/OptiXWrapper.h` | Add triangle mesh methods |
| `optix-jni/src/main/native/OptiXWrapper.cpp` | Implement triangle mesh, GAS building |
| `optix-jni/src/main/native/JNIBindings.cpp` | Add triangle mesh JNI bindings |
| `optix-jni/src/main/native/shaders/sphere_combined.cu` | Add `__closesthit__triangle`, `__closesthit__triangle_shadow` |
| `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala` | Add triangle mesh Scala interface |
| `src/main/scala/menger/MengerCLIOptions.scala` | Add `--object`, `--cube-color`, `--cube-ior` |
| `src/main/scala/menger/OptiXResources.scala` | Add cube configuration |

---

## Definition of Done

- [ ] All tasks completed
- [ ] All tests passing (new + existing 897+)
- [ ] Code compiles without warnings
- [ ] Code passes `sbt "scalafix --check"`
- [ ] CHANGELOG.md updated
- [ ] Visual verification: cube renders correctly (screenshot in PR)
- [ ] Performance verified: no significant regression
- [ ] Documentation: architectural decisions recorded

---

## References

- [OptiX Programming Guide - Triangle Input](https://raytracing-docs.nvidia.com/optix7/guide/index.html#acceleration_structures#triangle-build-input)
- [optixTriangle SDK sample](https://github.com/NVIDIA/OptiX_Apps/tree/master/apps/optixTriangle)
- Existing implementation: `sphere_combined.cu` (refraction code lines 687-947)
- Existing implementation: `OptiXWrapper.cpp` (GAS building lines 238-261)
