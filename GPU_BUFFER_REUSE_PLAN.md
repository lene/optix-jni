# GPU Buffer Reuse Implementation Plan

## Overview

This document provides a detailed implementation plan for optimizing GPU image buffer allocation in the OptiX rendering pipeline. The current implementation allocates and frees the GPU buffer on every render call, causing ~200-1000μs overhead per frame.

**Goal:** Cache GPU buffer and reallocate only when dimensions change
**Expected Performance Gain:** 5-10% for typical workloads, 99%+ allocation avoidance for animations
**Risk Level:** Low (well-understood CUDA pattern with clear safety guarantees)

---

## Current Implementation Analysis

### Current Code Flow (OptiXWrapper.cpp:446-525)

```cpp
void OptiXWrapper::render(int width, int height, unsigned char* output) {
    // ... validation ...

    // STEP 1: Allocate GPU buffer (EVERY CALL)
    CUdeviceptr d_image;
    const size_t image_size = width * height * 4; // RGBA
    CUDA_CHECK(cudaMalloc(&d_image, image_size));  // ~100-500μs

    // STEP 2: Setup params
    Params params;
    params.image = reinterpret_cast<unsigned char*>(d_image);
    // ... other params ...

    // STEP 3: Copy params to GPU
    CUDA_CHECK(cudaMemcpy(impl->d_params, &params, sizeof(Params),
                          cudaMemcpyHostToDevice));

    // STEP 4: Launch OptiX kernel
    impl->optix_context.launch(impl->pipeline, impl->sbt,
                               impl->d_params, width, height);
    // Note: launch() calls cudaDeviceSynchronize() internally

    // STEP 5: Copy result to CPU
    CUDA_CHECK(cudaMemcpy(output, d_image, image_size,
                          cudaMemcpyDeviceToHost));

    // STEP 6: Free GPU buffer (EVERY CALL)
    CUDA_CHECK(cudaFree(d_image));  // ~100-500μs
}
```

### Performance Analysis

**Overhead per frame:**
- `cudaMalloc`: 100-500μs (depends on size and fragmentation)
- `cudaFree`: 100-500μs (depends on fragmentation)
- **Total waste:** 200-1000μs per frame

**Impact scenarios:**
1. **60 FPS animation (800x600):**
   - Current: 60 × 500μs = 30ms wasted per second
   - With reuse: 1 × 500μs = 0.5ms (first frame only)
   - **Savings: 59/60 frames = 98.3%**

2. **Interactive rendering (constant window):**
   - Current: Every rotation/parameter change allocates
   - With reuse: Zero allocations after first frame
   - **Savings: 100%**

3. **Multi-resolution batch (varying sizes):**
   - Current: Allocate every frame
   - With reuse: Allocate only on size change
   - **Savings: ~80% (depends on size variation)**

---

## Safety Analysis

### Why Buffer Reuse is Safe

#### 1. Explicit Synchronization Guarantee

**Key Code:** `OptiXContext.cpp:424-425`
```cpp
void OptiXContext::launch(...) {
    OPTIX_CHECK(optixLaunch(...));
    CUDA_CHECK(cudaDeviceSynchronize());  // ← BLOCKS until GPU completes
}
```

**Guarantee:**
- When `launch()` returns, GPU kernel has **finished writing** to buffer
- Subsequent `cudaMemcpy` reads **completed results** only
- No race conditions possible between launch and memcpy

**CUDA Documentation:**
> "cudaDeviceSynchronize() blocks until the device has completed all preceding
> requested tasks. cudaDeviceSynchronize() returns an error if one of the
> preceding tasks has failed."

#### 2. Full Pixel Overwrite Guarantee

**Key Code:** `sphere_combined.cu:194-224` (raygen shader)
```cuda
extern "C" __global__ void __raygen__render_frame() {
    const unsigned int x = optixGetLaunchIndex().x;
    const unsigned int y = optixGetLaunchIndex().y;

    // ... ray tracing logic ...

    // EVERY pixel is written
    const unsigned int image_idx = (params.image_height - 1 - y) * params.image_width + x;
    params.image[image_idx] = make_uchar4(
        static_cast<unsigned char>(r * 255),
        static_cast<unsigned char>(g * 255),
        static_cast<unsigned char>(b * 255),
        255  // Alpha always 255
    );
}
```

**Guarantee:**
- OptiX launches exactly `width × height` threads (one per pixel)
- Each thread writes exactly one pixel
- **All pixels overwritten** - no dependency on previous contents
- Old data cannot "leak" into new frame

