# OptiX Renderer Enhancement Plan

**Created:** 2025-11-09
**Updated:** 2025-11-16
**Status:** Sprint 1 Complete (Features 1.1 & 1.2 Complete)

## Overview

This document outlines the comprehensive plan for enhancing the OptiX ray tracing renderer with advanced features focused on quality, interactivity, and realistic lighting. All features are OptiX-specific and do not affect the existing LibGDX rendering pipeline.

---

## Goals

1. **Quality:** Adaptive antialiasing, realistic shadows, caustics
2. **Performance:** Ray statistics for optimization insights
3. **Interactivity:** Mouse camera control
4. **Flexibility:** Multiple configurable light sources

---

## Progress Summary

### Completed Features
- ✅ **Ray Statistics (1.1)** - Completed 2025-11-14
  - Tracks total, primary, reflected, refracted rays
  - Displays formatted statistics via `--stats` flag
  - Zero performance overhead when disabled
  - Time spent: ~6 hours

- ✅ **Shadow Rays (1.2)** - Completed 2025-11-16
  - Cast shadow rays from opaque surfaces to light source
  - Tracks shadow ray statistics
  - Darkens surfaces in shadow (0.2x ambient lighting)
  - Enabled via `setShadows(true)` API and `--shadows` CLI flag
  - 6 comprehensive tests added to RayStatsTest
  - CLI integration complete with validation
  - Time spent: ~5 hours

### In Progress
- 🔄 None - Sprint 1 complete!

### Investigation Completed (Deferred)
- ⏸️ **Dynamic Window Resizing (5.1)** - Investigated 2025-11-09 to 2025-11-14
  - Time spent: ~10 hours investigation
  - Result: Deferred to Sprint 5 due to complexity
  - See Sprint 5 section for details

### Code Quality Improvements (2025-11-14)
- ✅ Fixed boolean operator precedence bug in AnimationSpecification
- ✅ Made model caches thread-safe (ConcurrentHashMap in Cube/Sphere)
- ✅ Documented intentional use of `throw` and `.get` for clarity
- ✅ All 95 tests passing
- Time spent: ~2 hours

**Total time spent so far:** ~23 hours (11h features + 10h resize investigation + 2h code quality)

---

## Sprint Breakdown

### Sprint 1: Foundation (11 hours) - ✅ COMPLETE
Quick wins that establish core capabilities and visual improvements.
- ✅ Feature 1.1: Ray Statistics (COMPLETE - 6 hours)
- ✅ Feature 1.2: Shadow Rays (COMPLETE - 5 hours)

### Sprint 2: Interactivity (10-13 hours) - PLANNED
Make the OptiX window fully interactive and user-friendly.

### Sprint 3: Advanced Quality (10-15 hours) - PLANNED
Implement sophisticated antialiasing for production-quality output.

### Sprint 4: Advanced Lighting (15-25 hours) - PLANNED
Add photon mapping for physically accurate caustics.

### Sprint 5: Complex Features (Status: Deferred) - DEFERRED
Features requiring further investigation and prototyping.
- ⏸️ Feature 5.1: Dynamic Window Resizing (10+ hours spent, no resolution)

**Total estimated effort (Sprints 1-4):** 39-59 hours
**Deferred (Sprint 5):** 10-20 hours additional
**Actual time spent:** 23 hours (as of 2025-11-16) - Sprint 1 complete

---

## Feature Specifications

---

## Sprint 1: Foundation

### 1.1 Ray Statistics

**Priority:** HIGH
**Effort:** 4-6 hours
**Risk:** Low

#### Goal
Track and report ray tracing metrics to understand performance characteristics and optimize future features.

#### Metrics to Track

1. **Total rays cast** - All rays generated during render
2. **Primary rays** - Initial camera rays (equals pixel count)
3. **Reflected rays** - Rays generated from Fresnel reflection
4. **Refracted rays** - Rays transmitted through sphere
5. **Min/Max trace depth** - Shallowest and deepest ray recursion
6. **Average trace depth** - Mean recursion depth across all rays

#### Output Format

```
Ray Statistics:
  Total rays: 1,234,567
  Primary rays: 480,000 (800×600 pixels)
  Reflected: 456,789 (37.0%)
  Refracted: 297,778 (24.1%)
  Trace depth: min=1, max=5, avg=2.57
  Render time: 145.3ms (3,293,410 rays/sec)
```

#### Implementation

**Data Structure (OptiXData.h):**
```cpp
struct RayStats {
    unsigned long long total_rays;
    unsigned long long primary_rays;
    unsigned long long reflected_rays;
    unsigned long long refracted_rays;
    unsigned int max_depth_reached;
    unsigned int min_depth_reached;
};
```

**CUDA Shader (sphere_combined.cu):**
- Use atomic operations for thread-safe counters:
  ```cuda
  atomicAdd(&stats.total_rays, 1);
  atomicMax(&stats.max_depth_reached, current_depth);
  ```
- Increment counters at appropriate shader locations:
  - Ray generation program: `primary_rays++`
  - Closest hit (reflection path): `reflected_rays++`
  - Closest hit (refraction path): `refracted_rays++`

**Host Code (OptiXWrapper.cpp):**
- Allocate stats buffer on GPU (`cudaMalloc`)
- Initialize to zero before each render
- Copy back to host after render (`cudaMemcpy`)
- Return stats struct to Scala layer

**Scala Layer (OptiXRenderer.scala):**
```scala
case class RayStats(
  totalRays: Long,
  primaryRays: Long,
  reflectedRays: Long,
  refractedRays: Long,
  maxDepth: Int,
  minDepth: Int
)

def render(width: Int, height: Int): (Array[Byte], RayStats)
```

#### Files to Modify

- `optix-jni/src/main/native/include/OptiXData.h`
  - Add `RayStats` struct
  - Add `RayStats* stats` pointer to `Params` struct

- `optix-jni/src/main/native/shaders/sphere_combined.cu`
  - Add atomic counter increments in ray generation and closest hit programs
  - Track min/max depth using `atomicMin`/`atomicMax`

- `optix-jni/src/main/native/OptiXWrapper.cpp`
  - Allocate/deallocate GPU stats buffer in constructor/destructor
  - Zero stats buffer before render
  - Copy stats back to host after render
  - Return stats via output parameter

- `optix-jni/src/main/native/include/OptiXWrapper.h`
  - Update `render()` signature to return stats

