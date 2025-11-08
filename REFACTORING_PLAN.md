# OptiX JNI Refactoring Plan

**Goal:** Improve code quality, maintainability, and architecture of OptiX JNI bindings.

**Estimated Total Time:** 12-16 hours

---

## Architecture Overview

Current single-layer architecture:
```
Scala OptiXRenderer → JNI → C++ OptiXWrapper (mixed low/high level)
```

Target two-layer architecture:
```
Scala OptiXRenderer → JNI → C++ OptiXSceneRenderer (high-level)
                                        ↓
                             C++ OptiXContext (low-level)
```

**Benefits:**
- Clear separation of concerns (OptiX API vs scene management)
- Better testability (can mock low-level layer)
- Easier to add alternative backends later
- More flexible for power users
- Aligns with OptiX's own design philosophy

---

## Phase 1: Extract Low-Level Layer (OptiXContext)

**Goal:** Create pure OptiX wrapper with minimal state.

**Time Estimate:** 4-6 hours

### 1.1 Create OptiXContext Header
**File:** `optix-jni/src/main/native/include/OptiXContext.h`

**Interface:**
```cpp
class OptiXContext {
public:
    // Context lifecycle
    bool initialize();
    void destroy();
    OptixDeviceContext getContext() const;

    // Module management
    OptixModule createModuleFromPTX(
        const std::string& ptx_content,
        const OptixModuleCompileOptions& module_options,
        const OptixPipelineCompileOptions& pipeline_options
    );
    void destroyModule(OptixModule module);

    // Program group management
    OptixProgramGroup createRaygenProgramGroup(OptixModule, const char* entry);
    OptixProgramGroup createMissProgramGroup(OptixModule, const char* entry);
    OptixProgramGroup createHitgroupProgramGroup(
        OptixModule module_ch, const char* entry_ch,
        OptixModule module_is, const char* entry_is
    );
    void destroyProgramGroup(OptixProgramGroup);

    // Pipeline management
    OptixPipeline createPipeline(
        const OptixPipelineCompileOptions& pipeline_options,
        const OptixPipelineLinkOptions& link_options,
        OptixProgramGroup* program_groups,
        unsigned int num_program_groups
    );
    void destroyPipeline(OptixPipeline);

    // Geometry acceleration structure (GAS)
    struct GASBuildResult {
        CUdeviceptr gas_buffer;
        OptixTraversableHandle handle;
    };
    GASBuildResult buildCustomPrimitiveGAS(
        const OptixAabb& aabb,
        const OptixAccelBuildOptions& build_options
    );
    void destroyGAS(CUdeviceptr gas_buffer);

    // Shader binding table (SBT) helpers
    template<typename T>
    CUdeviceptr createSBTRecord(OptixProgramGroup, const T& data);
    void freeSBTRecord(CUdeviceptr record);

    // Launch
    void launch(
        OptixPipeline pipeline,
        const OptixShaderBindingTable& sbt,
        CUdeviceptr params_buffer,
        unsigned int width,
        unsigned int height
    );

private:
    OptixDeviceContext context_;
    bool initialized_;
};
```

### 1.2 Create OptiXContext Implementation
**File:** `optix-jni/src/main/native/OptiXContext.cpp`

**Extract from OptiXWrapper.cpp:**
- Context initialization (lines ~138-161)
- Module loading (lines ~330-366)
- Program group creation (lines ~368-435)
- Pipeline creation (lines ~437-498)
- GAS building (lines ~246-327)
- SBT record setup pattern (extract template from lines ~500-590)
- Launch execution (extract from render() method)

**Key changes:**
- Move error checking macros (OPTIX_CHECK, CUDA_CHECK) to OptiXContext.cpp
- Make methods stateless - take explicit parameters
- No scene state storage (sphere position, camera, etc.)
- Clean separation: only OptiX API calls

### 1.3 Update CMakeLists.txt
**File:** `optix-jni/src/main/native/CMakeLists.txt`

Add `OptiXContext.cpp` to sources:
```cmake
set(SOURCES
    JNIBindings.cpp
    OptiXWrapper.cpp
    OptiXContext.cpp  # NEW
    standalone_test.cpp
)
```

---

## Phase 2: Refactor High-Level Layer (OptiXSceneRenderer)

**Goal:** Use OptiXContext for all low-level operations, focus on scene management.

**Time Estimate:** 3-4 hours

