# Sprint 6: Full Geometry Support

**Created:** 2025-11-22
**Status:** 📋 PLANNED
**Estimated Effort:** 20-30 hours
**Branch:** TBD (create from `main` when starting)
**Prerequisites:** Sprint 5 complete (Triangle Mesh + Cube)

## Overview

Extend the OptiX renderer to support multiple objects with independent transforms, and enable rendering of Menger sponges by exporting `SpongeBySurface` geometry to OptiX triangle meshes.

### Sprint Goal

Render scenes with multiple objects (sphere, cube, sponge) positioned independently, demonstrating the Instance Acceleration Structure (IAS) pipeline.

### Success Criteria

- [ ] Multiple `--object` flags place different objects at specified positions
- [ ] Sponge mesh exports correctly from `SpongeBySurface`
- [ ] Sponge levels 0-3 render without issues (up to ~62,000 triangles)
- [ ] Per-object transforms work (position, rotation, scale)
- [ ] All new code has tests
- [ ] Existing 897+ tests still pass

---

## Architectural Decisions

These decisions affect future sprints. Document rationale to avoid regret later.

### AD-1: Instance Acceleration Structure (IAS) architecture

**Decision:** Refactor from single GAS to IAS containing multiple GAS instances.

**Rationale:**
- IAS is the standard OptiX pattern for multi-object scenes
- Each object type (sphere, cube, sponge) gets its own GAS
- Instances reference GAS handles with per-instance transforms
- Required for per-object position/rotation/scale

**Impact:** Major architectural change to OptiXWrapper. Sprint 5's single GAS becomes one of several under an IAS.

### AD-2: One GAS per geometry type, instances share GAS

**Decision:** Build one GAS per unique geometry (sphere GAS, cube GAS, sponge-level-N GAS), and use instances to place multiple copies.

**Rationale:**
- Efficient memory usage (same geometry shared)
- Fast updates when only transforms change (no GAS rebuild)
- Same sponge level shares geometry even if colors differ

**Impact:** Need geometry registry mapping type → GAS handle.

### AD-3: Per-instance material via instance ID

**Decision:** Store per-instance colors/IOR in arrays indexed by instance ID.

**Rationale:**
- Shaders use `optixGetInstanceId()` to get instance index
- Material arrays in launch parameters, not SBT (simpler)
- Avoids complex SBT offset calculations

**Impact:** `Params` struct grows with material arrays. Max instances capped (start with 64).

### AD-4: Face → Triangle conversion preserves per-face normals

**Decision:** Convert each `Face` quad to 2 triangles, duplicating normals (same as cube in Sprint 5).

**Rationale:**
- Sponge has sharp edges; per-face normals are correct
- Consistent with cube implementation from Sprint 5
- 4 vertices per face → 6 indices (2 triangles)

**Impact:** Sponge level 3 ≈ 10,368 faces × 4 vertices = 41,472 vertices, ~62K triangles.

### AD-5: Sponge geometry cached per level

**Decision:** Cache generated sponge meshes by level to avoid regeneration.

**Rationale:**
- Sponge generation is CPU-bound (recursive subdivision)
- Same level always produces same geometry
- Cache invalidation simple (level is key)

**Impact:** Memory usage for cached meshes (~5MB for level 3).

### AD-6: Transform matrix is 4x3 row-major (OptiX convention)

**Decision:** Use OptiX's native 4x3 row-major transform format.

**Rationale:**
- Matches `OptixInstance.transform[12]` layout directly
- No conversion needed at render time
- Translation in columns 3 (m03, m13, m23)

**Impact:** Scala transform API must produce correct format.

---

## Step 6.1: IAS Infrastructure (6-8 hours)

### Goal
Refactor OptiX pipeline from single GAS to IAS with multiple GAS instances.

---

### Task 6.1.1: Update pipeline compile options for IAS

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Location:** In `getDefaultPipelineCompileOptions()` (around line 26)

**Change from:**
```cpp
options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;
```

**To:**
```cpp
options.traversableGraphFlags =
    OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS |
    OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_LEVEL_INSTANCING;
```

**Why:** This enables both direct GAS traversal (backward compatible) and single-level IAS with instances.

**Test:** Compile `sbt "project optixJni" nativeCompile` - should succeed.

---

### Task 6.1.2: Add instance and scene structures to OptiXData.h

**File:** `optix-jni/src/main/native/include/OptiXData.h`

**Location:** After existing structs (around line 220)

**Add these structures:**

```cpp
// Maximum number of object instances in scene
constexpr unsigned int MAX_INSTANCES = 64;

// Geometry types for SBT offset calculation
enum GeometryType {
    GEOMETRY_TYPE_SPHERE = 0,
    GEOMETRY_TYPE_TRIANGLE = 1,
    GEOMETRY_TYPE_COUNT = 2
};

// Per-instance material data (indexed by instance ID)
struct InstanceMaterial {
    float color[4];        // RGBA
    float ior;             // Index of refraction
    unsigned int padding[3]; // Align to 32 bytes
};

// Scene data passed to shaders
// Add these fields to Params struct:
// OptixTraversableHandle ias_handle;           // Top-level IAS (replaces single gas handle)
// InstanceMaterial* instance_materials;        // Device pointer to material array
// unsigned int num_instances;                  // Active instance count
// bool use_ias;                                // True = use IAS, False = legacy single GAS
```

**Modify existing Params struct:** Add the fields listed above. Keep existing `handle` for backward compatibility but add `ias_handle` and `use_ias` flag.

**Test:** Compile should succeed.

---

### Task 6.1.3: Add multi-object state to OptiXWrapper::Impl

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Location:** In `Impl` struct (around line 45)

**Add these members:**