- `optix-jni/src/main/native/JNIBindings.cpp`
  - Marshal `RayStats` struct from C++ to Java/Scala
  - Return as Scala case class or array

- `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala`
  - Define `RayStats` case class
  - Update `render()` to return tuple `(Array[Byte], RayStats)`

- `src/main/scala/menger/OptiXResources.scala`
  - Print formatted statistics after render completes
  - Calculate percentages and derived metrics (rays/sec)

#### Testing

1. **Unit test:** Verify stats counters are accurate
   - 800×600 image → 480,000 primary rays
   - Transparent sphere → refracted rays > 0
   - Opaque sphere → refracted rays = 0

2. **Performance test:** Ensure atomic operations don't significantly impact performance
   - Baseline render time vs. stats-enabled render time
   - Acceptable overhead: <5%

#### Acceptance Criteria

- ✅ All ray counts accurate (verified against known scenarios)
- ✅ Statistics printed to console after render
- ✅ Performance overhead <5%
- ✅ No race conditions or incorrect atomic operations

---

### 1.2 Shadow Rays

**Priority:** HIGH
**Effort:** 4-6 hours
**Risk:** Low

#### Goal
Add realistic hard shadows by casting shadow rays from plane intersections to light sources.

#### CLI Parameters

```bash
--shadows         # Enable shadow rays (default: off)
```

#### Algorithm

When a ray hits the **plane**:

1. **Shadow ray origin:** Plane hit point + normal × epsilon (avoid self-intersection)
2. **Shadow ray direction:**
   - Directional light: Negate light direction
   - Point light: Direction from hit point to light position
3. **Shadow test:** Cast ray with `optixTrace()`
   - If ray hits sphere → point is in shadow (darken)
   - If ray misses → point is lit (full brightness)
4. **Shadow intensity:**
   - In shadow: Multiply plane color by 0.2 (ambient only)
   - Not in shadow: Full lighting calculation

#### Implementation

**CUDA Shader (sphere_combined.cu):**

Modify the **miss program** (plane rendering):

```cuda
if (params.shadows_enabled) {
    // Compute shadow ray
    float3 shadow_origin = hit_point + plane_normal * SHADOW_RAY_OFFSET;
    float3 shadow_dir = normalize(-params.light_dir); // For directional light

    // Cast shadow ray (use separate ray type or flag)
    unsigned int shadow_hit = 0;
    optixTrace(
        params.handle,
        shadow_origin,
        shadow_dir,
        SHADOW_RAY_OFFSET,
        MAX_RAY_DISTANCE,
        0.0f,
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_TERMINATE_ON_FIRST_HIT, // Optimization: early exit
        SHADOW_RAY_TYPE, // Separate ray type
        1, // Number of ray types
        SHADOW_RAY_TYPE,
        shadow_hit
    );

    // Apply shadow
    if (shadow_hit) {
        color *= 0.2f; // Ambient only (80% darkening)
    }
}
```

**Shadow Ray Handling:**
- Add new ray type: `SHADOW_RAY_TYPE = 1` (primary rays = 0)
- Shadow rays don't need full shading, just hit/miss
- Optimize with `OPTIX_RAY_FLAG_TERMINATE_ON_FIRST_HIT`

**Data Structure:**
```cpp
// In OptiXData.h Params struct
bool shadows_enabled;
```

#### Files to Modify

- `optix-jni/src/main/native/include/OptiXData.h`
  - Add `bool shadows_enabled` to `Params`

- `optix-jni/src/main/native/include/OptiXConstants.h`
  - Add `SHADOW_RAY_OFFSET` constant (e.g., 0.001f)

- `optix-jni/src/main/native/shaders/sphere_combined.cu`
  - Modify miss program to cast shadow rays
  - Add shadow ray type handling in closest hit (just return hit=1)

- `optix-jni/src/main/native/OptiXWrapper.cpp`
  - Add `setShadows(bool enabled)` method
  - Update `Params` before render

- `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala`
  - Add `def setShadows(enabled: Boolean): Unit`

- `src/main/scala/menger/CommandLineInterface.scala`
  - Add `--shadows` boolean flag parsing

- `src/main/scala/menger/OptiXResources.scala`
  - Configure shadows from CLI options

#### Testing

1. **Visual test:** Place sphere between light and plane
   - Shadow should appear on plane beneath sphere
   - Shadow shape should match sphere silhouette

2. **Edge cases:**
   - Transparent sphere (alpha < 1.0) → Should still cast shadow
   - Multiple renders → Shadows consistent
   - No plane visible → No performance impact (shadow rays not cast)

3. **Performance test:** Measure impact with ray statistics
   - Shadow rays should roughly equal primary rays hitting plane
   - Frame time increase: ~10-30% (acceptable for quality gain)

#### Acceptance Criteria

- ✅ Hard shadows visible on plane when sphere occludes light
- ✅ Shadow position/shape geometrically correct
- ✅ `--shadows` flag toggles feature on/off
- ✅ No visual artifacts (shadow acne, peter-panning)
- ✅ Performance impact <30%

---

## Sprint 2: Interactivity

### 2.1 Mouse Camera Control

**Priority:** HIGH
**Effort:** 4-5 hours
**Risk:** Low

#### Goal
Enable click-and-drag mouse controls to orbit, pan, and zoom the camera in the OptiX window, matching the interaction model of the LibGDX view.

#### Reference Implementation
`menger.input.CameraController` - Provides proven pattern for camera manipulation.

#### Mouse Controls

| Input | Action |
|-------|--------|
| Left-click drag | Orbit camera around lookAt point (azimuth/elevation) |
| Right-click drag | Pan camera (translate lookAt and eye together) |
| Scroll wheel | Zoom in/out (move eye closer/farther from lookAt) |

#### Implementation

**Camera Representation:**
- **Eye position:** Camera location in world space
- **LookAt point:** Point camera is aimed at (orbit center)
- **Up vector:** Camera's up direction (typically +Y)
- **Distance:** |eye - lookAt| (preserved during orbit)

**Orbit Mathematics (Spherical Coordinates):**
```scala
// Convert mouse delta to angle change
azimuth += deltaX * sensitivity    // Horizontal rotation
elevation += deltaY * sensitivity  // Vertical rotation
elevation = clamp(elevation, -89°, 89°) // Prevent gimbal lock

// Convert spherical to Cartesian
eye.x = lookAt.x + distance * cos(elevation) * sin(azimuth)
eye.y = lookAt.y + distance * sin(elevation)
eye.z = lookAt.z + distance * cos(elevation) * cos(azimuth)
```