**OptiX Launch Configuration:**
```cpp
impl->optix_context.launch(..., width, height);
// Internally: optixLaunch(..., width, height, 1)
// Launches width × height × 1 threads
```

#### 3. Buffer Size Match Guarantee

**Key Logic:** Check before reuse
```cpp
const size_t required_size = width * height * 4;
if (impl->cached_image_size != required_size) {
    // Reallocate to exact size
    cudaFree(impl->d_image);
    cudaMalloc(&impl->d_image, required_size);
    impl->cached_image_size = required_size;
}
```

**Guarantee:**
- Buffer size always exactly matches render dimensions
- No buffer overruns possible
- No wasted memory (exact size, not oversized)

#### 4. Pointer Stability Guarantee

**Key Observation:**
- Buffer pointer assigned to `Params.image` before launch
- No reallocation between launch and memcpy
- Pointer remains valid throughout render call

**Code Flow:**
```cpp
// Pointer stored in params
params.image = reinterpret_cast<unsigned char*>(impl->d_image);

// Launch with params (pointer stable)
impl->optix_context.launch(...);

// Read from same pointer (still valid)
cudaMemcpy(output, impl->d_image, ...);
```

---

## Implementation Design

### Modified Impl Struct

**File:** `OptiXWrapper.cpp:94-143`

```cpp
struct OptiXWrapper::Impl {
    // ... existing fields ...

    // GPU buffers (created once, reused)
    CUdeviceptr d_gas_output_buffer = 0;     // Existing - GAS
    CUdeviceptr d_params = 0;                 // Existing - Launch params
    CUdeviceptr d_image = 0;                  // NEW - Cached image buffer
    size_t cached_image_size = 0;             // NEW - Cached buffer size

    // Note: d_vertex_buffer and d_radius_buffer can be removed (dead code)

    // ... rest of struct ...
};
```

**Field Semantics:**
- `d_image`: GPU device pointer for image buffer (0 = not allocated)
- `cached_image_size`: Size in bytes of allocated buffer (0 = not allocated)

**Invariants:**
- `(d_image == 0) ⟺ (cached_image_size == 0)` - Both zero or both non-zero
- `cached_image_size % 4 == 0` - Always RGBA (4 bytes per pixel)
- `cached_image_size = cached_width × cached_height × 4` (implied)

---

### Modified render() Method

**File:** `OptiXWrapper.cpp:446-525`

```cpp
void OptiXWrapper::render(int width, int height, unsigned char* output) {
    if (!impl->initialized) {
        throw std::runtime_error("[OptiX] render() called before initialize()");
    }

    try {
        // Update image dimensions for aspect ratio calculations
        impl->image_width = width;
        impl->image_height = height;

        // Build OptiX pipeline on first render call or when params change
        if (!impl->pipeline_built || impl->plane_params_dirty || impl->sphere_params_dirty) {
            buildPipeline();
            impl->pipeline_built = true;
            impl->plane_params_dirty = false;
            impl->sphere_params_dirty = false;
        }

        // ========================================
        // NEW: Reuse GPU image buffer
        // ========================================
        const size_t required_size = width * height * 4; // RGBA

        // Reallocate only if size doesn't match
        if (impl->cached_image_size != required_size) {
            // Free old buffer if exists
            if (impl->d_image) {
                CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_image)));
                impl->d_image = 0;
                impl->cached_image_size = 0;
            }

            // Allocate new buffer with exact required size
            CUDA_CHECK(cudaMalloc(
                reinterpret_cast<void**>(&impl->d_image),
                required_size
            ));
            impl->cached_image_size = required_size;
        }
        // ========================================

        // Set up launch parameters (use cached buffer)
        Params params;
        params.image = reinterpret_cast<unsigned char*>(impl->d_image);
        params.image_width = width;
        params.image_height = height;
        params.handle = impl->gas_handle;

        // Dynamic scene data (moved from SBT for performance)
        std::memcpy(params.sphere_color, impl->sphere_color, sizeof(float) * 4);
        params.sphere_ior = impl->sphere_ior;
        params.sphere_scale = impl->sphere_scale;
        std::memcpy(params.light_dir, impl->light_direction, sizeof(float) * 3);
        params.light_intensity = impl->light_intensity;
        params.plane_axis = impl->plane_axis;
        params.plane_positive = impl->plane_positive;
        params.plane_value = impl->plane_value;

        // Copy params to GPU
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(impl->d_params),
            &params,
            sizeof(Params),
            cudaMemcpyHostToDevice
        ));

        // Launch OptiX (includes cudaDeviceSynchronize internally)
        impl->optix_context.launch(
            impl->pipeline,
            impl->sbt,
            impl->d_params,
            width,
            height
        );

        // Copy result back to CPU
        CUDA_CHECK(cudaMemcpy(
            output,
            reinterpret_cast<void*>(impl->d_image),
            required_size,
            cudaMemcpyDeviceToHost
        ));

        // NOTE: No cudaFree here - buffer cached for next render

    } catch (const std::exception& e) {
        std::cerr << "[OptiX] Render failed: " << e.what() << std::endl;
        // Fill with error color (red)
        for (int i = 0; i < width * height; i++) {
            output[i * 4 + 0] = 255;  // R
            output[i * 4 + 1] = 0;    // G
            output[i * 4 + 2] = 0;    // B
            output[i * 4 + 3] = 255;  // A
        }
    }
}
```