```cpp
struct OptiXWrapper::Impl {
    // ... existing members ...

    // === Multi-object / IAS support ===
    struct ObjectInstance {
        GeometryType geometry_type;
        OptixTraversableHandle gas_handle;
        float transform[12];           // 4x3 row-major
        float color[4];                // RGBA
        float ior;
        bool active;
    };

    std::vector<ObjectInstance> instances;

    // Instance Acceleration Structure
    OptixTraversableHandle ias_handle;
    CUdeviceptr d_ias_output_buffer;
    CUdeviceptr d_instances_buffer;      // OptixInstance array on GPU
    CUdeviceptr d_instance_materials;    // InstanceMaterial array on GPU
    bool ias_dirty;                      // True if IAS needs rebuild
    bool use_ias;                        // True = multi-object mode

    // GAS registry (geometry type → handle)
    std::map<GeometryType, OptixTraversableHandle> gas_registry;
    std::map<GeometryType, CUdeviceptr> gas_buffer_registry;

    // Sponge mesh cache (level → mesh data)
    struct CachedSpongeMesh {
        CUdeviceptr d_vertices;
        CUdeviceptr d_indices;
        unsigned int num_vertices;
        unsigned int num_triangles;
    };
    std::map<int, CachedSpongeMesh> sponge_cache;
};
```

**Initialize in constructor:**

```cpp
OptiXWrapper::OptiXWrapper() : impl(new Impl()) {
    // ... existing init ...

    impl->ias_handle = 0;
    impl->d_ias_output_buffer = 0;
    impl->d_instances_buffer = 0;
    impl->d_instance_materials = 0;
    impl->ias_dirty = false;
    impl->use_ias = false;
}
```

**Test:** Compile should succeed.

---

### Task 6.1.4: Implement buildIAS method

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Location:** After `buildTriangleMeshGAS()` (around line 400)

**Add this implementation:**

```cpp
void OptiXWrapper::buildIAS() {
    if (impl->instances.empty()) {
        impl->use_ias = false;
        return;
    }

    // Free existing IAS buffers
    if (impl->d_ias_output_buffer) {
        CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_ias_output_buffer)));
        impl->d_ias_output_buffer = 0;
    }
    if (impl->d_instances_buffer) {
        CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_instances_buffer)));
        impl->d_instances_buffer = 0;
    }

    // Count active instances
    std::vector<OptixInstance> optix_instances;
    std::vector<InstanceMaterial> materials;

    for (size_t i = 0; i < impl->instances.size(); ++i) {
        const auto& inst = impl->instances[i];
        if (!inst.active) continue;

        OptixInstance oi = {};

        // Copy transform (4x3 row-major)
        memcpy(oi.transform, inst.transform, 12 * sizeof(float));

        // Instance ID = index into materials array
        oi.instanceId = static_cast<unsigned int>(optix_instances.size());

        // SBT offset based on geometry type and ray type count
        // Ray types: 0 = primary, 1 = shadow
        // SBT layout: [sphere_primary, sphere_shadow, triangle_primary, triangle_shadow]
        oi.sbtOffset = inst.geometry_type * 2;  // 2 ray types per geometry

        oi.visibilityMask = 255;
        oi.flags = OPTIX_INSTANCE_FLAG_NONE;
        oi.traversableHandle = inst.gas_handle;

        optix_instances.push_back(oi);

        // Build material entry
        InstanceMaterial mat = {};
        memcpy(mat.color, inst.color, 4 * sizeof(float));
        mat.ior = inst.ior;
        materials.push_back(mat);
    }

    if (optix_instances.empty()) {
        impl->use_ias = false;
        return;
    }

    // Upload instances to GPU
    size_t instances_size = optix_instances.size() * sizeof(OptixInstance);
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&impl->d_instances_buffer),
        instances_size
    ));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(impl->d_instances_buffer),
        optix_instances.data(),
        instances_size,
        cudaMemcpyHostToDevice
    ));

    // Upload materials to GPU
    if (impl->d_instance_materials) {
        CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_instance_materials)));
    }
    size_t materials_size = materials.size() * sizeof(InstanceMaterial);
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&impl->d_instance_materials),
        materials_size
    ));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(impl->d_instance_materials),
        materials.data(),
        materials_size,
        cudaMemcpyHostToDevice
    ));

    // Build IAS
    OptixBuildInput ias_input = {};
    ias_input.type = OPTIX_BUILD_INPUT_TYPE_INSTANCES;
    ias_input.instanceArray.instances = impl->d_instances_buffer;
    ias_input.instanceArray.numInstances = static_cast<unsigned int>(optix_instances.size());

    OptixAccelBuildOptions accel_options = {};
    accel_options.buildFlags = OPTIX_BUILD_FLAG_ALLOW_UPDATE |
                               OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;
    accel_options.operation = OPTIX_BUILD_OPERATION_BUILD;

    // Query memory requirements
    OptixAccelBufferSizes ias_buffer_sizes;
    OPTIX_CHECK(optixAccelComputeMemoryUsage(
        impl->optix_context.getContext(),
        &accel_options,
        &ias_input,
        1,
        &ias_buffer_sizes
    ));

    // Allocate temp and output buffers
    CUdeviceptr d_temp_buffer;
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&d_temp_buffer),
        ias_buffer_sizes.tempSizeInBytes
    ));
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&impl->d_ias_output_buffer),
        ias_buffer_sizes.outputSizeInBytes
    ));

    // Build IAS
    OPTIX_CHECK(optixAccelBuild(
        impl->optix_context.getContext(),
        0,  // CUDA stream
        &accel_options,
        &ias_input,
        1,
        d_temp_buffer,
        ias_buffer_sizes.tempSizeInBytes,
        impl->d_ias_output_buffer,
        ias_buffer_sizes.outputSizeInBytes,
        &impl->ias_handle,
        nullptr,
        0
    ));

    // Free temp buffer
    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(d_temp_buffer)));

    impl->use_ias = true;
    impl->ias_dirty = false;
}
```