**Pan Mathematics:**
```scala
// Move both eye and lookAt by same offset
val right = up.cross(forward).normalize()
val offset = right * deltaX + up * deltaY
eye += offset
lookAt += offset
```

**Zoom Mathematics:**
```scala
// Move eye along view direction
distance *= (1.0 - deltaScroll * zoomSpeed)
distance = clamp(distance, minDistance, maxDistance)
eye = lookAt + forward * distance
```

#### Class Structure

**New File: `src/main/scala/menger/input/OptiXCameraController.scala`**

```scala
package menger.input

import com.badlogic.gdx.InputAdapter
import com.badlogic.gdx.math.Vector3
import menger.OptiXResources

class OptiXCameraController(optixResources: OptiXResources) extends InputAdapter {

  private val eye = new Vector3(/* initial */)
  private val lookAt = new Vector3(0, 0, 0)
  private val up = new Vector3(0, 1, 0)

  private var distance: Float = eye.dst(lookAt)
  private var azimuth: Float = /* compute from eye */
  private var elevation: Float = /* compute from eye */

  private var lastX = 0
  private var lastY = 0
  private var leftButtonDown = false
  private var rightButtonDown = false

  override def touchDown(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean = {
    lastX = screenX
    lastY = screenY
    if (button == 0) leftButtonDown = true  // Left button
    if (button == 1) rightButtonDown = true // Right button
    true
  }

  override def touchUp(screenX: Int, screenY: Int, pointer: Int, button: Int): Boolean = {
    if (button == 0) leftButtonDown = false
    if (button == 1) rightButtonDown = false
    true
  }

  override def touchDragged(screenX: Int, screenY: Int, pointer: Int): Boolean = {
    val deltaX = screenX - lastX
    val deltaY = screenY - lastY
    lastX = screenX
    lastY = screenY

    if (leftButtonDown) {
      orbit(deltaX, deltaY)
    } else if (rightButtonDown) {
      pan(deltaX, deltaY)
    }

    updateCamera()
    true
  }

  override def scrolled(amountX: Float, amountY: Float): Boolean = {
    zoom(amountY)
    updateCamera()
    true
  }

  private def orbit(deltaX: Int, deltaY: Int): Unit = {
    // Update spherical coordinates and recompute eye position
  }

  private def pan(deltaX: Int, deltaY: Int): Unit = {
    // Translate eye and lookAt together
  }

  private def zoom(amount: Float): Unit = {
    // Adjust distance
  }

  private def updateCamera(): Unit = {
    optixResources.setCamera(
      eye.toArray,
      lookAt.toArray,
      up.toArray,
      fov = 45f
    )
    optixResources.requestRender() // Trigger re-render
  }
}
```

#### Files to Modify

- **Create:** `src/main/scala/menger/input/OptiXCameraController.scala`
  - Implement mouse input handling
  - Update OptiX camera parameters on interaction

- `src/main/scala/menger/OptiXResources.scala`
  - Add `requestRender()` method to trigger re-render
  - Expose camera configuration method

- `src/main/scala/menger/InteractiveMengerEngine.scala` or equivalent
  - Register `OptiXCameraController` with input multiplexer
  - Ensure OptiX window receives input events

#### Testing

1. **Interaction test:**
   - Left-drag → Camera orbits smoothly around sphere
   - Right-drag → Camera pans without changing view angle
   - Scroll → Zoom in/out without orbit

2. **Edge cases:**
   - Elevation clamped to ±89° (no gimbal lock)
   - Zoom clamped to min/max distance (no camera inside sphere)
   - Smooth motion at various mouse speeds

3. **Integration test:**
   - OptiX camera control doesn't interfere with LibGDX window controls
   - Both windows can be controlled independently

#### Acceptance Criteria

- ✅ Left-click drag orbits camera around scene
- ✅ Right-click drag pans camera
- ✅ Scroll wheel zooms in/out
- ✅ No gimbal lock at poles
- ✅ Smooth, responsive interaction (re-render <100ms)
- ✅ Independent control from LibGDX window

---

### 2.2 Multiple Light Sources

**Priority:** MEDIUM → ✅ COMPLETE
**Effort:** 6-8 hours (actual: ~8 hours)
**Risk:** Medium
**Status:** Complete (native layer 2025-11-18, CLI integration 2025-11-19)

#### Goal
Support multiple lights with different types (directional, point) and properties (color, intensity) to enable creative lighting setups.

#### CLI Parameters

```bash
# Directional light (sun-like, infinite distance)
--light type=directional direction=-1,-1,-1 intensity=1.0 color=1.0,1.0,1.0

# Point light (radiates from position)
--light type=point position=2,3,4 intensity=0.5 color=1.0,0.8,0.6

# Multiple lights
--light type=directional direction=0,-1,0 intensity=0.8 color=1.0,1.0,1.0 \
--light type=point position=-2,3,2 intensity=0.4 color=0.8,0.8,1.0
```

#### Light Types

**1. Directional Light**
- Rays are parallel (sun-like)
- Direction is constant across scene
- No distance attenuation
- **Use case:** Main scene lighting, outdoor scenes

**2. Point Light**
- Radiates from a position in all directions
- Intensity falls off with inverse-square law: `I = I₀ / (1 + d²)`
  - `d` = distance from light to hit point
  - `I₀` = light intensity parameter
- **Use case:** Light bulbs, spotlights, local illumination

#### Data Structures

**Light Struct (OptiXData.h):**
```cpp
enum LightType {
    LIGHT_TYPE_DIRECTIONAL = 0,
    LIGHT_TYPE_POINT = 1
};

struct Light {
    LightType type;
    float direction[3];  // For directional lights (normalized)
    float position[3];   // For point lights
    float color[3];      // RGB color (0.0-1.0)
    float intensity;     // Brightness multiplier
};

// In Params struct:
Light lights[MAX_LIGHTS];  // Max 8 lights initially
int num_lights;
bool shadows_enabled;      // Apply to all lights or per-light?
```

**Constants:**
```cpp
constexpr int MAX_LIGHTS = 8;
```

#### Shader Implementation

**Lighting Loop (sphere_combined.cu):**

