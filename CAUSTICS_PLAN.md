# Caustics Implementation Plan - Progressive Photon Mapping

**Created:** 2025-11-21
**Status:** 🔄 IN PROGRESS
**Estimated Effort:** 20-25 hours
**Branch:** `feature/caustics`

## Overview

Implement **Progressive Photon Mapping (PPM)** for physically accurate caustics rendering. PPM is chosen over standard photon mapping because:

1. **Unbiased** - Converges to mathematically correct solution
2. **View-dependent** - Only computes caustics visible to camera
3. **Progressive** - Quality improves with each iteration
4. **Memory efficient** - No need to store full photon map

### Target Use Case

High-quality screenshot/offline rendering where quality is prioritized over speed. Users can wait several seconds for sharp, accurate caustics.

---

## Algorithm: Progressive Photon Mapping

### Traditional Two-Pass Photon Mapping (Background)

```
Pass 1: Emit photons from lights → Store in spatial data structure
Pass 2: Ray trace → Query photon map at hit points → Estimate radiance
```

**Problem:** Biased, requires tuning search radius, caustics can be blurry.

### Progressive Photon Mapping (Our Approach)

```
Pass 1 (once): Trace eye rays → Store hit points on diffuse surfaces
Pass 2 (iterate N times):
    - Emit photons from lights
    - For each photon hitting near a hit point: accumulate flux
    - Reduce search radius progressively
Pass 3: Final render using accumulated flux at hit points
```

**Key insight:** Instead of storing photons and querying them, we store *hit points* and let photons find them. This inverts the lookup and enables progressive refinement.

### Mathematical Foundation

**Flux accumulation at hit point:**
```
τ(x, ω) = Σ f_r(x, ω_i, ω) * Φ_p(x_p, ω_p)
```

Where:
- `τ` = accumulated flux
- `f_r` = BRDF at hit point
- `Φ_p` = photon power
- `ω_i` = incoming photon direction
- `ω` = outgoing direction to camera

**Progressive radius reduction:**
```
R_{i+1} = R_i * sqrt((N_i + α * M_i) / (N_i + M_i))
```

Where:
- `R_i` = search radius at iteration i
- `N_i` = accumulated photon count
- `M_i` = new photons this iteration
- `α` = 0.7 (typical, controls convergence rate)

**Radiance estimate:**
```
L(x, ω) = τ(x, ω) / (π * R² * N_total_photons)
```

---

## Phase 1: Data Structures & GPU Memory (3-4h)

### 1.1 New Structs in `OptiXData.h`

```cpp
// Photon emitted from light source
struct Photon {
    float3 position;      // World position where photon lands
    float3 direction;     // Incoming direction (normalized)
    float3 flux;          // RGB energy (watts)
    float pad;            // Alignment
};

// Eye ray hit point on diffuse surface (receives caustics)
struct HitPoint {
    float3 position;      // World position
    float3 normal;        // Surface normal
    float3 flux;          // Accumulated photon flux (RGB)
    float radius;         // Current search radius (shrinks over iterations)
    unsigned int n;       // Number of photons accumulated
    unsigned int pixel_x; // Pixel coordinates for final write
    unsigned int pixel_y;
    float3 weight;        // BRDF weight for this view direction
};

// Parameters for caustics rendering
struct CausticsParams {
    bool enabled;
    int photons_per_iteration;  // Photons to emit each iteration
    int iterations;             // Number of PPM iterations
    float initial_radius;       // Starting search radius
    float alpha;                // Radius reduction factor (0.7 typical)

    // GPU buffers (set by host)
    HitPoint* hit_points;       // Array of hit points
    unsigned int num_hit_points;

    // Hash grid for spatial queries
    unsigned int* grid;         // Cell → first hit point index
    unsigned int* grid_counts;  // Photons per cell
    float3 grid_min;            // Bounding box
    float3 grid_max;
    float cell_size;
    unsigned int grid_resolution; // Typically 256
};
```

### 1.2 Constants in `OptiXConstants.h`

```cpp
// Caustics constants
constexpr int MAX_HIT_POINTS = 2000000;        // Max stored hit points
constexpr int DEFAULT_PHOTONS_PER_ITER = 100000;
constexpr int DEFAULT_ITERATIONS = 10;
constexpr float DEFAULT_INITIAL_RADIUS = 0.1f;
constexpr float DEFAULT_ALPHA = 0.7f;          // Radius reduction factor
constexpr int CAUSTICS_GRID_RESOLUTION = 256;
```

### 1.3 Extend `Params` struct

```cpp
// In Params struct, add:
CausticsParams caustics;
```

---