**Add declaration to OptiXWrapper.h (private section):**
```cpp
private:
    void buildIAS();
```

**Test:** Compile should succeed.

---

### Task 6.1.5: Add object instance management API

**File:** `optix-jni/src/main/native/include/OptiXWrapper.h`

**Location:** In public section

**Add these method declarations:**

```cpp
// Multi-object API
int addSphereInstance(
    float x, float y, float z,           // Position
    float radius,                         // Sphere radius
    float r, float g, float b, float a,   // Color
    float ior                             // Index of refraction
);

int addCubeInstance(
    float x, float y, float z,           // Position
    float size,                          // Cube size
    float r, float g, float b, float a,   // Color
    float ior                             // Index of refraction
);

int addSpongeInstance(
    float x, float y, float z,           // Position
    float size,                          // Size
    int level,                           // Sponge recursion level (0-3)
    float r, float g, float b, float a,   // Color
    float ior                             // Index of refraction
);

void setInstanceTransform(
    int instance_id,
    const float* transform              // 4x3 row-major matrix
);

void setInstanceColor(int instance_id, float r, float g, float b, float a);
void setInstanceIOR(int instance_id, float ior);
void removeInstance(int instance_id);
void clearAllInstances();
int getInstanceCount() const;
```

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Implement addSphereInstance:**

```cpp
int OptiXWrapper::addSphereInstance(
    float x, float y, float z,
    float radius,
    float r, float g, float b, float a,
    float ior
) {
    // Ensure sphere GAS exists
    if (impl->gas_registry.find(GEOMETRY_TYPE_SPHERE) == impl->gas_registry.end()) {
        // Build sphere GAS (reuse existing buildGeometryAccelerationStructure logic)
        buildSphereGAS();
    }

    // Create instance
    Impl::ObjectInstance inst = {};
    inst.geometry_type = GEOMETRY_TYPE_SPHERE;
    inst.gas_handle = impl->gas_registry[GEOMETRY_TYPE_SPHERE];

    // Build transform: translate to (x,y,z), scale by radius
    // Identity with translation and scale
    inst.transform[0] = radius;  inst.transform[1] = 0;      inst.transform[2] = 0;      inst.transform[3] = x;
    inst.transform[4] = 0;       inst.transform[5] = radius; inst.transform[6] = 0;      inst.transform[7] = y;
    inst.transform[8] = 0;       inst.transform[9] = 0;      inst.transform[10] = radius; inst.transform[11] = z;

    inst.color[0] = r; inst.color[1] = g; inst.color[2] = b; inst.color[3] = a;
    inst.ior = ior;
    inst.active = true;

    int id = static_cast<int>(impl->instances.size());
    impl->instances.push_back(inst);
    impl->ias_dirty = true;

    return id;
}
```

**Implement addCubeInstance (similar pattern):**

```cpp
int OptiXWrapper::addCubeInstance(
    float x, float y, float z,
    float size,
    float r, float g, float b, float a,
    float ior
) {
    // Ensure cube GAS exists (uses triangle mesh from Sprint 5)
    if (impl->gas_registry.find(GEOMETRY_TYPE_TRIANGLE) == impl->gas_registry.end()) {
        buildCubeGAS();  // Build unit cube GAS
    }

    Impl::ObjectInstance inst = {};
    inst.geometry_type = GEOMETRY_TYPE_TRIANGLE;
    inst.gas_handle = impl->gas_registry[GEOMETRY_TYPE_TRIANGLE];

    // Transform: translate and scale
    float half = size / 2.0f;
    inst.transform[0] = size;  inst.transform[1] = 0;     inst.transform[2] = 0;     inst.transform[3] = x;
    inst.transform[4] = 0;     inst.transform[5] = size;  inst.transform[6] = 0;     inst.transform[7] = y;
    inst.transform[8] = 0;     inst.transform[9] = 0;     inst.transform[10] = size; inst.transform[11] = z;

    inst.color[0] = r; inst.color[1] = g; inst.color[2] = b; inst.color[3] = a;
    inst.ior = ior;
    inst.active = true;

    int id = static_cast<int>(impl->instances.size());
    impl->instances.push_back(inst);
    impl->ias_dirty = true;

    return id;
}
```

**Test:** Compile should succeed.

---

### Task 6.1.6: Update render to use IAS when available

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Location:** In `render()` method (around line 580)

**Modify launch parameter setup:**

```cpp
// In render() before optixLaunch:

// Rebuild IAS if needed
if (impl->ias_dirty && !impl->instances.empty()) {
    buildIAS();
}

Params params;
// ... existing setup ...

// Choose traversable handle based on mode
if (impl->use_ias && impl->ias_handle != 0) {
    params.handle = impl->ias_handle;  // Use IAS
    params.instance_materials = reinterpret_cast<InstanceMaterial*>(impl->d_instance_materials);
    params.num_instances = static_cast<unsigned int>(impl->instances.size());
    params.use_ias = true;
} else {
    params.handle = impl->gas_handle;  // Legacy single GAS
    params.use_ias = false;
    params.num_instances = 0;
}
```

**Test:** Existing tests should still pass (backward compatible).

---

### Task 6.1.7: Update shaders for instance materials

**File:** `optix-jni/src/main/native/shaders/sphere_combined.cu`

**Location:** In `__closesthit__ch` and `__closesthit__triangle`

**Add instance material lookup at the start of each closest-hit:**