**Key Changes:**
1. Calculate `required_size` before allocation check
2. Check if `cached_image_size != required_size`
3. Only reallocate on size mismatch
4. Always free old buffer before allocating new
5. Update cached size after successful allocation
6. Remove `cudaFree` at end of method

---

### Modified dispose() Method

**File:** `OptiXWrapper.cpp:527-575`

```cpp
void OptiXWrapper::dispose() {
    if (impl->initialized) {
        try {
            // Clean up OptiX pipeline resources using OptiXContext
            if (impl->pipeline) {
                impl->optix_context.destroyPipeline(impl->pipeline);
                impl->pipeline = nullptr;
            }

            // ... existing program group cleanup ...

            if (impl->module) {
                impl->optix_context.destroyModule(impl->module);
                impl->module = nullptr;
            }

            // ========================================
            // NEW: Clean up cached image buffer
            // ========================================
            if (impl->d_image) {
                CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_image)));
                impl->d_image = 0;
                impl->cached_image_size = 0;
            }
            // ========================================

            // Clean up GAS
            if (impl->d_gas_output_buffer) {
                impl->optix_context.destroyGAS(impl->d_gas_output_buffer);
                impl->d_gas_output_buffer = 0;
            }

            // Clean up params buffer
            if (impl->d_params) {
                CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_params)));
                impl->d_params = 0;
            }

            // REMOVE: Dead code cleanup for d_vertex_buffer and d_radius_buffer
            // These are never allocated in current implementation

            impl->optix_context.destroy();
            impl->initialized = false;

        } catch (const std::exception& e) {
            std::cerr << "[OptiX] Cleanup error: " << e.what() << std::endl;
        }
    }
}
```

**Key Changes:**
1. Add cleanup for `d_image` buffer
2. Reset both `d_image` and `cached_image_size` to zero
3. Remove dead code for `d_vertex_buffer` and `d_radius_buffer`

---

## Testing Strategy

### Unit Tests (C++ Google Test)

**File:** `optix-jni/src/main/native/tests/OptiXContextTest.cpp`

#### Test 1: Basic Render Reuse
```cpp
TEST_F(OptiXWrapperTest, RenderReusesSameBuffer) {
    OptiXWrapper wrapper;
    ASSERT_TRUE(wrapper.initialize());

    // First render - allocates buffer
    std::vector<unsigned char> output1(800 * 600 * 4);
    wrapper.render(800, 600, output1.data());

    // Second render - should reuse buffer (same dimensions)
    std::vector<unsigned char> output2(800 * 600 * 4);
    wrapper.render(800, 600, output2.data());

    // Verify renders succeeded
    ASSERT_NE(output1, output2);  // Different sphere positions
}
```

#### Test 2: Dimension Change Reallocation
```cpp
TEST_F(OptiXWrapperTest, RenderReallocatesOnDimensionChange) {
    OptiXWrapper wrapper;
    ASSERT_TRUE(wrapper.initialize());

    // Render at 800x600
    std::vector<unsigned char> output1(800 * 600 * 4);
    wrapper.render(800, 600, output1.data());

    // Render at 1024x768 - should reallocate
    std::vector<unsigned char> output2(1024 * 768 * 4);
    wrapper.render(1024, 768, output2.data());

    // Render back to 800x600 - should reallocate again
    std::vector<unsigned char> output3(800 * 600 * 4);
    wrapper.render(800, 600, output3.data());

    // All renders should succeed without corruption
    ASSERT_GT(countNonZeroPixels(output1), 0);
    ASSERT_GT(countNonZeroPixels(output2), 0);
    ASSERT_GT(countNonZeroPixels(output3), 0);
}
```