## Phase 2: Eye Ray Pass - Hit Point Generation (4-5h)

### 2.1 New Raygen Program

Add to `sphere_combined.cu`:

```cuda
extern "C" __global__ void __raygen__hitpoints()
{
    const uint3 idx = optixGetLaunchIndex();
    const uint3 dim = optixGetLaunchDimensions();

    // Generate camera ray (same as __raygen__rg)
    const float2 subpixel_jitter = make_float2(0.5f, 0.5f);
    const float2 d = 2.0f * make_float2(
        (float(idx.x) + subpixel_jitter.x) / float(dim.x),
        (float(idx.y) + subpixel_jitter.y) / float(dim.y)
    ) - 1.0f;

    const RayGenData* rtData = (RayGenData*)optixGetSbtDataPointer();
    float3 ray_origin = rtData->cam_eye;
    float3 ray_direction = normalize(d.x * rtData->cam_u + d.y * rtData->cam_v + rtData->cam_w);

    // Trace ray - but we need to detect DIFFUSE hits (plane), not specular (sphere)
    unsigned int p0, p1, p2, p3;
    p0 = __float_as_uint(0.0f); // hit_type: 0=miss, 1=diffuse, 2=specular
    p1 = p2 = p3 = 0;

    optixTrace(
        params.handle,
        ray_origin,
        ray_direction,
        0.0001f,                    // tmin
        1e16f,                      // tmax
        0.0f,                       // rayTime
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        0, 1, 0,                    // SBT offset, stride, miss index
        p0, p1, p2, p3
    );

    float hit_type = __uint_as_float(p0);

    if (hit_type == 1.0f) {  // Diffuse hit (plane)
        // Store hit point for caustics gathering
        float3 hit_pos = make_float3(__uint_as_float(p1), __uint_as_float(p2), __uint_as_float(p3));

        // Atomically allocate hit point slot
        unsigned int hit_idx = atomicAdd(&params.caustics.num_hit_points, 1);

        if (hit_idx < MAX_HIT_POINTS) {
            HitPoint& hp = params.caustics.hit_points[hit_idx];
            hp.position = hit_pos;
            hp.normal = make_float3(0.0f, 1.0f, 0.0f);  // Plane normal (Y-up)
            hp.flux = make_float3(0.0f);
            hp.radius = params.caustics.initial_radius;
            hp.n = 0;
            hp.pixel_x = idx.x;
            hp.pixel_y = idx.y;
            hp.weight = make_float3(1.0f);  // Lambertian BRDF = 1/π (normalized later)
        }
    }
}
```

### 2.2 Modify Closest Hit to Report Hit Type

```cuda
// In __closesthit__ch, add payload for hit type reporting
// When we hit the sphere (specular): set payload p0 = 2.0
// When we hit the plane (diffuse): set payload p0 = 1.0, plus position
```

### 2.3 Build Hash Grid for Hit Points

After generating hit points, build spatial hash grid on GPU:

```cuda
extern "C" __global__ void __build_hitpoint_grid()
{
    const unsigned int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx >= params.caustics.num_hit_points) return;

    HitPoint& hp = params.caustics.hit_points[idx];

    // Compute grid cell
    float3 rel = hp.position - params.caustics.grid_min;
    uint3 cell = make_uint3(
        min((unsigned int)(rel.x / params.caustics.cell_size), params.caustics.grid_resolution - 1),
        min((unsigned int)(rel.y / params.caustics.cell_size), params.caustics.grid_resolution - 1),
        min((unsigned int)(rel.z / params.caustics.cell_size), params.caustics.grid_resolution - 1)
    );

    unsigned int cell_idx = cell.x + cell.y * params.caustics.grid_resolution +
                           cell.z * params.caustics.grid_resolution * params.caustics.grid_resolution;

    // Atomic increment cell count (for sorting later)
    atomicAdd(&params.caustics.grid_counts[cell_idx], 1);
}
```

---

## Phase 3: Photon Tracing Pass (5-6h)

### 3.1 Photon Emission Raygen Program