```cuda
// Get instance ID and look up material
unsigned int instance_id = optixGetInstanceId();
float4 object_color;
float object_ior;

if (params.use_ias && params.instance_materials != nullptr) {
    // Multi-object mode: get material from instance array
    const InstanceMaterial& mat = params.instance_materials[instance_id];
    object_color = make_float4(mat.color[0], mat.color[1], mat.color[2], mat.color[3]);
    object_ior = mat.ior;
} else {
    // Legacy mode: use global sphere/mesh parameters
    object_color = make_float4(
        params.sphere_color[0], params.sphere_color[1],
        params.sphere_color[2], params.sphere_color[3]
    );
    object_ior = params.sphere_ior;
}

// Use object_color and object_ior throughout the shader instead of params.sphere_*
```

**Apply this pattern to:**
- `__closesthit__ch` (sphere)
- `__closesthit__triangle` (cube/sponge)

**Test:** Compile `sbt "project optixJni" nativeCompile`

---

### Task 6.1.8: Unit tests for IAS infrastructure

**File:** `optix-jni/src/test/scala/menger/optix/MultiObjectTest.scala` (new file)

```scala
package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import menger.common.Color

class MultiObjectTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  private var renderer: OptiXRenderer = _
  private var initialized: Boolean = false

  override def beforeAll(): Unit =
    if OptiXRenderer.isLibraryLoaded then
      renderer = new OptiXRenderer()
      initialized = renderer.initialize()
      if initialized then
        renderer.setCamera(
          Array(0.0f, 3.0f, 8.0f),
          Array(0.0f, 0.0f, 0.0f),
          Array(0.0f, 1.0f, 0.0f),
          45.0f
        )
        renderer.setLight(Array(-1.0f, 1.0f, 1.0f), 1.0f)
        renderer.setPlane(1, true, -2.0f)

  override def afterAll(): Unit =
    if initialized then renderer.dispose()

  "addSphereInstance" should "create a sphere instance" in:
    assume(initialized, "OptiX not available")

    renderer.clearAllInstances()
    val id = renderer.addSphereInstance(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.0f)

    id shouldBe 0
    renderer.getInstanceCount() shouldBe 1

  "addCubeInstance" should "create a cube instance" in:
    assume(initialized, "OptiX not available")

    renderer.clearAllInstances()
    val id = renderer.addCubeInstance(2.0f, 0.0f, 0.0f, 1.5f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f)

    id shouldBe 0
    renderer.getInstanceCount() shouldBe 1

  "Multiple instances" should "render without errors" in:
    assume(initialized, "OptiX not available")

    renderer.clearAllInstances()
    renderer.addSphereInstance(-2.0f, 0.0f, 0.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 1.5f)  // Red sphere
    renderer.addCubeInstance(2.0f, 0.0f, 0.0f, 1.5f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f)    // Blue cube

    renderer.getInstanceCount() shouldBe 2

    val result = renderer.renderWithStats(800, 600)
    result.image should not be empty
    result.totalRays should be > 0L

  "clearAllInstances" should "remove all instances" in:
    assume(initialized, "OptiX not available")

    renderer.addSphereInstance(0.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f)
    renderer.addCubeInstance(2.0f, 0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f, 1.0f)

    renderer.clearAllInstances()

    renderer.getInstanceCount() shouldBe 0
```

**Run:** `sbt "testOnly menger.optix.MultiObjectTest"`

---

## Step 6.2: Sponge Mesh Export (5-7 hours)

### Goal
Export `SpongeBySurface.faces` to OptiX triangle buffers for rendering.

---

### Task 6.2.1: Create SpongeGeometry.scala

**File:** `optix-jni/src/main/scala/menger/optix/geometry/SpongeGeometry.scala` (new file)