#### Test 3: Buffer Content Independence
```cpp
TEST_F(OptiXWrapperTest, BufferReuseNoContentLeakage) {
    OptiXWrapper wrapper;
    ASSERT_TRUE(wrapper.initialize());

    // Render sphere at position 1
    wrapper.setSphere(0.0f, 0.0f, 0.0f, 1.5f);
    std::vector<unsigned char> output1(800 * 600 * 4);
    wrapper.render(800, 600, output1.data());

    // Render sphere at position 2 (same dimensions)
    wrapper.setSphere(2.0f, 2.0f, 2.0f, 2.0f);
    std::vector<unsigned char> output2(800 * 600 * 4);
    wrapper.render(800, 600, output2.data());

    // Images should be different (no stale data)
    ASSERT_NE(output1, output2);

    // Verify sphere appears at new position
    // (specific pixel checks based on expected sphere location)
}
```

#### Test 4: Multiple Dimension Changes
```cpp
TEST_F(OptiXWrapperTest, MultipleResolutionChanges) {
    OptiXWrapper wrapper;
    ASSERT_TRUE(wrapper.initialize());

    std::vector<std::pair<int, int>> resolutions = {
        {640, 480}, {800, 600}, {1024, 768}, {1920, 1080},
        {800, 600}, {640, 480}  // Repeat to test cache behavior
    };

    for (const auto& [width, height] : resolutions) {
        std::vector<unsigned char> output(width * height * 4);
        ASSERT_NO_THROW(wrapper.render(width, height, output.data()));
        ASSERT_GT(countNonZeroPixels(output), 0)
            << "Render failed at " << width << "x" << height;
    }
}
```

---

### Integration Tests (Scala)

**File:** `optix-jni/src/test/scala/menger/optix/OptiXRendererSpec.scala`

#### Test 5: Animation Sequence (Same Dimensions)
```scala
"OptiXRenderer" should "efficiently render animation frames" in {
  assume(OptiXRenderer.isAvailable, "OptiX not available")

  val renderer = new OptiXRenderer()
  renderer.setSphere(0, 0, 0, 1.5f)
  renderer.setCamera(Array(0f, 0f, 3f), Array(0f, 0f, 0f), Array(0f, 1f, 0f), 60f)

  // Render 60 frames (simulating 1 second at 60 FPS)
  val frames = (0 until 60).map { i =>
    val angle = i * 6f // 6 degrees per frame
    renderer.setSphere(
      Math.cos(Math.toRadians(angle)).toFloat * 2f,
      0f,
      Math.sin(Math.toRadians(angle)).toFloat * 2f,
      1.5f
    )
    renderer.render(800, 600)
  }

  // Verify all frames rendered successfully
  frames.foreach { img =>
    img.size should be (800 * 600 * 4)
  }

  // Verify frames are different (animation is happening)
  frames.sliding(2).foreach { case Seq(frame1, frame2) =>
    frame1 should not equal frame2
  }
}
```

#### Test 6: Resolution Flexibility
```scala
"OptiXRenderer" should "handle varying resolutions" in {
  assume(OptiXRenderer.isAvailable, "OptiX not available")

  val renderer = new OptiXRenderer()
  renderer.setSphere(0, 0, 0, 1.5f)

  val resolutions = Seq((640, 480), (800, 600), (1024, 768), (1920, 1080))

  resolutions.foreach { case (width, height) =>
    val img = renderer.render(width, height)
    img.size should be (width * height * 4)

    // Verify image is not solid red (error indicator)
    val allRed = img.grouped(4).forall { pixel =>
      pixel(0) == 255 && pixel(1) == 0 && pixel(2) == 0
    }
    allRed should be (false)
  }
}
```

---

### Performance Tests

#### Test 7: Benchmark Allocation Overhead
```cpp
TEST_F(OptiXWrapperTest, BenchmarkRenderPerformance) {
    OptiXWrapper wrapper;
    ASSERT_TRUE(wrapper.initialize());
    wrapper.setSphere(0.0f, 0.0f, 0.0f, 1.5f);

    std::vector<unsigned char> output(800 * 600 * 4);

    // Warmup render
    wrapper.render(800, 600, output.data());

    // Time 100 renders at same resolution
    const int iterations = 100;
    auto start = std::chrono::high_resolution_clock::now();

    for (int i = 0; i < iterations; i++) {
        wrapper.render(800, 600, output.data());
    }

    auto end = std::chrono::high_resolution_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start);

    double avg_us = duration.count() / static_cast<double>(iterations);
    std::cout << "Average render time: " << avg_us << " μs" << std::endl;

    // Expected: < 20ms per frame (50 FPS minimum)
    ASSERT_LT(avg_us, 20000);  // 20ms = 20,000μs
}
```

