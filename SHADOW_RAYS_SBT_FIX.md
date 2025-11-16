# Shadow Rays SBT Configuration Fix

**Status:** WORK IN PROGRESS - Shadow rays traced but not visible
**Date:** 2025-11-16
**Issue:** Shadow rays hit wrong closest-hit shader due to missing SBT configuration

## Problem Summary

Shadow rays are being traced (7433+ shadow rays counted in stats) but shadows are not visible in rendered images. The root cause is that shadow rays are hitting the MAIN closest-hit shader (`__closesthit__ch`) instead of the shadow closest-hit shader (`__closesthit__shadow`).

### Why This Happens

OptiX uses ray types to route different rays to different shaders via the Shader Binding Table (SBT). Currently:
- Only ONE hit group exists: `hitgroup_prog_group` → `__closesthit__ch`
- Shadow rays use SBT offset `0, 1, 0` (ray type 1) but there's no hit group at that offset
- OptiX falls back to the main hit group, which doesn't set the shadow occlusion payload

### What's Working

✅ CLI integration (`--shadows` flag)
✅ Shadow ray tracing (stats tracking works)
✅ CUDA shader logic (both `__closesthit__shadow` and plane shadow code)
✅ Light direction fixed (now comes from above: -1, 1, -1)

### What's Broken

❌ SBT has only one hit group (main rendering)
❌ No hit group for shadow rays (ray type 1)
❌ Shadow rays execute wrong shader → `shadow_occluded` stays 0
❌ No darkening applied to shadowed surfaces

## Required Fix: Add Shadow Ray Hit Group

### File Locations

- `optix-jni/src/main/native/OptiXWrapper.cpp` - Main implementation
- `optix-jni/src/main/native/include/OptiXWrapper.h` - Header
- `optix-jni/src/main/native/shaders/sphere_combined.cu` - Shaders (already correct)

### Step 1: Add Shadow Hit Group to Impl Struct

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Location:** Line 82 (in `struct OptiXWrapper::Impl`)

**Current code:**
```cpp
OptixProgramGroup raygen_prog_group = nullptr;
OptixProgramGroup miss_prog_group = nullptr;
OptixProgramGroup hitgroup_prog_group = nullptr;
```

**Add:**
```cpp
OptixProgramGroup raygen_prog_group = nullptr;
OptixProgramGroup miss_prog_group = nullptr;
OptixProgramGroup hitgroup_prog_group = nullptr;
OptixProgramGroup shadow_hitgroup_prog_group = nullptr;  // NEW: Shadow ray hit group
```

### Step 2: Create Shadow Hit Group Program

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Location:** In `createProgramGroups()` function (around line 257-260)

**Current code:**
```cpp
impl->hitgroup_prog_group = impl->optix_context.createHitgroupProgramGroup(
    impl->module, "__closesthit__ch",
    impl->module, "__intersection__sphere"
);
```

**Add after:**
```cpp
// Shadow ray hit group (uses same intersection but different closest-hit)
impl->shadow_hitgroup_prog_group = impl->optix_context.createHitgroupProgramGroup(
    impl->module, "__closesthit__shadow",  // Shadow closest-hit shader
    impl->module, "__intersection__sphere"  // Same intersection program
);
```

### Step 3: Update SBT to Include Shadow Hit Group

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Location:** In `setupShaderBindingTable()` function (around line 331-336)

**Current code:**
```cpp
impl->sbt.hitgroupRecordBase = impl->optix_context.createHitgroupSBTRecord(
    impl->hitgroup_prog_group,
    hg_data
);
impl->sbt.hitgroupRecordStrideInBytes = sizeof(SbtRecord<HitGroupData>);
impl->sbt.hitgroupRecordCount = 1;
```

**Replace with:**
```cpp
// Create array of hit group records (one per ray type)
const size_t hitgroup_record_size = sizeof(SbtRecord<HitGroupData>);
std::vector<SbtRecord<HitGroupData>> hitgroup_records(2);  // 2 ray types

// Ray type 0: Main rendering rays
SbtRecord<HitGroupData> main_hg_record;
main_hg_record.data = hg_data;
OPTIX_CHECK(optixSbtRecordPackHeader(impl->hitgroup_prog_group, &main_hg_record));
hitgroup_records[0] = main_hg_record;

// Ray type 1: Shadow rays
SbtRecord<HitGroupData> shadow_hg_record;
shadow_hg_record.data = hg_data;  // Same data, different shader
OPTIX_CHECK(optixSbtRecordPackHeader(impl->shadow_hitgroup_prog_group, &shadow_hg_record));
hitgroup_records[1] = shadow_hg_record;

// Allocate and upload hit group records to GPU
CUdeviceptr d_hitgroup_records;
const size_t hitgroup_records_size = hitgroup_record_size * 2;
CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_hitgroup_records), hitgroup_records_size));
CUDA_CHECK(cudaMemcpy(
    reinterpret_cast<void*>(d_hitgroup_records),
    hitgroup_records.data(),
    hitgroup_records_size,
    cudaMemcpyHostToDevice
));

impl->sbt.hitgroupRecordBase = d_hitgroup_records;
impl->sbt.hitgroupRecordStrideInBytes = hitgroup_record_size;
impl->sbt.hitgroupRecordCount = 2;  // Changed from 1 to 2
```