```scala
package menger.optix.geometry

import menger.objects.Face
import menger.objects.Direction
import menger.objects.SpongeBySurface
import menger.ProfilingConfig

object SpongeGeometry:

  case class MeshData(
      vertices: Array[Float],  // Interleaved pos + normal (6 floats per vertex)
      indices: Array[Int]
  ):
    def numVertices: Int = vertices.length / 6
    def numTriangles: Int = indices.length / 3

  def generate(level: Int, size: Float = 1.0f)(using ProfilingConfig): MeshData =
    // Create SpongeBySurface to get faces
    given ProfilingConfig = ProfilingConfig(enabled = false)
    val sponge = new SpongeBySurface(level = level.toFloat)

    // Get all faces from all 6 cube sides
    val allFaces = collectAllFaces(sponge.faces)

    // Convert faces to triangles
    facesToMesh(allFaces, size)

  private def collectAllFaces(baseFaces: Seq[Face]): Seq[Face] =
    // SpongeBySurface generates faces for one side (+Z)
    // We need all 6 sides, rotated appropriately
    // For simplicity, we'll generate 6 separate face sets
    // and transform them

    val rotations = Seq(
      // (axis to rotate around, angle in 90° increments)
      (Direction.Y, 0),   // Front (+Z) - no rotation
      (Direction.Y, 2),   // Back (-Z) - 180° around Y
      (Direction.Y, 1),   // Right (+X) - 90° around Y
      (Direction.Y, 3),   // Left (-X) - 270° around Y
      (Direction.X, 3),   // Top (+Y) - -90° around X
      (Direction.X, 1)    // Bottom (-Y) - 90° around X
    )

    // For now, just use the base faces (single side)
    // Full implementation needs face transformation
    baseFaces

  private def facesToMesh(faces: Seq[Face], size: Float): MeshData =
    val vertexList = scala.collection.mutable.ArrayBuffer[Float]()
    val indexList = scala.collection.mutable.ArrayBuffer[Int]()

    var vertexIndex = 0

    for face <- faces do
      // Get face center and dimensions
      val cx = face.xCen * size
      val cy = face.yCen * size
      val cz = face.zCen * size
      val halfSize = face.scale * size / 2.0f

      // Get normal from Direction
      val (nx, ny, nz) = face.normal match
        case Direction.X    => (1.0f, 0.0f, 0.0f)
        case Direction.Y    => (0.0f, 1.0f, 0.0f)
        case Direction.Z    => (0.0f, 0.0f, 1.0f)
        case Direction.negX => (-1.0f, 0.0f, 0.0f)
        case Direction.negY => (0.0f, -1.0f, 0.0f)
        case Direction.negZ => (0.0f, 0.0f, -1.0f)

      // Calculate 4 corners based on face normal direction
      // For Z-facing: corners in XY plane
      // For X-facing: corners in YZ plane
      // For Y-facing: corners in XZ plane
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

      // Add 4 vertices with normals
      for (px, py, pz) <- corners do
        vertexList ++= Array(px, py, pz, nx, ny, nz)

      // Add 2 triangles (CCW winding when viewed from normal direction)
      val base = vertexIndex
      // Check normal sign to ensure correct winding
      val sign = nx + ny + nz
      if sign > 0 then
        // Positive normal: CCW
        indexList ++= Array(base, base + 1, base + 2)
        indexList ++= Array(base, base + 2, base + 3)
      else
        // Negative normal: reverse winding
        indexList ++= Array(base, base + 2, base + 1)
        indexList ++= Array(base, base + 3, base + 2)

      vertexIndex += 4

    MeshData(vertexList.toArray, indexList.toArray)

  def estimateFaceCount(level: Int): Int =
    // Each face subdivides into 8 same-direction + 4 rotated = 12 per face
    // 6 initial faces × 12^level
    val facesPerSide = math.pow(12, level).toInt
    6 * facesPerSide

  def estimateTriangleCount(level: Int): Int =
    // 2 triangles per face
    estimateFaceCount(level) * 2
```

**Note:** This is a simplified implementation. The full implementation needs proper face transformation for all 6 sides using the sponge's rotation logic.

**Test:** Add unit test for geometry generation.

---

### Task 6.2.2: Create SpongeGeometryTest.scala

**File:** `optix-jni/src/test/scala/menger/optix/geometry/SpongeGeometryTest.scala` (new file)

```scala
package menger.optix.geometry

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import menger.ProfilingConfig

class SpongeGeometryTest extends AnyFlatSpec with Matchers:

  given ProfilingConfig = ProfilingConfig(enabled = false)

  "SpongeGeometry.generate" should "produce triangles for level 0" in:
    val mesh = SpongeGeometry.generate(level = 0)

    // Level 0 = single cube = 6 faces = 12 triangles
    mesh.numTriangles should be >= 6    // At least one side
    mesh.numVertices should be >= 4     // At least one face

  it should "produce more triangles for level 1" in:
    val level0 = SpongeGeometry.generate(level = 0)
    val level1 = SpongeGeometry.generate(level = 1)

    level1.numTriangles should be > level0.numTriangles

  it should "produce valid vertex data" in:
    val mesh = SpongeGeometry.generate(level = 0)

    // Vertices should be divisible by 6 (pos + normal)
    mesh.vertices.length % 6 shouldBe 0

    // Indices should be divisible by 3 (triangles)
    mesh.indices.length % 3 shouldBe 0

  it should "have all indices in valid range" in:
    val mesh = SpongeGeometry.generate(level = 1)

    all(mesh.indices) should be >= 0
    all(mesh.indices) should be < mesh.numVertices

  it should "have normalized normals" in:
    val mesh = SpongeGeometry.generate(level = 0)

    for i <- 0 until mesh.numVertices do
      val nx = mesh.vertices(i * 6 + 3)
      val ny = mesh.vertices(i * 6 + 4)
      val nz = mesh.vertices(i * 6 + 5)
      val length = math.sqrt(nx * nx + ny * ny + nz * nz)
      length shouldBe 1.0f +- 0.001f

  "estimateTriangleCount" should "grow exponentially with level" in:
    val t0 = SpongeGeometry.estimateTriangleCount(0)
    val t1 = SpongeGeometry.estimateTriangleCount(1)
    val t2 = SpongeGeometry.estimateTriangleCount(2)

    t1 shouldBe t0 * 12  // 12x growth per level
    t2 shouldBe t1 * 12
```

**Run:** `sbt "testOnly menger.optix.geometry.SpongeGeometryTest"`

---

### Task 6.2.3: Implement addSpongeInstance

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Location:** After `addCubeInstance()`

```cpp
int OptiXWrapper::addSpongeInstance(
    float x, float y, float z,
    float size,
    int level,
    float r, float g, float b, float a,
    float ior
) {
    // Check sponge cache
    auto cache_it = impl->sponge_cache.find(level);
    OptixTraversableHandle sponge_gas;

    if (cache_it != impl->sponge_cache.end()) {
        // Use cached sponge GAS
        sponge_gas = buildSpongeGASFromCache(cache_it->second);
    } else {
        // Need to receive mesh from Scala side first
        // This method expects setSpongeMesh() was called before
        if (impl->triangle_mesh.has_mesh && impl->triangle_mesh.gas_handle != 0) {
            sponge_gas = impl->triangle_mesh.gas_handle;

            // Cache for reuse
            impl->sponge_cache[level] = {
                impl->triangle_mesh.d_vertices,
                impl->triangle_mesh.d_indices,
                impl->triangle_mesh.num_vertices,
                impl->triangle_mesh.num_triangles
            };
        } else {
            // Error: mesh not set
            return -1;
        }
    }

    // Create instance
    Impl::ObjectInstance inst = {};
    inst.geometry_type = GEOMETRY_TYPE_TRIANGLE;
    inst.gas_handle = sponge_gas;

    // Transform: translate and scale
    inst.transform[0] = size;  inst.transform[1] = 0;     inst.transform[2] = 0;     inst.transform[3] = x;
    inst.transform[4] = 0;     inst.transform[5] = size;  inst.transform[6] = 0;     inst.transform[7] = y;
    inst.transform[8] = 0;     inst.transform[9] = 0;     inst.transform[10] = size; inst.transform[11] = z;

    inst.color[0] = r; inst.color[1] = g; inst.color[2] = b; inst.color[3] = a;
    inst.ior = ior;
    inst.active = true;

    int id = static_cast<int>(impl->instances.size());
    impl->instances.push_back(inst);
    impl->ias_dirty = true;

    return id;
}
```