**Expected Results:**
- **Before optimization:** ~10-15ms per frame (includes 200-500μs allocation overhead)
- **After optimization:** ~9-14ms per frame (allocation overhead only on first frame)
- **Improvement:** ~5-10% faster, 98%+ allocation avoidance

---

## Memory Leak Verification

### Valgrind Test
```bash
# Build with debug symbols
sbt "project optixJni" compile

# Run Valgrind on C++ test
valgrind --leak-check=full \
         --show-leak-kinds=all \
         --track-origins=yes \
         optix-jni/target/native/x86_64-linux/build/optixcontext_test

# Expected output:
# HEAP SUMMARY:
#   in use at exit: 0 bytes in 0 blocks
#   total heap usage: X allocs, X frees, Y bytes allocated
# All heap blocks were freed -- no leaks are possible
```

### compute-sanitizer Test
```bash
# Run GPU memory leak detection
compute-sanitizer --tool memcheck \
                  optix-jni/target/native/x86_64-linux/build/optixcontext_test

# Expected output:
# ========= ERROR SUMMARY: 0 errors
```

### Scala Integration Memory Test
```scala
"OptiXRenderer" should "not leak memory across multiple renders" in {
  assume(OptiXRenderer.isAvailable, "OptiX not available")

  val renderer = new OptiXRenderer()
  renderer.setSphere(0, 0, 0, 1.5f)

  // Render 1000 frames to expose leaks
  (0 until 1000).foreach { i =>
    val img = renderer.render(800, 600)
    // Let GC clean up Scala-side array
  }

  renderer.dispose()

  // If test completes without OOM, memory management is correct
  succeed
}
```

---

## Rollback Plan

If issues discovered during testing:

### Option 1: Conditional Reuse
Add flag to disable optimization:
```cpp
struct Impl {
    bool enable_buffer_reuse = true;  // Can be disabled
    // ...
};

void render(...) {
    if (impl->enable_buffer_reuse && impl->cached_image_size == required_size) {
        // Reuse
    } else {
        // Allocate fresh
    }
}
```

### Option 2: Revert Commit
```bash
git revert <commit-hash>
sbt "project optixJni" test --warn
```

### Option 3: Branch Strategy
```bash
# Develop on feature branch
git checkout -b feature/gpu-buffer-reuse

# If issues found, switch back
git checkout main
```

---

## Implementation Checklist

- [ ] Add `d_image` and `cached_image_size` to `Impl` struct
- [ ] Modify `render()` to check cached size before allocation
- [ ] Remove `cudaFree()` from end of `render()`
- [ ] Add `d_image` cleanup to `dispose()`
- [ ] Remove dead code (`d_vertex_buffer`, `d_radius_buffer`)
- [ ] Add C++ unit tests (Tests 1-4, 7)
- [ ] Add Scala integration tests (Tests 5-6)
- [ ] Run Valgrind memory leak test
- [ ] Run compute-sanitizer GPU memory test
- [ ] Benchmark performance improvement
- [ ] Update documentation
- [ ] Create git commit with tests

---

## Success Criteria

### Functional
- ✅ All existing tests pass
- ✅ New tests verify buffer reuse behavior
- ✅ No memory leaks (Valgrind + compute-sanitizer)
- ✅ Renders identical to non-optimized version (pixel-perfect)

### Performance
- ✅ 5-10% improvement in typical render time
- ✅ 98%+ allocation avoidance for animations
- ✅ No regression for varying resolutions

### Code Quality
- ✅ Clean implementation following existing patterns
- ✅ Clear comments explaining optimization
- ✅ No compiler warnings
- ✅ Passes code review

---

## References

- CUDA C Programming Guide: Memory Management Best Practices
- OptiX Programming Guide 9.0: Section 3.8 (Pipeline Launch)
- OptiX API Reference: optixLaunch()
- CUDA Runtime API: cudaDeviceSynchronize()

---

## Document History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-11-09 | Claude Code | Initial detailed plan |

---

**Next Steps:**
1. Review this plan for technical correctness
2. Confirm safety analysis addresses concerns
3. Decide execution order (see main improvement plan)
4. Begin implementation