**NOTE:** The current code uses `createHitgroupSBTRecord()` which returns a single CUdeviceptr. The new code needs to manually pack and upload multiple records. You'll need to track `d_hitgroup_records` for cleanup.

### Step 4: Update Cleanup to Free Shadow Hit Group

**File:** `optix-jni/src/main/native/OptiXWrapper.cpp`

**Location:** In `dispose()` function

**Add:**
```cpp
if (impl->shadow_hitgroup_prog_group) {
    OPTIX_CHECK(optixProgramGroupDestroy(impl->shadow_hitgroup_prog_group));
    impl->shadow_hitgroup_prog_group = nullptr;
}
```

**Also add cleanup for the hit group records buffer** (if you tracked it separately):
```cpp
if (impl->d_hitgroup_records) {
    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_hitgroup_records)));
    impl->d_hitgroup_records = 0;
}
```

### Step 5: Verify OptiXContext Helper Supports Shadow Hit Groups

**File:** `optix-jni/src/main/native/include/OptiXContext.h`

Check if `createHitgroupProgramGroup` supports creating hit groups with only closest-hit (no any-hit):

```cpp
OptixProgramGroup createHitgroupProgramGroup(
    OptixModule module_ch, const char* ch_function_name,
    OptixModule module_is, const char* is_function_name,
    OptixModule module_ah = nullptr, const char* ah_function_name = nullptr
);
```

If the signature is different, adjust the call accordingly.

## Testing the Fix

After implementing the above changes:

1. **Compile:**
   ```bash
   sbt compile
   ```

2. **Test without shadows (baseline):**
   ```bash
   sbt "run --optix --sponge-type sphere --camera-pos 0,-2,5 --camera-lookat 0,1,0 --plane +y:2 --save-name no_shadow_test.png"
   ```

3. **Test with shadows:**
   ```bash
   sbt "run --optix --sponge-type sphere --camera-pos 0,-2,5 --camera-lookat 0,1,0 --plane +y:2 --shadows --save-name with_shadow_test.png"
   ```

4. **Expected result:**
   - `with_shadow_test.png` should show **pure black shadow** on the checkered plane where the sphere blocks the light
   - The shadow should be a dark circle/ellipse on the plane directly below the sphere
   - Light comes from (-1, 1, -1) → upper-left-back
   - Shadow should appear on lower-right portion of visible plane

5. **Verify stats:**
   ```bash
   sbt "run --optix --sponge-type sphere --shadows --stats --timeout 0.5"
   ```
   Should show `shadow=XXXX` with non-zero count

## Alternative: Simpler SBT Approach

If the above multi-record approach is too complex, an alternative is to use the SAME closest-hit shader for both ray types and detect shadow rays inside `__closesthit__ch`:

```cuda
extern "C" __global__ void __closesthit__ch() {
    // Check if this is a shadow ray (ray type from SBT offset)
    const unsigned int ray_type = optixGetRayFlags();  // Or use another method

    if (/* is shadow ray */) {
        optixSetPayload_0(1);  // Mark as occluded
        return;
    }

    // ... existing main rendering logic ...
}
```

However, this is less clean than having separate shaders per ray type.

## Known Issues After Fix

1. **Shadow too dark:** Currently set to pure black (r=g=b=0) for testing
   - **Fix:** Change back to `AMBIENT_LIGHT_FACTOR` in `sphere_combined.cu:296-299`

2. **Plane shadow offset wrong:** May need to adjust `plane_normal` direction
   - Current code uses absolute value of dot product for double-sided plane
   - Shadow offset should be along light direction, not plane normal

3. **Light direction:** Fixed to (-1, 1, -1) which may not match all use cases
   - Consider making light direction configurable via CLI

## Files Modified in WIP Commit

- `src/main/scala/menger/MengerCLIOptions.scala` - Added `--shadows` flag
- `src/main/scala/menger/OptiXResources.scala` - Added `setShadows()` method and fixed light direction
- `src/main/scala/menger/engines/OptiXEngine.scala` - Wired up shadows in create(), added to logs
- `src/main/scala/Main.scala` - Pass shadows parameter from CLI
- `src/test/scala/menger/OptiXEngineTest.scala` - Updated tests with shadows parameter
- `optix-jni/src/main/native/shaders/sphere_combined.cu` - Added shadow ray logic to plane rendering
- `CHANGELOG.md` - Updated with CLI integration status
- `optix-jni/ENHANCEMENT_PLAN.md` - Marked shadow rays as complete with CLI integration

## Next Steps

1. Implement SBT fix (Steps 1-4 above)
2. Test visual shadow rendering
3. Adjust shadow darkening factor (change from pure black to AMBIENT_LIGHT_FACTOR)
4. Add visual validation tests
5. Update CHANGELOG to mark feature as fully complete
6. Create final commit and merge to main

## References

- OptiX Programming Guide: Shader Binding Table section
- OptiX SDK Examples: `optixPathTracer` (uses multiple ray types)
- Current implementation: `optix-jni/SHADOW_RAYS_PLAN.md` (original plan)