**Note:** The actual sponge mesh data comes from Scala via `setTriangleMesh()`. The C++ side just manages the GPU buffers.

---

### Task 6.2.4: Add JNI bindings for sponge

**File:** `optix-jni/src/main/native/JNIBindings.cpp`

**Location:** After existing JNI methods

```cpp
JNIEXPORT jint JNICALL Java_menger_optix_OptiXRenderer_addSpongeInstanceNative(
    JNIEnv* env, jobject obj,
    jfloat x, jfloat y, jfloat z,
    jfloat size,
    jint level,
    jfloat r, jfloat g, jfloat b, jfloat a,
    jfloat ior
) {
    OptiXWrapper* wrapper = getWrapper(env, obj);
    if (!wrapper) return -1;

    return wrapper->addSpongeInstance(x, y, z, size, level, r, g, b, a, ior);
}
```

---

### Task 6.2.5: Add Scala interface for sponge instance

**File:** `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala`

**Location:** After existing instance methods

```scala
// Sponge instance support
@native private def addSpongeInstanceNative(
    x: Float, y: Float, z: Float,
    size: Float,
    level: Int,
    r: Float, g: Float, b: Float, a: Float,
    ior: Float
): Int

def addSpongeInstance(
    x: Float, y: Float, z: Float,
    size: Float,
    level: Int,
    color: Color,
    ior: Float = 1.0f
)(using ProfilingConfig): Int =
    // Generate sponge mesh and upload
    val mesh = geometry.SpongeGeometry.generate(level, 1.0f)  // Unit size, scaled by transform
    setTriangleMesh(mesh.vertices, mesh.indices)

    addSpongeInstanceNative(x, y, z, size, level, color.r, color.g, color.b, color.a, ior)
```

---

### Task 6.2.6: Sponge render tests

**File:** `optix-jni/src/test/scala/menger/optix/SpongeRenderTest.scala` (new file)

```scala
package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import menger.common.Color
import menger.ProfilingConfig

class SpongeRenderTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  given ProfilingConfig = ProfilingConfig(enabled = false)

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

  override def afterAll(): Unit =
    if initialized then renderer.dispose()

  "Sponge level 0" should "render without errors" in:
    assume(initialized, "OptiX not available")

    renderer.clearAllInstances()
    renderer.addSpongeInstance(0.0f, 0.0f, 0.0f, 2.0f, 0, Color.fromRGB(0.8f, 0.8f, 0.8f))

    val result = renderer.renderWithStats(800, 600)

    result.image should not be empty
    result.totalRays should be > 0L

  "Sponge level 1" should "render without errors" in:
    assume(initialized, "OptiX not available")

    renderer.clearAllInstances()
    renderer.addSpongeInstance(0.0f, 0.0f, 0.0f, 2.0f, 1, Color.fromRGB(0.6f, 0.6f, 0.8f))

    val result = renderer.renderWithStats(800, 600)

    result.image should not be empty

  "Sponge level 2" should "render without errors" in:
    assume(initialized, "OptiX not available")

    renderer.clearAllInstances()
    renderer.addSpongeInstance(0.0f, 0.0f, 0.0f, 2.0f, 2, Color.fromRGB(0.5f, 0.7f, 0.5f))

    val result = renderer.renderWithStats(800, 600)

    result.image should not be empty

  "Sponge level 3" should "render without errors (performance test)" in:
    assume(initialized, "OptiX not available")

    renderer.clearAllInstances()

    val startTime = System.currentTimeMillis()
    renderer.addSpongeInstance(0.0f, 0.0f, 0.0f, 2.0f, 3, Color.fromRGB(0.7f, 0.5f, 0.5f))
    val result = renderer.renderWithStats(800, 600)
    val elapsed = System.currentTimeMillis() - startTime

    result.image should not be empty

    // Performance: should complete within reasonable time
    elapsed should be < 30000L  // 30 seconds max
```

**Run:** `sbt "testOnly menger.optix.SpongeRenderTest"`

---

## Step 6.3: CLI Integration (4-5 hours)

### Goal
Multiple `--object` flags with position/size parameters.

---

### Task 6.3.1: Extend --object option for multiple objects

**File:** `src/main/scala/menger/MengerCLIOptions.scala`

**Modify the object option to accept multiple values with position:**

```scala
// Object definitions (can be repeated)
// Format: type:x,y,z:size:color:ior
// Examples:
//   --object sphere:0,0,0:1.0:#FF0000:1.5
//   --object cube:2,0,0:1.5:#0000FF:1.0
//   --object sponge:−2,0,0:2.0:2:#00FF00:1.0  (level after size)
val objects: ScallopOption[List[String]] = opt[List[String]](
  name = "object",
  required = false,
  default = Some(List("sphere:0,0,0:1.0")),
  descr = "Objects to render (repeatable). Format: type:x,y,z:size[:level][:color][:ior]"
)

// Validation
validateOpt(objects, optix) { (objs, ox) =>
  if objs.length > 1 && !ox.getOrElse(false) then
    Left("Multiple --object flags require --optix")
  else Right(())
}
```