```cuda
// Accumulate contributions from all lights
float3 total_lighting = make_float3(0.0f, 0.0f, 0.0f);

for (int i = 0; i < params.num_lights; ++i) {
    const Light& light = params.lights[i];

    float3 light_dir;
    float attenuation = 1.0f;

    if (light.type == LIGHT_TYPE_DIRECTIONAL) {
        light_dir = normalize(make_float3(
            -light.direction[0],
            -light.direction[1],
            -light.direction[2]
        ));
    } else if (light.type == LIGHT_TYPE_POINT) {
        float3 light_pos = make_float3(
            light.position[0],
            light.position[1],
            light.position[2]
        );
        float3 to_light = light_pos - hit_point;
        float distance = length(to_light);
        light_dir = to_light / distance;

        // Inverse-square falloff
        attenuation = 1.0f / (1.0f + distance * distance);
    }

    // Shadow test (if enabled)
    float shadow = 1.0f;
    if (params.shadows_enabled) {
        shadow = castShadowRay(hit_point, light_dir);
    }

    // Diffuse lighting
    float ndotl = fmaxf(dot(normal, light_dir), 0.0f);
    float3 light_color = make_float3(
        light.color[0],
        light.color[1],
        light.color[2]
    );

    total_lighting += light_color * light.intensity * attenuation * ndotl * shadow;
}

// Add ambient term
total_lighting += make_float3(AMBIENT_LIGHT_FACTOR);

// Apply to surface color
final_color = surface_color * total_lighting;
```

#### CLI Parsing

**CommandLineInterface.scala:**

Parse `--light` as repeatable option with key=value pairs:

```scala
case class LightSpec(
  lightType: String,        // "directional" or "point"
  direction: Option[Vector3], // For directional
  position: Option[Vector3],  // For point
  intensity: Float = 1.0f,
  color: Color = Color.WHITE
)

// Parse --light arguments
val lights = args.filter(_.startsWith("--light")).map { arg =>
  val pairs = arg.split(" ").tail.map { pair =>
    val Array(key, value) = pair.split("=")
    key -> value
  }.toMap

  LightSpec(
    lightType = pairs("type"),
    direction = pairs.get("direction").map(parseVector3),
    position = pairs.get("position").map(parseVector3),
    intensity = pairs.getOrElse("intensity", "1.0").toFloat,
    color = pairs.get("color").map(parseColor).getOrElse(Color.WHITE)
  )
}
```

#### Backward Compatibility

**Keep existing single-light API:**
```scala
// Old API (still works)
renderer.setLight(direction, intensity)

// New API
renderer.setLights(lights: Array[Light])
```

**Strategy:**
- If `setLight()` called → Convert to single-element `lights` array
- If `setLights()` called → Use full array
- Default: One directional light at (-1, -1, -1)

#### Files to Modify

- `optix-jni/src/main/native/include/OptiXData.h`
  - Add `Light` struct and `LightType` enum
  - Add `lights` array and `num_lights` to `Params`

- `optix-jni/src/main/native/include/OptiXConstants.h`
  - Add `MAX_LIGHTS` constant

- `optix-jni/src/main/native/shaders/sphere_combined.cu`
  - Replace single-light calculation with multi-light loop
  - Implement per-light shadow rays
  - Handle directional and point light types

- `optix-jni/src/main/native/OptiXWrapper.cpp`
  - Add `setLights(Light* lights, int count)` method
  - Keep `setLight()` for backward compatibility (converts to single-element array)

- `optix-jni/src/main/native/JNIBindings.cpp`
  - Add JNI binding for `setLights()` array parameter

- `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala`
  - Add `Light` case class
  - Add `def setLights(lights: Array[Light]): Unit`

- `src/main/scala/menger/CommandLineInterface.scala`
  - Add `LightSpec` case class
  - Parse `--light` with key=value pairs
  - Allow multiple `--light` arguments

- `src/main/scala/menger/OptiXResources.scala`
  - Convert `LightSpec` to `Light` and configure renderer

#### Testing

1. **Single light:** Backward compatibility test
   - Existing scenes render identically with old API

2. **Multiple directional lights:**
   - Two opposing directional lights → Uniform illumination
   - Colored lights (red + blue) → Purple regions where they overlap

3. **Point lights:**
   - Light near sphere → Bright center, darker edges (falloff)
   - Light far away → More uniform illumination
   - Verify inverse-square attenuation formula

4. **Mixed lights:**
   - Directional (key light) + point (fill light) → Realistic lighting

5. **Shadows with multiple lights:**
   - Two lights → Two overlapping shadows
   - Performance test: Shadow rays scale with num_lights

#### Acceptance Criteria

- ✅ Support up to 8 lights simultaneously
- ✅ Directional and point lights work correctly
- ✅ Light color and intensity applied properly
- ✅ Point light falloff matches inverse-square law
- ✅ Shadows work with multiple lights (multiple shadow rays per hit)
- ✅ Backward compatible with existing single-light API
- ✅ CLI parsing handles complex `--light` arguments
- ✅ Performance acceptable with 4-8 lights (<2x slowdown vs single light)

---

## Sprint 3: Advanced Quality

### 3.1 Recursive Adaptive Antialiasing

**Priority:** HIGH (Quality)
**Effort:** 10-15 hours
**Risk:** Medium-High

#### Goal
Implement recursive adaptive super-sampling with configurable subdivision depth to eliminate aliasing artifacts while maintaining performance on smooth regions.

#### CLI Parameters

```bash
--antialiasing              # Enable adaptive AA (default: off)
--aa-max-depth N            # Max recursion depth (default: 2, range: 1-4)
--aa-threshold T            # Color difference threshold (default: 0.1, range: 0.0-1.0)
```

**Example usage:**
```bash
# High quality (up to 81 samples/pixel in high-contrast areas)
--antialiasing --aa-max-depth 2 --aa-threshold 0.08

# Maximum quality (up to 729 samples/pixel, slow!)
--antialiasing --aa-max-depth 3 --aa-threshold 0.05

# Performance mode (up to 9 samples/pixel)
--antialiasing --aa-max-depth 1 --aa-threshold 0.15
```

#### Algorithm Overview

**Recursive 3×3 Subdivision:**

1. **Level 0 (Initial pass):**
   - Render 1 sample per pixel (standard ray tracing)
   - Store colors in buffer