### 2.1 Rename OptiXWrapper → OptiXSceneRenderer
**Files:**
- `optix-jni/src/main/native/include/OptiXWrapper.h` → `OptiXSceneRenderer.h`
- `optix-jni/src/main/native/OptiXWrapper.cpp` → `OptiXSceneRenderer.cpp`

**Update references in:**
- `JNIBindings.cpp`
- `standalone_test.cpp`
- `CMakeLists.txt`

### 2.2 Refactor OptiXSceneRenderer to Use OptiXContext
**File:** `OptiXSceneRenderer.cpp`

**Changes to Impl struct:**
```cpp
struct OptiXSceneRenderer::Impl {
    OptiXContext context;  // NEW: Low-level context

    // Remove: context (now in OptiXContext)
    // Keep: All scene state (camera, sphere, light, plane)
    // Keep: Pipeline handles (but created via context)
    // Keep: Dirty flags
};
```

**Replace all direct OptiX calls with context methods:**
- `optixDeviceContextCreate(...)` → `impl->context.initialize()`
- `optixModuleCreate(...)` → `impl->context.createModuleFromPTX(...)`
- `optixProgramGroupCreate(...)` → `impl->context.createRaygenProgramGroup(...)`
- `optixPipelineCreate(...)` → `impl->context.createPipeline(...)`
- `optixAccelBuild(...)` → `impl->context.buildCustomPrimitiveGAS(...)`
- `optixLaunch(...)` → `impl->context.launch(...)`

**Keep high-level logic:**
- Dirty flag tracking (`sphere_params_dirty`, `plane_params_dirty`)
- Camera calculations (`setCamera()` - lookAt → UVW conversion)
- Convenience methods (default alpha in `setSphereColor()`)
- Conditional pipeline rebuilds in `render()`

### 2.3 Update JNIBindings
**File:** `JNIBindings.cpp`

**Current problem:** Global state (`static OptiXWrapper* g_wrapper`)
- Prevents multiple OptiXRenderer instances
- Not thread-safe

**Solution:** Store native pointer in Java object field

**Changes:**
1. Remove global `g_wrapper`
2. Add helper to get/set native handle:
```cpp
static jlong getNativeHandle(JNIEnv* env, jobject obj) {
    jclass cls = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(cls, "nativeHandle", "J");
    return env->GetLongField(obj, fid);
}

static void setNativeHandle(JNIEnv* env, jobject obj, jlong handle) {
    jclass cls = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(cls, "nativeHandle", "J");
    env->SetLongField(obj, fid, handle);
}
```
3. Update all JNI methods to use per-instance handles

**Scala changes:**
```scala
class OptiXRenderer extends LazyLogging:
    private var nativeHandle: Long = 0L  // NEW: Store native pointer

    // Rest unchanged
```

---

## Phase 3: Code Quality Improvements

**Goal:** Address technical debt and code smells.

**Time Estimate:** 3-4 hours

### 3.1 Remove Dead Code
**Priority:** HIGH (causes confusion)

**Delete files:**
- `optix-jni/src/main/native/shaders/sphere_raygen.cu`
- `optix-jni/src/main/native/shaders/sphere_miss.cu`
- `optix-jni/src/main/native/shaders/sphere_closesthit.cu`

**Reason:** Only `sphere_combined.cu` is compiled (see CLAUDE.md troubleshooting).

**Update CMakeLists.txt:** Verify only `sphere_combined.cu` is referenced.

### 3.2 Extract Duplicate Pipeline Compile Options
**File:** `OptiXContext.cpp`

**Problem:** `OptixPipelineCompileOptions` duplicated in:
- `createModuleFromPTX()` (needed for module creation)
- `createPipeline()` (needed for pipeline creation)

**Solution:** Create helper or store as member:
```cpp
OptixPipelineCompileOptions OptiXContext::getDefaultPipelineCompileOptions() {
    OptixPipelineCompileOptions options = {};
    options.usesMotionBlur = false;
    options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;
    options.numPayloadValues = 4;
    options.numAttributeValues = 4;
    options.exceptionFlags = OPTIX_EXCEPTION_FLAG_NONE;
    options.pipelineLaunchParamsVariableName = "params";
    options.usesPrimitiveTypeFlags = OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM;
    return options;
}
```

### 3.3 Extract SBT Record Setup Pattern
**File:** `OptiXContext.cpp`

**Problem:** Three nearly identical blocks in `setupShaderBindingTable()`:
1. Create record struct
2. Fill data
3. Pack header
4. Allocate GPU memory
5. Copy to device