```cuda
extern "C" __global__ void __raygen__photons()
{
    const uint3 idx = optixGetLaunchIndex();
    const uint3 dim = optixGetLaunchDimensions();

    // Each thread traces one photon
    unsigned int photon_idx = idx.x + idx.y * dim.x;

    // Initialize RNG with photon index + iteration for different patterns each iteration
    unsigned int seed = tea<4>(photon_idx, params.caustics.current_iteration);

    // Select light source (weighted by intensity)
    int light_idx = selectLight(seed);
    Light& light = params.lights[light_idx];

    // Generate photon direction
    float3 photon_dir;
    float3 photon_origin;
    float3 photon_flux;

    if (light.type == LIGHT_TYPE_DIRECTIONAL) {
        // Parallel rays from "infinity" - sample disk perpendicular to light direction
        float2 disk = sampleDisk(rnd(seed), rnd(seed));
        float3 tangent, bitangent;
        createONB(light.direction, tangent, bitangent);

        photon_origin = params.sphere_center + tangent * disk.x * 10.0f + bitangent * disk.y * 10.0f
                       - light.direction * 100.0f;  // Start far back
        photon_dir = normalize(make_float3(light.direction[0], light.direction[1], light.direction[2]));
        photon_flux = make_float3(light.color[0], light.color[1], light.color[2]) * light.intensity;
    } else {
        // Point light - emit in random direction
        photon_dir = sampleSphere(rnd(seed), rnd(seed));
        photon_origin = make_float3(light.position[0], light.position[1], light.position[2]);
        photon_flux = make_float3(light.color[0], light.color[1], light.color[2]) * light.intensity;
    }

    // Normalize flux by number of photons
    photon_flux /= float(dim.x * dim.y);

    // Trace photon through scene
    tracePhoton(photon_origin, photon_dir, photon_flux, seed, MAX_PHOTON_BOUNCES);
}

__device__ void tracePhoton(float3 origin, float3 dir, float3 flux, unsigned int& seed, int max_bounces)
{
    for (int bounce = 0; bounce < max_bounces; ++bounce) {
        // Trace ray
        unsigned int p0, p1, p2, p3;
        optixTrace(
            params.handle,
            origin, dir,
            0.001f, 1e16f, 0.0f,
            OptixVisibilityMask(255),
            OPTIX_RAY_FLAG_NONE,
            PHOTON_RAY_TYPE, 1, PHOTON_RAY_TYPE,
            p0, p1, p2, p3
        );

        float hit_type = __uint_as_float(p0);

        if (hit_type == 0.0f) {
            // Miss - photon escaped
            return;
        }
        else if (hit_type == 1.0f) {
            // Hit diffuse surface (plane) - deposit photon at nearby hit points
            float3 hit_pos = make_float3(__uint_as_float(p1), __uint_as_float(p2), __uint_as_float(p3));
            depositPhoton(hit_pos, dir, flux);
            return;  // Photon absorbed by diffuse surface
        }
        else if (hit_type == 2.0f) {
            // Hit specular surface (sphere) - refract and continue
            float3 hit_pos = make_float3(__uint_as_float(p1), __uint_as_float(p2), __uint_as_float(p3));
            float3 normal = computeSphereNormal(hit_pos);

            // Apply Beer-Lambert absorption
            // (Reuse existing code from __closesthit__ch)
            float3 attenuation = exp(-params.sphere_color * distance);
            flux *= attenuation;

            // Refract using Snell's law
            float3 refracted;
            if (refract(dir, normal, params.sphere_ior, refracted)) {
                origin = hit_pos + refracted * 0.001f;
                dir = refracted;
            } else {
                // Total internal reflection
                dir = reflect(dir, normal);
                origin = hit_pos + dir * 0.001f;
            }
        }
    }
}
```

### 3.2 Photon Deposition

```cuda
__device__ void depositPhoton(float3 photon_pos, float3 photon_dir, float3 flux)
{
    // Query hash grid for nearby hit points
    uint3 cell = getGridCell(photon_pos);

    // Check 27 neighboring cells (3x3x3)
    for (int dz = -1; dz <= 1; ++dz) {
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -1; dx <= 1; ++dx) {
                uint3 neighbor = make_uint3(
                    clamp((int)cell.x + dx, 0, (int)params.caustics.grid_resolution - 1),
                    clamp((int)cell.y + dy, 0, (int)params.caustics.grid_resolution - 1),
                    clamp((int)cell.z + dz, 0, (int)params.caustics.grid_resolution - 1)
                );

                unsigned int cell_idx = neighbor.x + neighbor.y * params.caustics.grid_resolution +
                                       neighbor.z * params.caustics.grid_resolution * params.caustics.grid_resolution;

                // Iterate hit points in this cell
                unsigned int start = params.caustics.grid[cell_idx];
                unsigned int count = params.caustics.grid_counts[cell_idx];

                for (unsigned int i = start; i < start + count; ++i) {
                    HitPoint& hp = params.caustics.hit_points[i];

                    float dist = length(photon_pos - hp.position);
                    if (dist < hp.radius) {
                        // Photon contributes to this hit point
                        // Weight by cosine of angle between photon direction and normal
                        float cos_theta = max(0.0f, dot(-photon_dir, hp.normal));

                        // Atomic add to flux accumulator
                        atomicAdd(&hp.flux.x, flux.x * cos_theta);
                        atomicAdd(&hp.flux.y, flux.y * cos_theta);
                        atomicAdd(&hp.flux.z, flux.z * cos_theta);
                        atomicAdd(&hp.n, 1);
                    }
                }
            }
        }
    }
}
```