2. **Level 1 (First subdivision):**
   - For each pixel, compare to 8 neighbors (Moore neighborhood)
   - If any neighbor differs by > threshold → Subdivide into 3×3 grid
   - Render 9 samples within pixel bounds
   - Check sub-pixels against each other AND against neighboring pixels' sub-pixels

3. **Level 2+ (Recursive subdivision):**
   - Treat each sub-pixel as a new pixel
   - Compare to its 8 neighbors (including cross-pixel boundaries)
   - If difference > threshold AND depth < max_depth → Subdivide again
   - Continue until max_depth or all sub-pixels below threshold

4. **Final averaging:**
   - Average all samples within each original pixel
   - Write final color to output buffer

#### Color Difference Metric

**Euclidean distance in RGB space:**
```cuda
__device__ float colorDifference(float3 c1, float3 c2) {
    float dr = c1.x - c2.x;
    float dg = c1.y - c2.y;
    float db = c1.z - c2.z;
    return sqrtf(dr*dr + dg*dg + db*db);
}

// Normalize to [0, sqrt(3)] range for RGB values in [0,1]
// Threshold of 0.1 means ~6% difference in color
```

#### Data Structures

**Sub-pixel Tree Storage (GPU):**

```cpp
// Each pixel can have up to 3^(2*max_depth) sub-samples
// Level 0: 1 sample
// Level 1: 9 samples
// Level 2: 81 samples
// Level 3: 729 samples

struct SubPixel {
    float3 color;
    unsigned char depth;  // Recursion level
    unsigned char subdivided; // 0 or 1
};

// GPU buffers
SubPixel* d_samples;  // All samples for all pixels (allocated dynamically)
int* d_sample_counts; // Number of samples per pixel (width × height)
```

**Memory estimation:**
- Worst case (all pixels subdivided to max depth 3):
  - 800×600 × 729 samples × 16 bytes = ~5.6 GB
- Typical case (10% pixels subdivided to depth 2):
  - 800×600 × (0.9×1 + 0.1×81) × 16 bytes = ~69 MB
- **Strategy:** Use dynamic allocation and monitor memory usage

#### Implementation Strategy

**Multi-pass rendering:**

**Pass 1: Initial render (1 sample/pixel)**
```cuda
// Standard ray tracing, store in d_samples
```

**Pass 2-N: Recursive subdivision**
```cuda
for (int level = 0; level < max_depth; ++level) {
    // Kernel 1: Detect edges
    detectEdges<<<...>>>(d_samples, d_sample_counts, threshold, level);

    // Kernel 2: Subdivide marked pixels
    subdividePixels<<<...>>>(d_samples, d_sample_counts, level);

    // Kernel 3: Render new samples
    renderSubSamples<<<...>>>(d_samples, level);
}
```

**Pass N+1: Final averaging**
```cuda
averageAndWrite<<<...>>>(d_samples, d_sample_counts, d_output);
```

#### Edge Detection Across Pixel Boundaries

**Challenge:** Sub-pixels at edges need to compare against neighboring pixels' sub-pixels

**Solution:**
- Store samples in a structured grid that allows efficient neighbor lookups
- For a sub-pixel at position `(px, py, sx, sy)`:
  - `px, py` = parent pixel coordinates
  - `sx, sy` = sub-pixel offset within parent (0-2 for 3×3 grid)
- Neighbor lookup function:
  ```cuda
  __device__ float3 getSampleAt(int px, int py, int sx, int sy, int level);
  ```

**Example:** Sub-pixel at edge of pixel (10, 20):
- Position: `(10, 20, 2, 1)` (rightmost column, middle row)
- Right neighbor: `(11, 20, 0, 1)` (leftmost column of next pixel)

#### Jittered Sampling

**For better quality, jitter sample positions within each sub-pixel:**

```cuda
// Instead of sampling at sub-pixel center:
float u = (px + (sx + 0.5f) / 3.0f) / width;
float v = (py + (sy + 0.5f) / 3.0f) / height;

// Sample at random offset within sub-pixel (stratified sampling):
float jitter_x = random(-0.5f / 3.0f, 0.5f / 3.0f);
float jitter_y = random(-0.5f / 3.0f, 0.5f / 3.0f);
float u = (px + (sx + 0.5f + jitter_x) / 3.0f) / width;
float v = (py + (sy + 0.5f + jitter_y) / 3.0f) / height;
```

**Random number generation:** Use OptiX built-in RNG or simple hash function

#### Statistics Integration

**Report AA statistics:**
```
Adaptive Antialiasing Statistics:
  Max depth: 2
  Threshold: 0.10
  Pixels subdivided:
    Depth 0: 480,000 (100.0%) - 480,000 samples
    Depth 1: 124,380 (25.9%) - 1,119,420 samples
    Depth 2: 8,234 (1.7%) - 666,954 samples
  Total samples: 2,266,374 (avg 4.72 per pixel)
  Sample efficiency: 5.2% (vs 81 samples/pixel uniform)
```

#### Files to Modify

- `optix-jni/src/main/native/include/OptiXData.h`
  - Add AA parameters to `Params`:
    ```cpp
    bool aa_enabled;
    int aa_max_depth;
    float aa_threshold;
    ```
  - Add `SubPixel` struct and buffer pointers

- `optix-jni/src/main/native/shaders/sphere_combined.cu`
  - Add edge detection kernel
  - Add subdivision kernel
  - Add sub-sample rendering kernel
  - Add averaging kernel
  - Implement neighbor lookup across pixel boundaries

- `optix-jni/src/main/native/OptiXWrapper.cpp`
  - Allocate/deallocate AA buffers
  - Implement multi-pass rendering loop
  - Add `setAntialiasing(bool enabled, int max_depth, float threshold)` method

- `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala`
  - Add `def setAntialiasing(enabled: Boolean, maxDepth: Int, threshold: Float): Unit`

- `src/main/scala/menger/CommandLineInterface.scala`
  - Parse `--antialiasing`, `--aa-max-depth`, `--aa-threshold`

- `src/main/scala/menger/OptiXResources.scala`
  - Configure AA from CLI options

#### Testing

1. **Visual tests:**
   - Render sphere edges at various angles → No jaggies
   - Render checkered plane → Moiré patterns eliminated
   - Compare with/without AA side-by-side

2. **Threshold tests:**
   - High threshold (0.3) → Less subdivision, some aliasing remains
   - Low threshold (0.05) → More subdivision, smoother edges

3. **Depth tests:**
   - Depth 1 → Some improvement, fast
   - Depth 2 → Significant improvement, moderate performance
   - Depth 3 → Maximum quality, slow