**Solution:** Template function (already declared in OptiXContext.h):
```cpp
template<typename T>
CUdeviceptr OptiXContext::createSBTRecord(OptixProgramGroup program_group, const T& data) {
    using SbtRecordType = SbtRecord<T>;
    SbtRecordType sbt_record;
    sbt_record.data = data;

    OPTIX_CHECK(optixSbtRecordPackHeader(program_group, &sbt_record));

    CUdeviceptr d_record;
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_record), sizeof(SbtRecordType)));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(d_record),
        &sbt_record,
        sizeof(SbtRecordType),
        cudaMemcpyHostToDevice
    ));

    return d_record;
}
```

**Update OptiXSceneRenderer to use template:**
```cpp
impl->sbt.raygenRecord = impl->context.createSBTRecord(
    impl->raygen_prog_group,
    raygen_data
);
```

### 3.4 Extract Resource Cleanup Pattern
**File:** `OptiXContext.cpp` or helper header

**Problem:** Repetitive destroy/nullify pattern for OptiX resources.

**Solution:** RAII wrapper or helper:
```cpp
template<typename T, void (*Destroy)(T)>
class OptixResource {
    T handle_ = nullptr;
public:
    OptixResource() = default;
    OptixResource(T handle) : handle_(handle) {}
    ~OptixResource() { if (handle_) Destroy(handle_); }

    OptixResource(const OptixResource&) = delete;
    OptixResource& operator=(const OptixResource&) = delete;

    OptixResource(OptixResource&& other) : handle_(other.handle_) {
        other.handle_ = nullptr;
    }

    T get() const { return handle_; }
    T release() { T h = handle_; handle_ = nullptr; return h; }
};

using PipelineHandle = OptixResource<OptixPipeline, optixPipelineDestroy>;
using ModuleHandle = OptixResource<OptixModule, optixModuleDestroy>;
// etc.
```

**Note:** This is optional - may be overkill for current use case. Consider deferring.

### 3.5 Extract Magic Constants
**Files:**
- `OptiXContext.cpp`
- `sphere_combined.cu`

**Create:** `optix-jni/src/main/native/include/OptiXMath.h`

**Extract:**
```cpp
namespace OptiXMath {
    constexpr float PI = 3.14159265358979323846f;
    constexpr float DEG_TO_RAD = PI / 180.0f;
    constexpr float COLOR_SCALE = 255.99f;  // Byte conversion
    constexpr float RAY_OFFSET = 0.001f;    // Self-intersection avoidance
    constexpr float MAX_RAY_DISTANCE = 1e16f;
}
```

**Update usages:**
- `OptiXSceneRenderer.cpp`: Use `OptiXMath::DEG_TO_RAD` in FOV calculation
- `sphere_combined.cu`: Use namespace constants

### 3.6 Remove Unused Functional Code in Scala
**File:** `OptiXRenderer.scala`

**Problem:** Lines 133-195 define functional methods that are never called:
- `loadFromSystemPath()`
- `detectPlatform()`
- `copyStreamToFile()`
- `loadFromClasspath()`
- `extractAndLoadLibrary()`
- `loadNativeLibrary()`

Actual loading uses imperative style (lines 69-131).

**Solution A (recommended):** Delete unused methods
**Solution B:** Refactor to use functional methods consistently

**Choose A for now** - simpler and actual code works fine.

### 3.7 Clean Up Scala Library Loading
**File:** `OptiXRenderer.scala`

**Current issues:**
- Mix of imperative try/catch and functional Try
- PTX extraction embedded in library loading
- Long nested blocks

**Improvements:**
- Extract PTX extraction to separate method
- Simplify error handling
- Add clearer comments

---

## Phase 4: Move Dynamic Scene Data to Params (Optional)

**Goal:** Improve performance by avoiding SBT rebuilds on parameter changes.

**Time Estimate:** 2-3 hours

**Priority:** MEDIUM (optimization, can defer)

### Current Problem
SBT contains scene data (sphere color, position, IOR, light, plane):
- Any parameter change requires SBT rebuild
- SBT rebuild = GPU memory allocations + copies
- Dirty flags mitigate but don't eliminate overhead

### Solution
Move dynamic data from SBT to `Params` struct:

**OptiXData.h changes:**
```cpp
struct Params {
    unsigned char* image;
    unsigned int   image_width;
    unsigned int   image_height;
    OptixTraversableHandle handle;

    // NEW: Move from SBT to Params
    float sphere_color[4];
    float sphere_ior;
    float sphere_scale;
    float light_dir[3];
    float light_intensity;
    int   plane_axis;
    bool  plane_positive;
    float plane_value;
};

// HitGroupData now only has geometry info
struct HitGroupData {
    float sphere_center[3];  // Could even move this to Params
    float sphere_radius;     // Could even move this to Params
};
```

**Benefits:**
- Parameter changes = simple cudaMemcpy to params buffer
- No SBT rebuild needed
- No GPU allocations on parameter change
- Standard OptiX practice for dynamic scenes

**Tradeoffs:**
- More data passed to every shader invocation
- Slightly more complex shader code (access via params instead of SBT)

**Shader changes:** Update to read from `params` instead of SBT data.

---

## Phase 5: Testing & Documentation

**Time Estimate:** 2-3 hours

### 5.1 Run Test Suite After Each Phase
```bash
sbt "project optixJni" test --warn
```

**Expected:** All 87 tests pass throughout refactoring.

**If tests fail:**
- Check for compilation errors first
- Verify resource cleanup (memory leaks)
- Check for logic errors in refactored code

### 5.2 Update Documentation
**File:** `CLAUDE.md`

**Add section:** "OptiX JNI Architecture"
```markdown
## OptiX JNI Architecture

The OptiX bindings use a two-layer architecture:

### Low-Level Layer: OptiXContext
- Pure OptiX API wrapper
- Stateless where possible
- Explicit resource management
- Direct 1:1 mapping to OptiX operations

**Files:**
- `include/OptiXContext.h`
- `OptiXContext.cpp`

### High-Level Layer: OptiXSceneRenderer
- Scene state management (sphere, camera, light, plane)
- Convenience methods (automatic camera calculations)
- Optimization (dirty flag tracking, conditional rebuilds)
- Uses OptiXContext for all OptiX operations

**Files:**
- `include/OptiXSceneRenderer.h`
- `OptiXSceneRenderer.cpp`

### JNI Interface
- Binds Scala OptiXRenderer to C++ OptiXSceneRenderer
- Per-instance native handles (supports multiple renderers)
- Error propagation via return codes

**Files:**
- `JNIBindings.cpp`
- `menger/optix/OptiXRenderer.scala`
```

### 5.3 Update CHANGELOG.md
```markdown
## [Unreleased]

### Changed
- **OptiX JNI Architecture**: Refactored into two-layer architecture (low-level OptiXContext + high-level OptiXSceneRenderer) for better separation of concerns and testability
- **OptiXRenderer**: Fixed global state issue - now supports multiple renderer instances
- **Build**: Removed unused shader files (sphere_raygen.cu, sphere_miss.cu, sphere_closesthit.cu)

### Improved
- Extracted duplicate pipeline compile options to reduce code duplication
- Extracted SBT record setup pattern to template function
- Extracted magic constants to OptiXMath namespace
- Cleaned up unused functional methods in OptiXRenderer.scala
```

---

## Implementation Order

1. ✅ **Phase 1** - Extract OptiXContext (low-level layer)
2. ✅ **Phase 2** - Refactor OptiXSceneRenderer (high-level layer)
3. ✅ **Phase 3.1** - Delete dead shader files
4. ✅ **Phase 3.2-3.5** - Code quality improvements (duplication, patterns, constants)
5. ✅ **Phase 3.6-3.7** - Clean up Scala code
6. ✅ **Phase 5.1** - Run full test suite
7. ⏸️ **Phase 4** - (Optional) Move scene data to Params - defer if time constrained
8. ✅ **Phase 5.2-5.3** - Update documentation

---

## Success Criteria

- [ ] All tests pass (87/87)
- [ ] No memory leaks (compute-sanitizer + Valgrind)
- [ ] No compilation warnings
- [ ] OptiXContext has no scene state (sphere, camera, etc.)
- [ ] OptiXSceneRenderer uses OptiXContext for all OptiX calls
- [ ] Multiple OptiXRenderer instances can coexist
- [ ] Dead code removed
- [ ] Documentation updated

---

## Rollback Plan

If refactoring causes issues:
1. Git revert to commit before refactoring started
2. Keep this document for future attempt
3. Consider smaller incremental changes

---

## Notes

- Keep commits atomic (one phase = one commit)
- Run tests after each phase
- Use `git stash` if need to switch tasks mid-phase
- This is a refactoring - no new features, no behavior changes