---

### Task 6.3.2: Parse object specifications

**File:** `src/main/scala/menger/ObjectSpec.scala` (new file)

```scala
package menger

import menger.common.Color

case class ObjectSpec(
    objectType: String,
    x: Float,
    y: Float,
    z: Float,
    size: Float,
    level: Option[Int],     // For sponge
    color: Color,
    ior: Float
)

object ObjectSpec:

  def parse(spec: String): Either[String, ObjectSpec] =
    val parts = spec.split(":")
    if parts.length < 3 then
      return Left(s"Invalid object spec: $spec. Expected type:x,y,z:size[:level][:color][:ior]")

    val objectType = parts(0).toLowerCase

    val positionParts = parts(1).split(",")
    if positionParts.length != 3 then
      return Left(s"Invalid position in: $spec. Expected x,y,z")

    val position = positionParts.map(_.toFloatOption).toSeq
    if position.exists(_.isEmpty) then
      return Left(s"Invalid position numbers in: $spec")

    val Array(x, y, z) = position.map(_.get).toArray

    val size = parts(2).toFloatOption.getOrElse(
      return Left(s"Invalid size in: $spec")
    )

    // Parse remaining optional parts based on object type
    objectType match
      case "sphere" | "cube" =>
        val color = if parts.length > 3 then parseColor(parts(3)) else Color.WHITE
        val ior = if parts.length > 4 then parts(4).toFloatOption.getOrElse(1.0f) else 1.0f
        Right(ObjectSpec(objectType, x, y, z, size, None, color, ior))

      case "sponge" =>
        val level = if parts.length > 3 then parts(3).toIntOption else Some(2)
        val color = if parts.length > 4 then parseColor(parts(4)) else Color.WHITE
        val ior = if parts.length > 5 then parts(5).toFloatOption.getOrElse(1.0f) else 1.0f
        Right(ObjectSpec(objectType, x, y, z, size, level, color, ior))

      case other =>
        Left(s"Unknown object type: $other. Valid types: sphere, cube, sponge")

  private def parseColor(s: String): Color =
    if s.startsWith("#") then Color.fromHex(s)
    else Color.WHITE
```

---

### Task 6.3.3: Wire CLI to renderer

**File:** `src/main/scala/menger/OptiXResources.scala`

**Location:** In configuration section

```scala
def configureObjects(specs: List[String]): Unit =
  renderer.clearAllInstances()

  for spec <- specs do
    ObjectSpec.parse(spec) match
      case Right(obj) =>
        obj.objectType match
          case "sphere" =>
            renderer.addSphereInstance(
              obj.x, obj.y, obj.z,
              obj.size,
              obj.color.r, obj.color.g, obj.color.b, obj.color.a,
              obj.ior
            )
          case "cube" =>
            renderer.addCubeInstance(
              obj.x, obj.y, obj.z,
              obj.size,
              obj.color.r, obj.color.g, obj.color.b, obj.color.a,
              obj.ior
            )
          case "sponge" =>
            renderer.addSpongeInstance(
              obj.x, obj.y, obj.z,
              obj.size,
              obj.level.getOrElse(2),
              obj.color,
              obj.ior
            )
          case _ =>
            logger.warn(s"Unknown object type: ${obj.objectType}")

      case Left(error) =>
        logger.error(s"Failed to parse object: $error")
```

---

### Task 6.3.4: CLI integration tests

**File:** `src/test/scala/menger/CLIMultiObjectTest.scala` (new file)

```scala
package menger

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CLIMultiObjectTest extends AnyFlatSpec with Matchers:

  "ObjectSpec.parse" should "parse sphere" in:
    val result = ObjectSpec.parse("sphere:0,0,0:1.0")
    result shouldBe a[Right[_, _]]
    result.toOption.get.objectType shouldBe "sphere"
    result.toOption.get.size shouldBe 1.0f

  it should "parse cube with color" in:
    val result = ObjectSpec.parse("cube:2,0,0:1.5:#FF0000")
    result shouldBe a[Right[_, _]]
    result.toOption.get.objectType shouldBe "cube"
    result.toOption.get.x shouldBe 2.0f
    result.toOption.get.color.r shouldBe 1.0f +- 0.01f

  it should "parse sponge with level" in:
    val result = ObjectSpec.parse("sponge:-2,0,0:2.0:3")
    result shouldBe a[Right[_, _]]
    result.toOption.get.objectType shouldBe "sponge"
    result.toOption.get.level shouldBe Some(3)

  it should "reject invalid format" in:
    val result = ObjectSpec.parse("invalid")
    result shouldBe a[Left[_, _]]

  it should "reject unknown type" in:
    val result = ObjectSpec.parse("pyramid:0,0,0:1.0")
    result shouldBe a[Left[_, _]]

  "CLI parser" should "accept multiple --object flags" in:
    val args = Array(
      "--optix",
      "--object", "sphere:0,0,0:1.0",
      "--object", "cube:2,0,0:1.5"
    )
    val options = new MengerCLIOptions(args)

    options.objects().length shouldBe 2
```

**Run:** `sbt "testOnly menger.CLIMultiObjectTest"`

---

## Step 6.4: Performance Optimization (3-5 hours)

### Goal
Handle large sponge meshes efficiently.

---

### Task 6.4.1: BVH build optimization flags

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**In `buildTriangleMeshGAS()` and `buildIAS()`:**