4. **Performance tests:**
   - Measure samples per pixel distribution (histogram)
   - Verify adaptive behavior (more samples on edges, fewer on smooth areas)
   - Compare to uniform super-sampling (should be 5-20x faster for same quality)

5. **Edge cases:**
   - All smooth regions → Minimal overhead (~5%)
   - All high-contrast → Degrades to near-uniform sampling
   - Cross-pixel boundaries → No visible seams or artifacts

6. **Memory tests:**
   - Monitor GPU memory usage at various depths
   - Ensure no memory leaks during repeated renders
   - Worst-case scenario (depth 3, all pixels subdivided) → Handle gracefully or warn user

#### Acceptance Criteria

- ✅ Recursive subdivision up to configurable max_depth
- ✅ 3×3 grid subdivision at each level
- ✅ Edge detection uses Euclidean color distance
- ✅ Sub-pixels compare to neighbors across pixel boundaries
- ✅ CLI flags control enable/max_depth/threshold
- ✅ Statistics report subdivision levels and sample counts
- ✅ Visual quality dramatically improved (no visible aliasing on edges)
- ✅ Performance adaptive (10-50x fewer samples than uniform super-sampling for typical scenes)
- ✅ No artifacts, seams, or discontinuities
- ✅ Memory usage acceptable (<500 MB for 1920×1080 at depth 2)

---

## Sprint 4: Advanced Lighting (Future)

### 4.1 Caustics via Photon Mapping

**Priority:** LOW (Advanced Feature)
**Effort:** 15-25 hours
**Risk:** High

#### Goal
Implement photon mapping to render realistic caustics—the bright patterns formed when light focuses through transparent/refractive objects (e.g., light at the bottom of a swimming pool, bright lines through a glass sphere).

#### Overview

**Caustics** are difficult because they require solving the **inverse light transport problem:**
- Standard ray tracing: Camera → Scene → Light (forward)
- Caustics: Light → Scene → Camera (backward)

**Photon mapping** solves this via two passes:
1. **Photon emission pass:** Trace "photons" from lights into scene, store where they hit
2. **Rendering pass:** Ray tracing with photon map lookups for indirect illumination

#### Algorithm: Two-Pass Photon Mapping

**Pass 1: Photon Emission**

1. **Emit photons from lights:**
   - For each light source, emit N photons in random directions
   - N = 100,000 - 1,000,000 photons (configurable)