---

## Phase 4: Progressive Refinement & Integration (4-5h)

### 4.1 PPM Iteration in `OptiXWrapper.cpp`

```cpp
void OptiXWrapper::renderWithCaustics(int width, int height, unsigned char* output, int iterations) {
    // Phase 1: Generate hit points
    launchHitPointGeneration(width, height);
    buildHitPointGrid();

    // Phase 2: Progressive photon mapping iterations
    for (int iter = 0; iter < iterations; ++iter) {
        impl->params.caustics.current_iteration = iter;

        // Emit and trace photons
        launchPhotonTracing(impl->params.caustics.photons_per_iteration);

        // Update search radii (progressive refinement)
        launchRadiusUpdate();
    }

    // Phase 3: Final render with caustics
    launchFinalRender(width, height, output);
}

void OptiXWrapper::launchRadiusUpdate() {
    // CUDA kernel to update hit point radii based on PPM formula
    // R_new = R * sqrt((N + α*M) / (N + M))
    dim3 block(256);
    dim3 grid((impl->params.caustics.num_hit_points + 255) / 256);
    updateRadiiKernel<<<grid, block>>>(impl->d_caustics_params);
}
```

### 4.2 Radius Update Kernel

```cuda
extern "C" __global__ void updateRadiiKernel(CausticsParams* caustics)
{
    unsigned int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx >= caustics->num_hit_points) return;

    HitPoint& hp = caustics->hit_points[idx];

    if (hp.n > 0) {
        // PPM radius reduction formula
        float alpha = caustics->alpha;
        float new_n = hp.n + alpha * hp.new_photons_this_iter;
        float ratio = new_n / (hp.n + hp.new_photons_this_iter);

        hp.radius *= sqrtf(ratio);
        hp.n = (unsigned int)new_n;

        // Scale flux to account for reduced radius
        hp.flux *= ratio;
    }

    hp.new_photons_this_iter = 0;  // Reset for next iteration
}
```

### 4.3 Final Render Integration

Modify `__raygen__rg` to blend caustics:

```cuda
// In final color calculation, after direct lighting:
if (params.caustics.enabled && hit_type == DIFFUSE_HIT) {
    // Look up accumulated flux at this pixel's hit point
    unsigned int hp_idx = findHitPointForPixel(idx.x, idx.y);
    if (hp_idx != INVALID_INDEX) {
        HitPoint& hp = params.caustics.hit_points[hp_idx];

        // Compute radiance estimate
        float area = M_PIf * hp.radius * hp.radius;
        float3 caustic_radiance = hp.flux / (area * total_photons_traced);

        // Add to final color (caustics add to, not replace, direct lighting)
        final_color += caustic_radiance;
    }
}
```

---

## Phase 5: Scala/CLI Integration & Testing (4-5h)

### 5.1 JNI Bindings

Add to `JNIBindings.cpp`:

```cpp
JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setCaustics(
    JNIEnv* env, jobject obj,
    jboolean enabled, jint photonsPerIter, jint iterations, jfloat initialRadius)
{
    OptiXWrapper* wrapper = getWrapper(env, obj);
    if (wrapper) {
        wrapper->setCaustics(enabled, photonsPerIter, iterations, initialRadius);
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_renderWithCausticsNative(
    JNIEnv* env, jobject obj, jint width, jint height, jbyteArray output, jint iterations)
{
    // ... implementation
}
```

### 5.2 Scala Interface

Add to `OptiXRenderer.scala`:

```scala
@native def setCaustics(enabled: Boolean, photonsPerIter: Int, iterations: Int, initialRadius: Float): Unit

def renderWithCaustics(width: Int, height: Int, iterations: Int): RenderResult = {
  val output = new Array[Byte](width * height * 4)
  renderWithCausticsNative(width, height, output, iterations)
  // ... return RenderResult
}
```

### 5.3 CLI Options

Add to `MengerCLIOptions.scala`:

```scala
val caustics: ScallopOption[Boolean] = opt[Boolean](
  required = false,
  default = Some(false),
  descr = "Enable caustic rendering via progressive photon mapping (OptiX only)"
)

val causticsPhotons: ScallopOption[Int] = opt[Int](
  required = false,
  default = Some(100000),
  descr = "Photons per iteration for caustics (default: 100000)"
)

val causticsIterations: ScallopOption[Int] = opt[Int](
  required = false,
  default = Some(10),
  descr = "Number of PPM iterations for caustics (default: 10)"
)

val causticsRadius: ScallopOption[Float] = opt[Float](
  required = false,
  default = Some(0.1f),
  descr = "Initial photon gather radius (default: 0.1)"
)

// Validations
validateOpt(caustics, optix) { (c, ox) =>
  if c.getOrElse(false) && !ox.getOrElse(false) then
    Left("--caustics flag requires --optix flag")
  else Right(())
}
```

### 5.4 Tests

Create `optix-jni/src/test/scala/menger/optix/CausticsTest.scala`:

```scala
class CausticsTest extends AnyFlatSpec with Matchers with RendererFixture:

  "Caustics" should "produce bright patterns on plane beneath refractive sphere" in:
    // Setup: Glass sphere above plane, directional light from above
    TestScenario.default()
      .withSphereColor(Color.fromRGBA(0.9f, 0.9f, 1.0f, 0.1f))  // Mostly transparent
      .withSphereIOR(1.5f)  // Glass
      .applyTo(renderer)

    renderer.setCaustics(true, 100000, 10, 0.1f)

    // Render with caustics
    val withCaustics = renderer.renderWithCaustics(800, 600, 10)

    // Render without caustics (baseline)
    renderer.setCaustics(false, 0, 0, 0)
    val withoutCaustics = renderer.render(800, 600)

    // Caustic image should have brighter regions on plane beneath sphere
    val causticBrightness = measureBrightness(withCaustics.image, Region.bottomCenter)
    val baselineBrightness = measureBrightness(withoutCaustics.image, Region.bottomCenter)

    causticBrightness should be > baselineBrightness * 1.1  // At least 10% brighter

  it should "produce sharper caustics with more iterations" in:
    // Compare iteration counts
    val lowIter = renderer.renderWithCaustics(800, 600, 2)
    val highIter = renderer.renderWithCaustics(800, 600, 20)

    // Higher iterations should have lower variance (sharper patterns)
    val lowVariance = measureVariance(lowIter.image, Region.bottomCenter)
    val highVariance = measureVariance(highIter.image, Region.bottomCenter)

    // Note: This is subtle - may need adjustment based on actual behavior

  it should "respect the enabled flag" in:
    renderer.setCaustics(false, 100000, 10, 0.1f)
    val noCaustics = renderer.render(800, 600)

    renderer.setCaustics(true, 100000, 10, 0.1f)
    val withCaustics = renderer.renderWithCaustics(800, 600, 10)

    // Images should differ
    noCaustics.image should not equal withCaustics.image
```

---

## Performance Targets

| Metric | Target |
|--------|--------|
| Hit point generation | < 50ms for 800x600 |
| Per-iteration photon tracing | < 200ms @ 100k photons |
| Total render (10 iter, 100k) | < 5 seconds |
| Memory usage | < 200MB for hit points + grid |

---

## Risk Mitigation

### 1. Hash Grid Performance
**Risk:** Grid lookup too slow
**Mitigation:** Tune cell size to average 10-50 hit points per cell; use sorted arrays within cells

### 2. Numerical Precision
**Risk:** Flux accumulation overflow
**Mitigation:** Use double precision for flux; normalize by photon count each iteration

### 3. Caustic Visibility
**Risk:** Caustics too faint to see
**Mitigation:** Add `--caustics-intensity` multiplier for artistic control

### 4. Memory Exhaustion
**Risk:** Too many hit points
**Mitigation:** Limit to MAX_HIT_POINTS; sample hit points if exceeded

---

## Acceptance Criteria

- [ ] Caustic patterns visible on plane beneath refractive sphere
- [ ] Caustics sharpen with more iterations (visual convergence)
- [ ] `--caustics` CLI flag works with validation
- [ ] `--caustics-photons`, `--caustics-iterations`, `--caustics-radius` configurable
- [ ] Performance: 10 iterations @ 100k photons < 30 seconds (800x600)
- [ ] All existing 897+ tests still pass
- [ ] No visual artifacts (splotches, banding) at high iteration counts
- [ ] Memory usage < 500MB for 1920x1080 renders

---

## References

- Henrik Wann Jensen, "Realistic Image Synthesis Using Photon Mapping" (2001)
- Hachisuka et al., "Progressive Photon Mapping" (SIGGRAPH Asia 2008)
- NVIDIA OptiX Programming Guide, Section on custom programs
- Existing implementation: `sphere_combined.cu` lines 684-944 (refraction code)