```cpp
OptixAccelBuildOptions accel_options = {};
accel_options.buildFlags =
    OPTIX_BUILD_FLAG_ALLOW_COMPACTION |     // Reduce memory
    OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;     // Optimize for tracing speed

// For very large meshes (sponge level 3+), consider:
if (num_triangles > 50000) {
    accel_options.buildFlags |= OPTIX_BUILD_FLAG_ALLOW_RANDOM_VERTEX_ACCESS;
    // Enables more efficient memory access patterns for large meshes
}
```

---

### Task 6.4.2: Add sponge level limit validation

**File:** `optix-jni/src/main/scala/menger/optix/geometry/SpongeGeometry.scala`

```scala
def generate(level: Int, size: Float = 1.0f)(using ProfilingConfig): MeshData =
    require(level >= 0, "Sponge level must be non-negative")
    require(level <= 4, s"Sponge level $level too high. Max supported: 4 (${estimateTriangleCount(4)} triangles)")

    // Warn for high triangle counts
    val triangleCount = estimateTriangleCount(level)
    if triangleCount > 100000 then
      System.err.println(s"Warning: Sponge level $level generates $triangleCount triangles. This may be slow.")

    // ... rest of implementation
```

---

### Task 6.4.3: Performance benchmarks

**File:** `optix-jni/src/test/scala/menger/optix/SpongePerformanceTest.scala` (new file)

```scala
package menger.optix

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import menger.common.Color
import menger.ProfilingConfig

class SpongePerformanceTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll:

  given ProfilingConfig = ProfilingConfig(enabled = false)

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

  "Sponge mesh generation" should "complete within time limits" in:
    assume(initialized, "OptiX not available")

    val levels = Seq(0, 1, 2, 3)
    val maxTimes = Map(0 -> 100L, 1 -> 500L, 2 -> 2000L, 3 -> 10000L)  // ms

    for level <- levels do
      val start = System.currentTimeMillis()
      val mesh = geometry.SpongeGeometry.generate(level)
      val elapsed = System.currentTimeMillis() - start

      println(s"Level $level: ${mesh.numTriangles} triangles in ${elapsed}ms")

      elapsed should be < maxTimes(level)

  "Sponge rendering" should "complete within time limits" in:
    assume(initialized, "OptiX not available")

    val levels = Seq(0, 1, 2)
    val maxRenderTimes = Map(0 -> 500L, 1 -> 1000L, 2 -> 3000L)  // ms

    for level <- levels do
      renderer.clearAllInstances()
      renderer.addSpongeInstance(0f, 0f, 0f, 2f, level, Color.WHITE)

      val start = System.currentTimeMillis()
      val result = renderer.renderWithStats(800, 600)
      val elapsed = System.currentTimeMillis() - start

      println(s"Level $level render: ${elapsed}ms, ${result.totalRays} rays")

      elapsed should be < maxRenderTimes(level)
```

**Run:** `sbt "testOnly menger.optix.SpongePerformanceTest"`

---

## Files Summary

### New Files

| File | Description |
|------|-------------|
| `optix-jni/src/main/scala/menger/optix/geometry/SpongeGeometry.scala` | Sponge mesh generation |
| `optix-jni/src/test/scala/menger/optix/geometry/SpongeGeometryTest.scala` | Sponge geometry tests |
| `optix-jni/src/test/scala/menger/optix/MultiObjectTest.scala` | Multi-object IAS tests |
| `optix-jni/src/test/scala/menger/optix/SpongeRenderTest.scala` | Sponge render tests |
| `optix-jni/src/test/scala/menger/optix/SpongePerformanceTest.scala` | Performance benchmarks |
| `src/main/scala/menger/ObjectSpec.scala` | Object specification parser |
| `src/test/scala/menger/CLIMultiObjectTest.scala` | CLI multi-object tests |

### Modified Files

| File | Changes |
|------|---------|
| `optix-jni/src/main/native/include/OptiXData.h` | Add `MAX_INSTANCES`, `GeometryType`, `InstanceMaterial`, IAS params |
| `optix-jni/src/main/native/include/OptiXWrapper.h` | Add multi-object API methods |
| `optix-jni/src/main/native/OptiXWrapper.cpp` | Implement IAS, instance management, sponge support |
| `optix-jni/src/main/native/JNIBindings.cpp` | Add instance management JNI bindings |
| `optix-jni/src/main/native/shaders/sphere_combined.cu` | Add instance material lookup in closest-hit shaders |
| `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala` | Add instance management Scala interface |
| `src/main/scala/menger/MengerCLIOptions.scala` | Extend `--object` for multiple objects |
| `src/main/scala/menger/OptiXResources.scala` | Add `configureObjects()` |

---

## Definition of Done

- [ ] All tasks completed
- [ ] All tests passing (new + existing 897+)
- [ ] Code compiles without warnings
- [ ] Code passes `sbt "scalafix --check"`
- [ ] CHANGELOG.md updated
- [ ] Multiple objects render correctly with different positions
- [ ] Sponge levels 0-3 render without errors
- [ ] Performance acceptable (sponge level 2 < 3s render time at 800x600)
- [ ] Backward compatible: existing single-object scenes work

---

## References

- [OptiX Programming Guide - Instances](https://raytracing-docs.nvidia.com/optix7/guide/index.html#acceleration_structures#instances)
- [optixMotionBlur SDK sample](https://github.com/NVIDIA/OptiX_Apps/tree/master/apps/optixMotionBlur) - IAS example
- Sprint 5 plan: `SPRINT_5_PLAN.md` (triangle mesh foundation)
- Existing implementation: `SpongeBySurface.scala` (face generation)
- Existing implementation: `Face.scala` (face data structure)