2. **Trace photons through scene:**
   - When photon hits sphere:
     - **Refract** through sphere (using Snell's law)
     - Apply Beer-Lambert absorption
     - Continue tracing until photon exits
   - When photon hits plane:
     - **Store photon** in photon map (position, direction, energy, color)
     - Terminate photon

3. **Build photon map:**
   - k-d tree or hash grid for efficient spatial queries
   - ~1-10 MB for 1M photons

**Pass 2: Rendering (Ray Tracing + Photon Map)**

1. **Ray trace as normal** (camera → scene)

2. **When ray hits plane:**
   - **Direct illumination:** Standard lighting calculation (as before)
   - **Indirect illumination (caustics):**
     - Query photon map for k nearest photons (k = 50-200)
     - Estimate radiance from photon density:
       ```
       L_caustic = Σ(photon_energy) / (π * r²)
       ```
       Where r = distance to k-th nearest photon
     - Add to final color

3. **Final color:**
   ```
   color = direct_lighting + caustic_lighting + ambient
   ```

#### CLI Parameters

```bash
--caustics                  # Enable caustic rendering (default: off)
--caustics-photons N        # Number of photons to emit (default: 500000)
--caustics-search-radius R  # Photon search radius (default: 0.1)
--caustics-search-count K   # Number of photons for density estimate (default: 100)
```

#### Data Structures

**Photon:**
```cpp
struct Photon {
    float position[3];  // Where photon hit
    float direction[3]; // Incoming direction
    float energy[3];    // RGB energy/power
};
```

**Photon Map:**
- k-d tree for spatial queries (existing CUDA libraries: nanoflann, CUDA k-d tree)
- Alternative: Hash grid (simpler, potentially faster for dense distributions)

#### Implementation Challenges

1. **GPU k-d tree construction:** Non-trivial, may need to build on CPU and upload to GPU
2. **Photon emission distribution:** Need to emit photons proportional to light intensity/area
3. **Radius estimation:** Fixed radius vs. adaptive (k-nearest neighbor) affects quality
4. **Performance:** Photon map queries add ~30-50% overhead to render time
5. **Quality vs. performance:** More photons = better caustics but slower emission pass

#### Files to Modify

- `optix-jni/src/main/native/include/OptiXData.h`
  - Add `Photon` struct
  - Add caustics parameters to `Params`

- `optix-jni/src/main/native/shaders/sphere_combined.cu`
  - Add photon emission kernel (new OptiX program)
  - Add photon map query in miss program (plane hit)
  - Integrate caustic lighting into final color

- `optix-jni/src/main/native/OptiXWrapper.cpp`
  - Implement two-pass rendering (emission + ray tracing)
  - Build k-d tree or hash grid from photons
  - Add `setCaustics(bool enabled, int num_photons, float search_radius)` method

- `optix-jni/src/main/scala/menger/optix/OptiXRenderer.scala`
  - Add caustics configuration methods

- `src/main/scala/menger/CommandLineInterface.scala`
  - Parse `--caustics` and related parameters

#### Alternative: Simpler Approximations

If full photon mapping is too complex, consider:

1. **Screen-space caustics:** Post-process effect (fast but inaccurate)
2. **Analytic caustics:** Geometric approximation for simple shapes like spheres (faster, limited)
3. **Deferred shading + light volumes:** Project caustic pattern from light's perspective

#### Testing

1. **Visual reference:** Compare to reference renderer (e.g., Blender Cycles, LuxRender)
2. **Caustic patterns:** Verify bright lines/patterns appear on plane beneath sphere
3. **Energy conservation:** Total photon energy should match light source energy
4. **Performance:** Measure photon emission time vs. ray tracing time

#### Acceptance Criteria

- ✅ Caustic patterns visible on plane beneath refractive sphere
- ✅ Patterns geometrically accurate (match reference renderer within 10%)
- ✅ Adjustable photon count and search radius via CLI
- ✅ Performance: Full caustics render <5 seconds for 800×600 @ 500k photons
- ✅ No artifacts (e.g., splotches, banding) with proper photon counts

**Note:** This feature is marked as future work due to high complexity. Recommend completing Sprints 1-3 first and reassessing priority.

---

## Sprint 5: Complex Features (Deferred)

### 5.1 Dynamic Window Resizing

**Priority:** LOW (Deferred)
**Effort:** 10-20 hours (revised from initial 2-3 hour estimate)
**Risk:** HIGH
**Status:** DEFERRED - Requires further investigation

#### Revision History

**Initial estimate:** 2-3 hours (Very Low risk)
**Actual time spent:** 15+ hours with no resolution
**Status change:** 2025-11-13 - Moved to Sprint 5, deprioritized

#### Issue Summary

Despite 15+ hours of investigation and multiple implementation attempts, dynamic window resizing with OptiX renderer remains unresolved. The feature exhibits complex interactions between LibGDX window events, OptiX buffer management, and camera parameter updates that require deeper architectural investigation.

#### Goal

Resize window → automatically re-render at new resolution with adjusted FOV to maintain proper aspect ratio.

#### Known Challenges

1. **Timing Issues:** LibGDX resize events vs OptiX buffer reallocation sequence unclear
2. **FOV Calculation:** Multiple attempted approaches for aspect ratio preservation failed
3. **Buffer Management:** Dynamic GPU buffer allocation during resize may have race conditions
4. **Camera Updates:** Uncertainty about correct camera parameter update sequence
5. **Testing Difficulty:** Interactive testing required; automated tests insufficient to reproduce issue

#### Investigation Required Before Implementation

1. **Root Cause Analysis:**
   - Profile exact sequence of LibGDX resize events
   - Trace OptiX buffer lifecycle during resize
   - Identify where aspect ratio distortion originates (camera vs buffer vs rendering)

2. **Architecture Review:**
   - Evaluate whether current OptiXResources design supports dynamic resizing
   - Consider whether refactoring is needed before feature can work
   - Research how other OptiX applications handle window resizing

3. **Prototyping:**
   - Create minimal reproducible example outside main codebase
   - Test different buffer management strategies in isolation
   - Validate FOV calculation approaches with known-good reference

4. **Alternative Approaches:**
   - Fixed-size OptiX render with letterboxing/pillarboxing
   - Deferred resize (delay OptiX update until resize complete)
   - Separate OptiX window with independent size control

#### Acceptance Criteria (Unchanged)

- ✅ Window resizes smoothly without crashes
- ✅ Image re-renders at new resolution within 1 second
- ✅ Aspect ratio preserved (no distortion)
- ✅ FOV adjusts correctly for wider/taller windows

#### Decision Rationale

After 15+ hours of effort with no resolution, the cost-benefit ratio no longer justifies continued work on this feature. The feature provides quality-of-life improvement but is not critical to core functionality. Priority features (ray statistics, shadows, mouse control, antialiasing) provide more user value per development hour.

**Recommendation:** Defer until Sprints 1-4 complete, then reassess if still needed. If pursued, allocate dedicated investigation time before implementation.

---

## Development Workflow

### For Each Feature

1. **Create feature branch:**
   ```bash
   git checkout -b feature/ray-statistics
   ```

2. **Implement following TDD:**
   - Write tests first (expected behavior)
   - Implement feature
   - Verify tests pass
   - Refactor if needed

3. **Testing checklist:**
   - ✅ Unit tests (Scala + C++)
   - ✅ Integration tests (full render pipeline)
   - ✅ Visual tests (compare output images)
   - ✅ Performance tests (measure impact)

4. **Commit with clear message:**
   ```bash
   git commit -m "feat: Add ray statistics tracking

   - Add RayStats struct to OptiXData.h
   - Implement atomic counters in CUDA shaders
   - Return statistics from render() method
   - Print formatted summary to console

   All tests passing (16 C++ + 29 Scala + 4 new stats tests)."
   ```

5. **Push and create merge request:**
   ```bash
   git push origin feature/ray-statistics
   ```

### Code Review Checklist

Before merging:
- ✅ All tests pass (local + CI)
- ✅ No memory leaks (valgrind + compute-sanitizer)
- ✅ Code follows style guide (scalafix + clang-format)
- ✅ Documentation updated (inline comments + CLAUDE.md if needed)
- ✅ Performance acceptable (benchmarks)
- ✅ Visual quality verified (reference images)

---

## Dependencies and Compatibility

### External Libraries

**Required:**
- CUDA Toolkit 12.0+ (already required)
- OptiX SDK 9.0+ (already required)
- LibGDX (already required)

**Optional (for caustics):**
- k-d tree library (e.g., nanoflann) - Only needed for Sprint 4

### Platform Support

All features target the same platforms as current OptiX JNI:
- Linux x86_64 (primary)
- NVIDIA GPU with OptiX support (RTX 20 series or newer recommended)

### Backward Compatibility

**Guaranteed:**
- Existing CLI arguments work identically
- Existing API methods unchanged (deprecated if needed, not removed)
- Default behavior unchanged (new features opt-in via flags)

**Example:**
```bash
# Old command (still works)
menger --level 2 --sponge-type cube

# New command (adds features)
menger --level 2 --sponge-type cube --shadows --antialiasing --aa-max-depth 2
```

---

## Performance Targets

### Baseline (Current)

- 800×600 rendering: ~150ms (650 FPS)
- 1920×1080 rendering: ~400ms (250 FPS)

### Sprint 1 Targets

| Feature | Overhead | Target Render Time (800×600) |
|---------|----------|------------------------------|
| Statistics | <5% | <160ms |
| Shadows | 20-30% | 180-200ms |

### Sprint 2 Targets

| Feature | Overhead | Target Render Time (800×600) |
|---------|----------|------------------------------|
| Multi-light (4 lights) | 50-100% | 250-350ms |
| Mouse control | <1ms input latency | N/A (interaction) |

### Sprint 3 Targets

| Feature | Scenario | Target Render Time (800×600) |
|---------|----------|------------------------------|
| Adaptive AA | Smooth scene (depth 1) | 200-300ms |
| Adaptive AA | Medium detail (depth 2) | 500-800ms |
| Adaptive AA | High detail (depth 3) | 2-5 seconds |

**Goal:** Adaptive AA should be 10-50× faster than uniform super-sampling for equivalent quality.

---

## Risk Mitigation

### Sprint 1 Risks

**Risk:** Statistics atomic operations slow down rendering
**Mitigation:** Use warp-level intrinsics for faster atomics, profile and optimize

**Risk:** Shadow rays expensive with many plane hits
**Mitigation:** Early ray termination, optimize plane intersection tests

### Sprint 5 Risks

**Risk:** Window resize feature may require architectural refactoring
**Mitigation:** Perform thorough investigation before committing to implementation

**Risk:** Effort may exceed 20 hours if fundamental issues discovered
**Mitigation:** Set hard time-box limit; consider alternative approaches or abandon feature

### Sprint 2 Risks

**Risk:** Multiple lights with shadows causes exponential slowdown
**Mitigation:** Limit max lights to 8, optimize shadow ray logic, use early exits

**Risk:** Camera controller conflicts with existing input handling
**Mitigation:** Use InputMultiplexer to manage priority, test interaction carefully

### Sprint 3 Risks

**Risk:** Recursive AA memory usage exceeds GPU capacity
**Mitigation:** Monitor usage, warn user if depth too high, add --aa-max-samples fallback

**Risk:** Cross-pixel neighbor lookups complex and bug-prone
**Mitigation:** Extensive unit tests, visualize subdivision tree for debugging

### Sprint 4 Risks

**Risk:** Photon mapping too slow or inaccurate
**Mitigation:** Prototype in isolation, benchmark before full integration, consider alternatives

---

## Success Metrics

### Sprint 1

- ✅ All 2 features implemented and tested
- ✅ Statistics provide actionable performance insights
- ✅ Shadows dramatically improve visual realism
- ✅ Performance overhead <30% vs. baseline

### Sprint 2

- ✅ OptiX window fully interactive (orbit, pan, zoom)
- ✅ Multiple lights enable creative setups (tested with 4+ lights)
- ✅ Backward compatibility maintained
- ✅ Input handling doesn't interfere with LibGDX window

### Sprint 3

- ✅ Aliasing eliminated on sphere edges and plane patterns
- ✅ Adaptive behavior verified (10-50× fewer samples than uniform)
- ✅ Configurable depth and threshold work as expected
- ✅ Memory usage acceptable (<500 MB for 1080p depth 2)
- ✅ Visual quality matches or exceeds reference renderers

### Sprint 4

- ✅ Caustic patterns visible and physically plausible
- ✅ Adjustable quality via photon count
- ✅ Render time <5 seconds for typical scenes
- ✅ No major artifacts (verified against reference)

### Sprint 5

- ✅ Root cause identified for window resize issue
- ✅ Prototype validates chosen approach
- ✅ Implementation completed within time-box (10-20 hours)
- ✅ All acceptance criteria met OR decision made to abandon feature

---

## Timeline Estimate

**Optimistic (focused development, Sprints 1-4):**
- Sprint 1: 1 day
- Sprint 2: 2-3 days
- Sprint 3: 2-3 days
- Sprint 4: 3-5 days
- **Total: 8-12 days**

**Realistic (with testing, documentation, iteration, Sprints 1-4):**
- Sprint 1: 1-2 days
- Sprint 2: 3-4 days
- Sprint 3: 4-5 days
- Sprint 4: 5-7 days
- **Total: 13-18 days**

**Buffer for unknowns:** +20% → **16-22 days total (Sprints 1-4)**

**Sprint 5 (if pursued later):**
- Investigation: 2-4 days
- Implementation: 1-2 days (if straightforward) OR 3-8 days (if refactoring needed)
- **Sprint 5 Total: 3-12 days** (highly variable based on findings)

---

## Future Enhancements (Beyond Sprint 4)

1. **Soft shadows:** Area lights and penumbra
2. **Global illumination:** Full path tracing or irradiance caching
3. **Depth of field:** Simulate camera aperture for bokeh effects
4. **Motion blur:** Temporal sampling for moving objects
5. **Volumetric effects:** Fog, participating media
6. **Denoising:** AI-based or spatial-temporal filtering for noisy renders
7. **HDR environment maps:** Image-based lighting for realistic backgrounds
8. **Progressive rendering:** Iterative refinement with live preview

---

## References

### Ray Tracing Theory

- **"Physically Based Rendering" (PBRT)** by Pharr, Jakob, Humphreys
- **"Ray Tracing in One Weekend" series** by Peter Shirley

### OptiX Documentation

- NVIDIA OptiX Programming Guide: https://raytracing-docs.nvidia.com/optix7/guide/index.html
- OptiX API Reference: https://raytracing-docs.nvidia.com/optix7/api/index.html

### Antialiasing Techniques

- **"A Survey of Adaptive Sampling in Computer Graphics"** by Mitchell
- **Recursive grid refinement:** Similar to adaptive mesh refinement (AMR)

### Photon Mapping

- **"Realistic Image Synthesis Using Photon Mapping"** by Henrik Wann Jensen
- **GPU photon mapping papers:** NVIDIA research publications

---

## Appendix: Technical Decisions

### Why 3×3 subdivision?

**Alternatives considered:**
- 2×2 (4 samples): Simpler, but less granular refinement
- 4×4 (16 samples): More granular, but quadratic complexity explosion

**Decision:** 3×3 balances quality and performance:
- Depth 1: 9 samples (manageable)
- Depth 2: 81 samples (good quality)
- Depth 3: 729 samples (extreme detail, rare)
- Depth 4: 6,561 samples (probably overkill)

### Why Euclidean distance for color difference?

**Alternatives:**
- Luminance-only (Y channel): Simpler, but ignores color shifts
- Perceptual (CIEDE2000): More accurate, but computationally expensive

**Decision:** Euclidean is simple, fast, and "good enough" for most cases. Can revisit if needed.

### Why GPU storage for subdivision tree?

**Alternatives:**
- CPU storage: Simpler, but requires frequent host-device transfers (slow)
- Hybrid: Initial pass on GPU, refinement on CPU (complex coordination)

**Decision:** Full GPU storage keeps all data on device, enabling fast iterative refinement. Memory usage is manageable for realistic scenarios.

---

**Document Status:** ✅ **Sprint 1 Complete**

**Last Updated:** 2025-11-16 - Shadow Rays feature complete

**Next Step:** Begin Sprint 2 - Interactivity (Mouse Camera Control, Multiple Light Sources)

**Completed:**
- Feature 1.1 - Ray Statistics ✅
- Feature 1.2 - Shadow Rays ✅
