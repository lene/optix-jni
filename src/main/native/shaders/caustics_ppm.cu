//==============================================================================
// Progressive Photon Mapping (PPM) for Caustics
//==============================================================================
//
// This file implements Progressive Photon Mapping for rendering caustics
// (focused light patterns created by refraction through transparent objects).
//
// PPM Algorithm Overview (4 phases per iteration):
//
// Phase 1 - Hit Point Generation (__raygen__hitpoints):
//   Trace camera rays and record positions where they hit diffuse surfaces.
//   These "hit points" will accumulate photon energy over multiple iterations.
//
// Phase 2 - Grid Building (__caustics_count_grid_cells):
//   Build spatial hash grid for efficient photon-to-hitpoint lookups.
//   (Currently uses brute-force; grid is prepared for future optimization)
//
// Phase 3 - Photon Tracing (__raygen__photons):
//   Emit photons from light sources, trace through scene geometry.
//   When photons hit the glass sphere, apply Snell's law refraction and
//   Beer-Lambert absorption. When photons hit the diffuse plane, deposit
//   their energy at nearby hit points (depositPhoton).
//
// Phase 4 - Radiance Computation (__raygen__caustics_radiance, __raygen__update_radii):
//   Convert accumulated flux to radiance: L = Flux / (pi * R^2)
//   Progressively reduce search radius using PPM formula:
//     R_new = R_old * sqrt((N + alpha*M) / (N + M))
//   This improves estimate quality over iterations.
//
// Key Physics:
//   - Snell's Law: n1*sin(theta1) = n2*sin(theta2) for refraction angles
//   - Beer-Lambert: I = I0 * exp(-extinction * distance) for absorption
//   - Total Internal Reflection: when sin(theta_t) > 1, light reflects
//
//==============================================================================

//==============================================================================
// Progressive Photon Mapping - Helper Functions
//==============================================================================

/**
 * Create orthonormal basis from a single vector.
 * Used to generate tangent space for sampling around a direction.
 *
 * @param n Input normal vector (must be normalized)
 * @param t Output tangent vector
 * @param b Output bitangent vector
 */
__device__ void createONB(const float3& n, float3& t, float3& b) {
    if (fabsf(n.x) > fabsf(n.z)) {
        t = make_float3(-n.y, n.x, 0.0f);
    } else {
        t = make_float3(0.0f, -n.z, n.y);
    }
    t = normalize(t);
    b = cross(n, t);
}

/**
 * Sample a point on a unit disk using concentric mapping.
 *
 * @param u1 Random value [0, 1)
 * @param u2 Random value [0, 1)
 * @return 2D point on unit disk
 */
__device__ float2 sampleDisk(float u1, float u2) {
    const float r = sqrtf(u1);
    const float theta = 2.0f * M_PI * u2;
    return make_float2(r * cosf(theta), r * sinf(theta));
}

/**
 * Sample a direction on a unit sphere uniformly.
 *
 * @param u1 Random value [0, 1)
 * @param u2 Random value [0, 1)
 * @return Unit direction vector
 */
__device__ float3 sampleSphere(float u1, float u2) {
    const float z = 1.0f - 2.0f * u1;  // z in [-1, 1]
    const float r = sqrtf(fmaxf(0.0f, 1.0f - z * z));
    const float phi = 2.0f * M_PI * u2;
    return make_float3(r * cosf(phi), r * sinf(phi), z);
}

/**
 * Compute grid cell index for a given position in the caustics spatial hash grid.
 *
 * @param pos World position to hash
 * @return Grid cell coordinates (clamped to grid bounds)
 */
__device__ uint3 getCausticsGridCell(const float3& pos) {
    const float3 grid_min = make_float3(
        params.caustics.grid_min[0],
        params.caustics.grid_min[1],
        params.caustics.grid_min[2]
    );
    const float cell_size = params.caustics.cell_size;
    const unsigned int grid_res = params.caustics.grid_resolution;

    const float3 rel = pos - grid_min;
    return make_uint3(
        min(static_cast<unsigned int>(rel.x / cell_size), grid_res - 1),
        min(static_cast<unsigned int>(rel.y / cell_size), grid_res - 1),
        min(static_cast<unsigned int>(rel.z / cell_size), grid_res - 1)
    );
}

/**
 * Convert 3D grid cell coordinates to linear index.
 */
__device__ unsigned int getCausticsGridIndex(const uint3& cell) {
    const unsigned int grid_res = params.caustics.grid_resolution;
    return cell.x + cell.y * grid_res + cell.z * grid_res * grid_res;
}

/**
 * Simple hash-based pseudo-random number generator (TEA algorithm).
 * Used for photon emission randomization.
 */
__device__ unsigned int tea(unsigned int val0, unsigned int val1) {
    unsigned int v0 = val0;
    unsigned int v1 = val1;
    unsigned int s0 = 0;

    for (unsigned int n = 0; n < 4; ++n) {
        s0 += 0x9e3779b9;
        v0 += ((v1 << 4) + 0xa341316c) ^ (v1 + s0) ^ ((v1 >> 5) + 0xc8013ea4);
        v1 += ((v0 << 4) + 0xad90777d) ^ (v0 + s0) ^ ((v0 >> 5) + 0x7e95761e);
    }
    return v0;
}

/**
 * Generate random float in [0, 1) from seed.
 */
__device__ float rnd(unsigned int& seed) {
    seed = tea(seed, seed);
    return static_cast<float>(seed) / static_cast<float>(0xFFFFFFFFu);
}

//==============================================================================
// Progressive Photon Mapping - Hit Point Generation Helpers
//==============================================================================

/**
 * Generate camera ray from pixel coordinates.
 */
__device__ float3 generateCameraRay(
    const uint3& idx,
    const uint3& dim,
    const RayGenData* rt_data,
    float3& cam_eye
) {
    // Screen convention: idx.y=0 is top, so flip to get v=+1 at top, v=-1 at bottom
    const float u = (static_cast<float>(idx.x) + RenderingConstants::PIXEL_CENTER_OFFSET) / static_cast<float>(dim.x) * RenderingConstants::NDC_SCALE - RenderingConstants::NDC_OFFSET;
    const float v = RenderingConstants::NDC_OFFSET - (static_cast<float>(idx.y) + RenderingConstants::PIXEL_CENTER_OFFSET) / static_cast<float>(dim.y) * RenderingConstants::NDC_SCALE;
    const float2 d = make_float2(u, v);

    cam_eye = make_float3(rt_data->cam_eye[0], rt_data->cam_eye[1], rt_data->cam_eye[2]);
    const float3 cam_u = make_float3(rt_data->camera_u[0], rt_data->camera_u[1], rt_data->camera_u[2]);
    const float3 cam_v = make_float3(rt_data->camera_v[0], rt_data->camera_v[1], rt_data->camera_v[2]);
    const float3 cam_w = make_float3(rt_data->camera_w[0], rt_data->camera_w[1], rt_data->camera_w[2]);

    return normalize(d.x * cam_u + d.y * cam_v + cam_w);
}

/**
 * Get plane normal based on axis.
 */
__device__ void getPlaneNormalComponents(
    int plane_axis,
    float& nx,
    float& ny,
    float& nz
) {
    nx = (plane_axis == 0) ? 1.0f : 0.0f;
    ny = (plane_axis == 1) ? 1.0f : 0.0f;
    nz = (plane_axis == 2) ? 1.0f : 0.0f;
}

/**
 * Initialize a hit point at the given location.
 */
__device__ void initializeHitPoint(
    HitPoint& hp,
    const float3& position,
    int plane_axis,
    const uint3& idx
) {
    hp.position[0] = position.x;
    hp.position[1] = position.y;
    hp.position[2] = position.z;

    // Plane normal based on axis
    getPlaneNormalComponents(plane_axis, hp.normal[0], hp.normal[1], hp.normal[2]);

    // Initialize flux accumulator
    hp.flux[0] = 0.0f;
    hp.flux[1] = 0.0f;
    hp.flux[2] = 0.0f;

    // Initial search radius
    hp.radius = params.caustics.initial_radius;
    hp.n = 0;
    hp.new_photons = 0;

    // Store pixel coordinates for final write
    hp.pixel_x = idx.x;
    hp.pixel_y = idx.y;

    // P5: capture the diffuse albedo ρ of the floor into the hit-point weight, so the
    // radiance estimate can apply the Lambertian ρ/π factor. planes[0].color1 is the solid
    // (or checker-A) reflectance; a position-dependent checker albedo is a later refinement.
    hp.weight[0] = params.planes[0].color1[0];
    hp.weight[1] = params.planes[0].color1[1];
    hp.weight[2] = params.planes[0].color1[2];
}

//==============================================================================
// Progressive Photon Mapping - Hit Point Generation (Phase 2)
//==============================================================================

/**
 * Ray generation program for collecting hit points on diffuse surfaces.
 *
 * This is Phase 1 of Progressive Photon Mapping:
 * - Trace primary rays from camera
 * - When ray hits a diffuse surface (plane), store hit point
 * - Hit points will later receive photon contributions
 *
 * Note: This program is launched separately from the main render pass.
 */
extern "C" __global__ void __raygen__hitpoints() {
    const uint3 idx = optixGetLaunchIndex();
    const uint3 dim = optixGetLaunchDimensions();

    // Get camera data from SBT
    const RayGenData* rt_data = reinterpret_cast<RayGenData*>(optixGetSbtDataPointer());

    // Generate camera ray
    float3 cam_eye;
    const float3 ray_direction = generateCameraRay(idx, dim, rt_data, cam_eye);

    // Trace ray to find first hit
    unsigned int p0 = 0, p1 = 0, p2 = 0, p3 = 0;
    optixTrace(
        params.handle,
        cam_eye,
        ray_direction,
        HIT_POINT_RAY_TMIN,          // tmin
        MAX_RAY_DISTANCE,            // tmax
        0.0f,                        // rayTime
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        params.sbt_base_offset + SBTConstants::RAY_TYPE_PRIMARY,  // SBT offset (primary ray type)
        SBTConstants::STRIDE_RAY_TYPES,                           // SBT stride
        SBTConstants::MISS_PRIMARY,                               // missSBTIndex
        p0, p1, p2, p3
    );

    // Re-compute plane intersection to determine if we have a diffuse hit
    // TODO: Multi-plane caustics: currently only planes[0] is used for caustic
    // hit point collection and deposition. Supporting additional planes would
    // require looping over all active planes and collecting/depositing on each.
    if (params.num_planes > 0 && params.planes[0].enabled) {
        const int plane_axis = params.planes[0].axis;
        const float plane_value = params.planes[0].value;

        float ray_orig_comp, ray_dir_comp;
        getRayPlaneComponents(cam_eye, ray_direction, plane_axis, ray_orig_comp, ray_dir_comp);

        // Check for plane intersection
        if (fabsf(ray_dir_comp) > RAY_PARALLEL_THRESHOLD) {
            const float t_plane = (plane_value - ray_orig_comp) / ray_dir_comp;

            if (t_plane > 0.0f) {
                const float3 plane_hit = cam_eye + ray_direction * t_plane;

                // Allocate hit point slot atomically
                const unsigned int hp_idx = atomicAdd(params.caustics.num_hit_points, 1);

                if (hp_idx < MAX_HIT_POINTS) {
                    HitPoint& hp = params.caustics.hit_points[hp_idx];
                    initializeHitPoint(hp, plane_hit, plane_axis, idx);
                }
            }
        }
    }
}

//==============================================================================
// Progressive Photon Mapping - Grid Building Kernel
//==============================================================================

/**
 * OptiX raygen program to count hit points per grid cell.
 * Called after hit point generation to build spatial hash grid.
 * Launch with dimensions = (num_hit_points, 1).
 */
extern "C" __global__ void __raygen__grid_count() {
    const unsigned int idx = optixGetLaunchIndex().x;
    if (idx >= *params.caustics.num_hit_points) return;

    const HitPoint& hp = params.caustics.hit_points[idx];
    const float3 pos = make_float3(hp.position[0], hp.position[1], hp.position[2]);

    const uint3 cell = getCausticsGridCell(pos);
    const unsigned int cell_idx = getCausticsGridIndex(cell);

    atomicAdd(&params.caustics.grid_counts[cell_idx], 1);
}

/**
 * OptiX raygen program to scatter hit point indices into grid-sorted order.
 * After grid_count fills grid_counts and host computes prefix sum (grid_offsets),
 * this kernel places each hit point's index into the sorted grid array.
 * grid_counts is zeroed before this pass and refilled during scatter.
 * Launch with dimensions = (num_hit_points, 1).
 */
extern "C" __global__ void __raygen__grid_scatter() {
    const unsigned int idx = optixGetLaunchIndex().x;
    if (idx >= *params.caustics.num_hit_points) return;

    const HitPoint& hp = params.caustics.hit_points[idx];
    const float3 pos = make_float3(hp.position[0], hp.position[1], hp.position[2]);

    const uint3 cell = getCausticsGridCell(pos);
    const unsigned int cell_idx = getCausticsGridIndex(cell);

    // Atomically claim a slot within this cell's range
    const unsigned int slot = atomicAdd(&params.caustics.grid_counts[cell_idx], 1);
    const unsigned int sorted_pos = params.caustics.grid_offsets[cell_idx] + slot;

    // Store hit point index in sorted position
    params.caustics.grid[sorted_pos] = idx;
}

//==============================================================================
// Progressive Photon Mapping - Photon Deposition (Phase 3)
//==============================================================================

/**
 * Deposit photon energy at nearby hit points using the spatial hash grid.
 *
 * For each hit point within the photon's influence radius:
 * - Accumulate flux weighted by cosine of angle between photon direction and surface normal
 * - Increment photon counter for radius reduction
 *
 * @param photon_pos Position where photon hit diffuse surface
 * @param photon_dir Incoming direction of photon (normalized)
 * @param flux RGB energy carried by photon
 */
__device__ void depositPhoton(const float3& photon_pos, const float3& photon_dir, const float3& flux) {
    // Grid-accelerated search: check only hit points in 3x3x3 cell neighborhood
    const uint3 cell = getCausticsGridCell(photon_pos);
    const int grid_res = static_cast<int>(params.caustics.grid_resolution);

    for (int dz = -1; dz <= 1; ++dz) {
        for (int dy = -1; dy <= 1; ++dy) {
            for (int dx = -1; dx <= 1; ++dx) {
                const int nx = min(max(static_cast<int>(cell.x) + dx, 0), grid_res - 1);
                const int ny = min(max(static_cast<int>(cell.y) + dy, 0), grid_res - 1);
                const int nz = min(max(static_cast<int>(cell.z) + dz, 0), grid_res - 1);
                const uint3 neighbor = make_uint3(nx, ny, nz);
                const unsigned int ci = getCausticsGridIndex(neighbor);
                const unsigned int start = params.caustics.grid_offsets[ci];
                const unsigned int count = params.caustics.grid_counts[ci];

                for (unsigned int i = start; i < start + count; ++i) {
                    const unsigned int hp_idx = params.caustics.grid[i];
                    HitPoint& hp = params.caustics.hit_points[hp_idx];

                    // Compute distance from photon to hit point
                    const float3 hp_pos = make_float3(hp.position[0], hp.position[1], hp.position[2]);
                    const float3 diff = photon_pos - hp_pos;
                    const float dist_sq = dot(diff, diff);
                    const float radius_sq = hp.radius * hp.radius;

                    // P5: uniform-disk density estimate (pbrt-matching). A photon within the
                    // gather radius contributes its full flux Φ — no cosθ weight (the photon
                    // already carries the correct radiant power; the Lambertian ρ/π factor is
                    // applied once in the radiance kernel, not per photon) and no unnormalized
                    // Gaussian (which undercounted by ~8×). The disk area normalization π r²
                    // lives in the radiance kernel.
                    if (dist_sq < radius_sq) {
                        atomicAdd(&hp.flux[0], flux.x);
                        atomicAdd(&hp.flux[1], flux.y);
                        atomicAdd(&hp.flux[2], flux.z);

                        // Count photons for this hit point (this iteration)
                        atomicAdd(&hp.new_photons, 1);

                        // C4: Track deposition statistics
                        if (params.caustics.stats) {
                            atomicAdd(&params.caustics.stats->photons_deposited, 1ULL);
                            const double deposited_flux =
                                static_cast<double>(flux.x + flux.y + flux.z);
                            atomicAdd(&params.caustics.stats->total_flux_deposited, deposited_flux);
                        }
                    }
                }
            }
        }
    }
}

//==============================================================================
// Progressive Photon Mapping - Photon Tracing Helpers (Phase 3)
//==============================================================================

// intersectSphere() removed — photon tracing now uses optixTrace(RAY_TYPE_PHOTON)

/**
 * Apply Beer-Lambert absorption to photon flux when exiting refractive geometry.
 *
 * Beer-Lambert Law: I(d) = I₀ · exp(-α · d)
 * Where α is derived from glass color and opacity.
 *
 * @param flux Photon flux (modified in place)
 * @param distance Distance traveled through the medium
 * @param entering True if photon is entering the geometry (no absorption)
 * @param glass_color RGBA material color array
 * @param glass_alpha Material alpha (opacity)
 * @param glass_scale Physical scale factor for absorption distance
 */
__device__ void applyPhotonBeerLambert(float3& flux, float distance, bool entering,
                                        const float* glass_color, float glass_alpha, float glass_scale) {
    if (entering) return;  // No absorption when entering

    const float3 color = make_float3(glass_color[0], glass_color[1], glass_color[2]);

    const float absorption_scale = BEER_LAMBERT_ABSORPTION_SCALE;
    const float3 extinction = make_float3(
        -logf(fmaxf(color.x, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
        -logf(fmaxf(color.y, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
        -logf(fmaxf(color.z, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale
    );

    const float3 attenuation = make_float3(
        expf(-extinction.x * distance * glass_scale),
        expf(-extinction.y * distance * glass_scale),
        expf(-extinction.z * distance * glass_scale)
    );

    // Track absorbed flux in statistics
    if (params.caustics.stats) {
        const double absorbed = static_cast<double>(
            flux.x * (1.0f - attenuation.x) +
            flux.y * (1.0f - attenuation.y) +
            flux.z * (1.0f - attenuation.z)
        );
        atomicAdd(&params.caustics.stats->total_flux_absorbed, absorbed);
    }

    flux = make_float3(flux.x * attenuation.x, flux.y * attenuation.y, flux.z * attenuation.z);
}

// handlePhotonSphereHit() removed — logic moved to __closesthit__photon()

/**
 * Check if photon hits the plane and deposit energy if so.
 * Returns true if photon was deposited (absorbed by diffuse surface).
 */
__device__ bool checkPlaneIntersection(
    const float3& origin,
    const float3& dir,
    const float3& flux
) {
    // Use the first active plane for caustic deposition
    if (params.num_planes <= 0 || !params.planes[0].enabled) return false;

    const int plane_axis = params.planes[0].axis;
    const float plane_value = params.planes[0].value;

    float ray_orig_comp, ray_dir_comp;
    if (plane_axis == 0) {
        ray_orig_comp = origin.x;
        ray_dir_comp = dir.x;
    } else if (plane_axis == 1) {
        ray_orig_comp = origin.y;
        ray_dir_comp = dir.y;
    } else {
        ray_orig_comp = origin.z;
        ray_dir_comp = dir.z;
    }

    if (fabsf(ray_dir_comp) > RAY_PARALLEL_THRESHOLD) {
        const float t_plane = (plane_value - ray_orig_comp) / ray_dir_comp;

        if (t_plane > CONTINUATION_RAY_OFFSET) {
            const float3 plane_hit = origin + dir * t_plane;
            depositPhoton(plane_hit, dir, flux);
            return true;  // Photon absorbed by diffuse surface
        }
    }

    return false;  // No plane intersection
}

//==============================================================================
// Progressive Photon Mapping - Photon Tracing (Phase 3)
//==============================================================================

/**
 * Trace a single photon through the scene using optixTrace(RAY_TYPE_PHOTON).
 *
 * - If photon hits refractive geometry, __closesthit__photon applies Snell's law
 *   and Beer-Lambert absorption, returning new origin/direction via payload
 * - If photon misses geometry, __miss__photon checks plane intersection and deposits
 * - Continues tracing until max bounces or photon escapes/is absorbed
 *
 * @param origin Starting position of photon
 * @param dir Initial direction of photon (normalized)
 * @param flux RGB energy carried by photon
 * @param seed RNG seed (modified in place)
 * @param max_bounces Maximum number of surface interactions
 */
__device__ void tracePhoton(
    float3 origin,
    float3 dir,
    float3 flux,
    unsigned int& seed,
    int max_bounces
) {
    // P9: only photons that have interacted with a specular (glass) surface may deposit on
    // the diffuse plane — this restricts storage to LS⁺D caustic paths and excludes photons
    // that travel straight from the light to the floor (which direct lighting already
    // accounts for). The bit is carried across bounces via payload p9 bit 2.
    bool touched_specular = false;
    // 33.10: draw one hero wavelength per photon and carry it in p10 (mirrors the primary-ray
    // convention in raygen_primary.cu). It only affects photons that pass through a dispersive
    // instance (cauchy_b > 0); for non-dispersive scenes the wavelength is never read and the
    // caustic is bit-identical. `dispersed` becomes sticky once such an interaction happens so
    // the deposit can tint the flux by the wavelength's CIE response.
    const float hero_lambda = 380.0f + rnd(seed) * 350.0f;  // λ ∈ [380, 730] nm
    bool dispersed = false;
    for (int bounce = 0; bounce < max_bounces; ++bounce) {
        // Pack payload into 11 uint32 registers (p0..p10)
        unsigned int p0 = __float_as_uint(flux.x);
        unsigned int p1 = __float_as_uint(flux.y);
        unsigned int p2 = __float_as_uint(flux.z);
        unsigned int p3 = 0, p4 = 0, p5 = 0;  // new_origin (set by closesthit)
        unsigned int p6 = 0, p7 = 0, p8 = 0;  // new_dir (set by closesthit)
        // flags: bit0 alive, bit1 deposited, bit2 touched_specular, bit3 dispersed (in/out)
        unsigned int p9 = (touched_specular ? 4u : 0u) | (dispersed ? 8u : 0u);
        unsigned int p10 = __float_as_uint(hero_lambda);

        using namespace SBTConstants;
        optixTrace(
            params.handle,
            origin, dir,
            CONTINUATION_RAY_OFFSET, MAX_RAY_DISTANCE, 0.f,
            OptixVisibilityMask(255), OPTIX_RAY_FLAG_NONE,
            RAY_TYPE_PHOTON, STRIDE_RAY_TYPES, MISS_PHOTON,
            p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10
        );

        // Unpack results
        flux = make_float3(__uint_as_float(p0), __uint_as_float(p1), __uint_as_float(p2));
        const bool alive    = (p9 & 1u) != 0;
        const bool deposited = (p9 & 2u) != 0;
        touched_specular     = (p9 & 4u) != 0;
        dispersed            = (p9 & 8u) != 0;

        if (deposited) return;
        if (!alive) return;

        // closesthit set new_origin/new_dir — advance photon
        origin = make_float3(__uint_as_float(p3), __uint_as_float(p4), __uint_as_float(p5));
        dir    = make_float3(__uint_as_float(p6), __uint_as_float(p7), __uint_as_float(p8));
    }
}

//==============================================================================
// Progressive Photon Mapping - Photon Emission Helpers (Phase 3)
//==============================================================================

/**
 * Emit a photon from a directional light source.
 *
 * Generates a parallel ray targeting the sphere by sampling a disk
 * perpendicular to the light direction, positioned behind the sphere.
 *
 * @param light The directional light source
 * @param seed RNG seed (modified in place)
 * @param photon_origin Output: photon starting position
 * @param photon_dir Output: photon direction (normalized)
 */
__device__ void emitDirectionalPhoton(
    const Light& light,
    unsigned int& seed,
    float3& photon_origin,
    float3& photon_dir,
    float& emission_measure
) {
    // Directional light: emit parallel rays targeting the sphere
    const float3 light_dir = normalize(make_float3(
        light.direction[0],
        light.direction[1],
        light.direction[2]
    ));

    // Create tangent space around light direction
    float3 tangent, bitangent;
    createONB(light_dir, tangent, bitangent);

    // Per-instance emission (F-CAUSTICS-MULTITARGET): each photon fills the emission disk of ONE
    // refractive instance, chosen with probability A_i / sum(A). For parallel rays the projected
    // cross-section is the disk area pi*r^2, so area is both the sampling weight and the emission
    // measure — instead of a single merged disk spanning the gaps between separated objects, over
    // which most photons miss every object. For a single object this reduces to the old behaviour
    // (one target == that object). n == 0 falls back to the merged disk.
    const int n = params.caustics.num_caustic_targets;
    const int count = (n > 0) ? n : 1;

    float area[CausticsParams::MAX_CAUSTIC_TARGETS];
    float sum_area = 0.0f;
    for (int i = 0; i < count; ++i) {
        const float r = (n > 0)
            ? PHOTON_DISK_RADIUS_MULTIPLIER * params.caustics.caustic_targets[i * 4 + 3]
            : PHOTON_DISK_RADIUS_MULTIPLIER * params.caustics.caustic_target_radius;
        area[i] = M_PIf * r * r;
        sum_area += area[i];
    }

    // Sample a target by its area-weighted CDF. Guarded so the single-target path draws no extra
    // rnd() and stays bit-identical to the pre-multitarget emission (protects directional caustic
    // reference images from a spurious RNG-stream shift).
    int ti = 0;
    if (count > 1) {
        float xi = rnd(seed) * sum_area;
        for (; ti < count - 1; ++ti) {
            if (xi < area[ti]) break;
            xi -= area[ti];
        }
    }

    float3 target_center;
    float disk_radius;
    if (n > 0) {
        target_center = make_float3(params.caustics.caustic_targets[ti * 4 + 0],
                                    params.caustics.caustic_targets[ti * 4 + 1],
                                    params.caustics.caustic_targets[ti * 4 + 2]);
        disk_radius = PHOTON_DISK_RADIUS_MULTIPLIER * params.caustics.caustic_targets[ti * 4 + 3];
    } else {
        target_center = make_float3(params.caustics.caustic_target_center[0],
                                    params.caustics.caustic_target_center[1],
                                    params.caustics.caustic_target_center[2]);
        disk_radius = PHOTON_DISK_RADIUS_MULTIPLIER * params.caustics.caustic_target_radius;
    }

    const float2 disk_sample = sampleDisk(rnd(seed), rnd(seed));
    photon_origin = target_center
                   + tangent * (disk_sample.x * disk_radius)
                   + bitangent * (disk_sample.y * disk_radius)
                   - light_dir * PHOTON_EMISSION_DISTANCE;

    photon_dir = light_dir;

    // P1 emission measure: photons uniformly fill the union of per-target emission disks, so each
    // represents E * sum(A) / N of the incident power (E = irradiance = light.intensity, A = disk
    // area). Reduces to the single-disk area for one target; without it the deposited energy would
    // depend on the arbitrary sampling-disk size — the root cause of the historical scale factors.
    emission_measure = sum_area;
}

/**
 * Emit a photon from a point light source toward the glass sphere.
 *
 * Uses importance sampling: samples directions within a cone subtending
 * the sphere (with margin), so most photons hit the sphere rather than
 * being wasted in random directions.
 *
 * @param light The point light source
 * @param seed RNG seed (modified in place)
 * @param photon_origin Output: photon starting position (light position)
 * @param photon_dir Output: photon direction (normalized, toward sphere)
 */
__device__ void emitPointPhoton(
    const Light& light,
    unsigned int& seed,
    float3& photon_origin,
    float3& photon_dir,
    float& emission_measure
) {
    photon_origin = make_float3(
        light.position[0],
        light.position[1],
        light.position[2]
    );

    // Per-instance emission (F-CAUSTICS-MULTITARGET): aim each photon at ONE refractive
    // instance chosen with probability dOmega_i / sum(dOmega), instead of a single merged
    // sphere spanning the gaps between objects. For a single object this reduces to the old
    // behaviour (one target == that object). n == 0 falls back to the merged sphere.
    const int n = params.caustics.num_caustic_targets;

    // Per-target cone solid angle; also the sampling weight. Overlapping cones double-count
    // the overlap (documented approximation; fine for separated objects).
    float domega[CausticsParams::MAX_CAUSTIC_TARGETS];
    float sum_domega = 0.0f;
    const int count = (n > 0) ? n : 1;
    for (int i = 0; i < count; ++i) {
        float3 c;
        float r;
        if (n > 0) {
            c = make_float3(params.caustics.caustic_targets[i * 4 + 0],
                            params.caustics.caustic_targets[i * 4 + 1],
                            params.caustics.caustic_targets[i * 4 + 2]);
            r = PHOTON_DISK_RADIUS_MULTIPLIER * params.caustics.caustic_targets[i * 4 + 3];
        } else {
            c = make_float3(params.caustics.caustic_target_center[0],
                            params.caustics.caustic_target_center[1],
                            params.caustics.caustic_target_center[2]);
            r = PHOTON_DISK_RADIUS_MULTIPLIER * params.caustics.caustic_target_radius;
        }
        const float d = length(c - photon_origin);
        const float sin_max = fminf(r / d, 1.0f);
        const float cos_max = sqrtf(1.0f - sin_max * sin_max);
        domega[i] = 2.0f * M_PIf * (1.0f - cos_max);
        sum_domega += domega[i];
    }

    // Sample a target by its solid-angle-weighted CDF. Guarded so the single-target path draws no
    // extra rnd() and stays bit-identical to the pre-multitarget emission.
    int ti = 0;
    if (count > 1) {
        float xi = rnd(seed) * sum_domega;
        for (; ti < count - 1; ++ti) {
            if (xi < domega[ti]) break;
            xi -= domega[ti];
        }
    }

    float3 target_center;
    float target_radius;
    if (n > 0) {
        target_center = make_float3(params.caustics.caustic_targets[ti * 4 + 0],
                                    params.caustics.caustic_targets[ti * 4 + 1],
                                    params.caustics.caustic_targets[ti * 4 + 2]);
        target_radius = PHOTON_DISK_RADIUS_MULTIPLIER * params.caustics.caustic_targets[ti * 4 + 3];
    } else {
        target_center = make_float3(params.caustics.caustic_target_center[0],
                                    params.caustics.caustic_target_center[1],
                                    params.caustics.caustic_target_center[2]);
        target_radius = PHOTON_DISK_RADIUS_MULTIPLIER * params.caustics.caustic_target_radius;
    }

    const float3 to_sphere = target_center - photon_origin;
    const float dist = length(to_sphere);
    const float3 axis = to_sphere / dist;

    const float sin_theta_max = fminf(target_radius / dist, 1.0f);
    const float cos_theta_max = sqrtf(1.0f - sin_theta_max * sin_theta_max);

    // Uniform sampling within the chosen target's cone
    const float cos_theta = 1.0f - rnd(seed) * (1.0f - cos_theta_max);
    const float sin_theta = sqrtf(1.0f - cos_theta * cos_theta);
    const float phi = 2.0f * M_PIf * rnd(seed);

    float3 tangent, bitangent;
    createONB(axis, tangent, bitangent);

    photon_dir = normalize(
        axis * cos_theta + tangent * (sin_theta * cosf(phi)) + bitangent * (sin_theta * sinf(phi))
    );

    // P1 emission measure: for non-overlapping cones the sampled-direction pdf is
    // 1 / sum(dOmega), so each photon represents I * sum(dOmega) / N of the emitted power.
    // This makes deposited energy independent of the (arbitrary) per-target cone half-angles.
    emission_measure = sum_domega;
}

/**
 * Calculate photon flux based on light properties and total photon count.
 *
 * Each photon carries a share of the power emitted into the sampled emission measure:
 *   phi = color * intensity * emission_measure / total_photons
 * where emission_measure is the cone solid angle (point light, intensity = W/sr) or the
 * emission-disk area (directional light, intensity = irradiance = W/m^2). This P1 factor
 * is what makes the deposited energy physically correct and sampling-geometry-independent.
 *
 * @param light The light source
 * @param total_photons Total number of photons being emitted this iteration
 * @param emission_measure Solid angle (sr) or disk area (m^2) the photons sample
 * @return RGB flux carried by each photon
 */
__device__ float3 calculatePhotonFlux(
    const Light& light, float total_photons, float emission_measure
) {
    const float per_photon = light.intensity * emission_measure / total_photons;
    return make_float3(
        light.color[0] * per_photon,
        light.color[1] * per_photon,
        light.color[2] * per_photon
    );
}

//==============================================================================
// Progressive Photon Mapping - Photon Hit/Miss Programs (RAY_TYPE_PHOTON)
//==============================================================================

/**
 * Closest hit program for photon rays.
 *
 * Handles photon interaction with any geometry type (sphere, triangle, cylinder).
 * Applies Beer-Lambert absorption when exiting, then refracts or reflects the photon.
 * Results are communicated back to tracePhoton() via the 10-register payload.
 */
extern "C" __global__ void __closesthit__photon() {
    // Get ray info
    const float3 ray_dir    = optixGetWorldRayDirection();
    const float  t          = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 hit_point  = ray_origin + ray_dir * t;

    // Get outward normal (geometry-type dependent)
    float3 outward_normal;
    float ior_material;
    float glass_color[4];
    float glass_scale;

    const OptixPrimitiveType primitive_type = optixGetPrimitiveType();

    if (primitive_type == OPTIX_PRIMITIVE_TYPE_TRIANGLE) {
        const TriangleHitGroupData* hit_data =
            reinterpret_cast<const TriangleHitGroupData*>(
                optixGetSbtDataPointer());
        const unsigned int inst_id = optixGetInstanceId();
        // In IAS mode, use per-instance buffer pointers
        TriangleGeometry geom;
        if (params.use_ias && params.instance_materials) {
            const InstanceMaterial& mat =
                params.instance_materials[inst_id];
            if (mat.vertices && mat.indices) {
                geom = getTriangleGeometry(
                    mat.vertices, mat.indices,
                    mat.vertex_stride);
            } else {
                geom = getTriangleGeometry(hit_data);
            }
        } else {
            geom = getTriangleGeometry(hit_data);
        }
        outward_normal = geom.entering
            ? geom.normal
            : make_float3(
                -geom.normal.x, -geom.normal.y,
                -geom.normal.z);
        ior_material =
            params.instance_materials[inst_id].ior;
        for (int i = 0; i < 4; i++)
            glass_color[i] =
                params.instance_materials[inst_id]
                    .color[i];
        glass_scale = 1.0f;
    } else if (primitive_type == OPTIX_PRIMITIVE_TYPE_ROUND_CUBIC_BSPLINE) {
        outward_normal = computeCurveNormalWorld();
        if (params.use_ias) {
            const unsigned int id = optixGetInstanceId();
            ior_material = params.instance_materials[id].ior;
            for (int i = 0; i < 4; i++) glass_color[i] = params.instance_materials[id].color[i];
            glass_scale = 1.0f;
        } else {
            ior_material = params.sphere_ior;
            for (int i = 0; i < 4; i++) glass_color[i] = params.sphere_color[i];
            glass_scale = params.sphere_scale;
        }
    } else {
        // Sphere or cylinder: normal from intersection attributes
        outward_normal = normalize(make_float3(
            __uint_as_float(optixGetAttribute_0()),
            __uint_as_float(optixGetAttribute_1()),
            __uint_as_float(optixGetAttribute_2())
        ));
        if (params.use_ias) {
            const unsigned int id = optixGetInstanceId();
            ior_material = params.instance_materials[id].ior;
            for (int i = 0; i < 4; i++) glass_color[i] = params.instance_materials[id].color[i];
            glass_scale = 1.0f;
        } else {
            // Legacy single-object mode
            ior_material = params.sphere_ior;
            for (int i = 0; i < 4; i++) glass_color[i] = params.sphere_color[i];
            glass_scale = params.sphere_scale;
        }
    }

    // Unpack photon flux from payload
    float3 flux = make_float3(
        __uint_as_float(optixGetPayload_0()),
        __uint_as_float(optixGetPayload_1()),
        __uint_as_float(optixGetPayload_2())
    );

    // 33.10: dispersive refraction — replace the scalar IOR with the Cauchy n(λ) for this
    // photon's hero wavelength when the instance carries dispersion (cauchy_b > 0). Splits the
    // caustic into spectral colours (rainbow rings). Non-dispersive instances are untouched, so
    // non-dispersive caustics stay bit-identical.
    bool photon_dispersed = (optixGetPayload_9() & 8u) != 0u;  // sticky across bounces
    if (params.use_ias && params.instance_materials) {
        const unsigned int disp_id = optixGetInstanceId();
        const float cauchy_b = params.instance_materials[disp_id].cauchy_b;
        if (cauchy_b > 0.0f) {
            const float lambda = __uint_as_float(optixGetPayload_10());
            if (lambda > 0.0f) {
                const float cauchy_a = params.instance_materials[disp_id].cauchy_a;
                ior_material = cauchy_a + cauchy_b / (lambda * lambda);
                photon_dispersed = true;
            }
        }
    }

    // Determine entering vs exiting
    const bool entering = dot(ray_dir, outward_normal) < 0.0f;
    const float3 normal = entering ? outward_normal : make_float3(-outward_normal.x, -outward_normal.y, -outward_normal.z);
    const float n1 = entering ? 1.0f : ior_material;
    const float n2 = entering ? ior_material : 1.0f;
    const float eta = n1 / n2;
    const float cos_theta_i = fabsf(dot(ray_dir, normal));
    const float sin_theta_i_sq = 1.0f - cos_theta_i * cos_theta_i;
    const float sin_theta_t_sq = eta * eta * sin_theta_i_sq;

    // Track stats
    if (params.caustics.stats) {
        atomicAdd(&params.caustics.stats->sphere_hits, 1ULL);
    }

    // Apply Beer-Lambert absorption on exit
    applyPhotonBeerLambert(flux, t, entering, glass_color, glass_color[3], glass_scale);

    // Reflection direction, used both for TIR and for Fresnel-reflected photons.
    // `normal` faces against the incoming ray (dot(ray_dir, normal) = -cos_theta_i), so the
    // mirror reflection is ray_dir + 2*cos_theta_i*normal — the sign that bounces the ray back
    // out of the surface (the old `- 2*cos*normal` sent the reflected ray straight through).
    const float3 reflect_dir = normalize(make_float3(
        ray_dir.x + 2.0f * cos_theta_i * normal.x,
        ray_dir.y + 2.0f * cos_theta_i * normal.y,
        ray_dir.z + 2.0f * cos_theta_i * normal.z
    ));

    float3 new_dir;
    if (sin_theta_t_sq > 1.0f) {
        // Total internal reflection — all energy reflects.
        if (params.caustics.stats) atomicAdd(&params.caustics.stats->tir_events, 1ULL);
        new_dir = reflect_dir;
    } else {
        const float cos_theta_t = sqrtf(1.0f - sin_theta_t_sq);

        // P3: exact unpolarised dielectric Fresnel reflectance (replaces the Schlick
        // approximation, which is inaccurate at the grazing angles that dominate a
        // caustic's bright rim).
        const float r_parl = (n2 * cos_theta_i - n1 * cos_theta_t)
                           / (n2 * cos_theta_i + n1 * cos_theta_t);
        const float r_perp = (n1 * cos_theta_i - n2 * cos_theta_t)
                           / (n1 * cos_theta_i + n2 * cos_theta_t);
        const float fresnel = 0.5f * (r_parl * r_parl + r_perp * r_perp);

        // P2: Russian-roulette split — reflect with probability F, refract otherwise, and
        // carry the photon's full flux either way (no (1-F) weighting). This conserves
        // energy across the photon population and populates reflective caustics, which the
        // old "always refract, scale flux by (1-F)" path silently discarded.
        unsigned int rr_seed = tea(__float_as_uint(hit_point.x + hit_point.z),
                                   __float_as_uint(flux.x + hit_point.y));
        if (rnd(rr_seed) < fresnel) {
            new_dir = reflect_dir;
        } else {
            if (params.caustics.stats) atomicAdd(&params.caustics.stats->refraction_events, 1ULL);
            new_dir = normalize(make_float3(
                eta * ray_dir.x + (eta * cos_theta_i - cos_theta_t) * normal.x,
                eta * ray_dir.y + (eta * cos_theta_i - cos_theta_t) * normal.y,
                eta * ray_dir.z + (eta * cos_theta_i - cos_theta_t) * normal.z
            ));
        }
    }

    const float3 new_origin = hit_point + new_dir * CONTINUATION_RAY_OFFSET;

    // Set payload: updated flux, new_origin, new_dir, alive=true, deposited=false
    optixSetPayload_0(__float_as_uint(flux.x));
    optixSetPayload_1(__float_as_uint(flux.y));
    optixSetPayload_2(__float_as_uint(flux.z));
    optixSetPayload_3(__float_as_uint(new_origin.x));
    optixSetPayload_4(__float_as_uint(new_origin.y));
    optixSetPayload_5(__float_as_uint(new_origin.z));
    optixSetPayload_6(__float_as_uint(new_dir.x));
    optixSetPayload_7(__float_as_uint(new_dir.y));
    optixSetPayload_8(__float_as_uint(new_dir.z));
    // bit0 alive=1, bit2 touched_specular=1 (interacted with glass), bit3 dispersed (33.10).
    optixSetPayload_9(1u | 4u | (photon_dispersed ? 8u : 0u));
}

/**
 * Miss program for photon rays.
 *
 * When a photon misses all geometry, check if it hits the diffuse plane
 * for energy deposition. Sets payload flags accordingly.
 */
extern "C" __global__ void __miss__photon() {
    const float3 origin = optixGetWorldRayOrigin();
    const float3 dir    = optixGetWorldRayDirection();
    const float3 flux   = make_float3(
        __uint_as_float(optixGetPayload_0()),
        __uint_as_float(optixGetPayload_1()),
        __uint_as_float(optixGetPayload_2())
    );

    const unsigned int touched_bit = optixGetPayload_9() & 4u;  // preserve touched_specular
    const unsigned int disp_bit    = optixGetPayload_9() & 8u;  // preserve dispersed (33.10)

    // 33.10: a dispersive photon carries one hero wavelength; convert its flux to the
    // wavelength's CIE RGB response once, at deposit, so the caustic spreads into spectral
    // colours. Non-dispersive photons deposit their untinted flux unchanged.
    float3 dep_flux = flux;
    if (disp_bit != 0u) {
        const float lambda = __uint_as_float(optixGetPayload_10());
        const float3 tint = heroWavelengthToRGB(lambda);
        dep_flux = make_float3(flux.x * tint.x, flux.y * tint.y, flux.z * tint.z);
    }

    // P9: deposit only for LS⁺D paths — a photon that reached the floor without ever
    // touching glass carries direct illumination that the direct-lighting pass already
    // accounts for; storing it here would double-count the floor's direct light.
    if (touched_bit != 0u && checkPlaneIntersection(origin, dir, dep_flux)) {
        optixSetPayload_9(2u | touched_bit | disp_bit);  // alive=false, deposited=true
    } else {
        optixSetPayload_9(touched_bit | disp_bit);       // alive=false, deposited=false
    }
    // Track miss stats
    if (params.caustics.stats) {
        atomicAdd(&params.caustics.stats->sphere_misses, 1ULL);
    }
}

//==============================================================================
// Progressive Photon Mapping - Photon Emission Raygen (Phase 3)
//==============================================================================

/**
 * Ray generation program for emitting photons from light sources.
 *
 * This is the photon tracing phase of Progressive Photon Mapping:
 * - Each thread emits one photon from a light source
 * - Photons are traced through the scene, refracting through the sphere
 * - When photons hit the diffuse plane, they deposit energy at nearby hit points
 *
 * Note: Launch dimensions should be sqrt(photons_per_iteration) x sqrt(photons_per_iteration)
 */
extern "C" __global__ void __raygen__photons() {
    const uint3 idx = optixGetLaunchIndex();
    const uint3 dim = optixGetLaunchDimensions();

    // Each thread traces one photon
    const unsigned int photon_idx = idx.x + idx.y * dim.x;

    // Initialize RNG with photon index + iteration for different patterns each iteration
    unsigned int seed = tea(photon_idx, params.caustics.current_iteration);

    // Select light source (for now, use first light)
    // TODO: Weight by intensity for multiple lights
    if (params.num_lights == 0) return;

    const Light& light = params.lights[0];

    // Generate photon origin and direction based on light type
    float3 photon_origin;
    float3 photon_dir;

    float emission_measure = 0.0f;
    if (light.type == LightType::DIRECTIONAL) {
        emitDirectionalPhoton(light, seed, photon_origin, photon_dir, emission_measure);
    } else {
        emitPointPhoton(light, seed, photon_origin, photon_dir, emission_measure);
    }

    // Calculate photon flux (P1: includes the emission-measure factor)
    const float total_photons = static_cast<float>(dim.x * dim.y);
    const float3 photon_flux = calculatePhotonFlux(light, total_photons, emission_measure);

    // C1: Track photon emission statistics
    if (params.caustics.stats) {
        atomicAdd(&params.caustics.stats->photons_emitted, 1ULL);
        // Track total emitted flux (sum of RGB)
        const double flux_sum = static_cast<double>(photon_flux.x + photon_flux.y + photon_flux.z);
        atomicAdd(&params.caustics.stats->total_flux_emitted, flux_sum);
    }

    // Trace photon through scene
    tracePhoton(photon_origin, photon_dir, photon_flux, seed, MAX_PHOTON_BOUNCES);
}

//==============================================================================
// Progressive Photon Mapping - Radius Update Kernel (Phase 4)
//==============================================================================

/**
 * CUDA kernel to update hit point search radii using PPM progressive refinement.
 *
 * PPM radius reduction formula:
 *   R_new = R_old * sqrt((N + α*M) / (N + M))
 *
 * Where:
 *   N = accumulated photon count from previous iterations
 *   M = new photons this iteration
 *   α = radius reduction factor (typically 0.7)
 *
 * This causes the radius to shrink over iterations, improving estimate quality.
 */
extern "C" __global__ void __raygen__update_radii() {
    const unsigned int idx = optixGetLaunchIndex().x;
    if (idx >= *params.caustics.num_hit_points) return;

    HitPoint& hp = params.caustics.hit_points[idx];

    // Only update if we received photons this iteration
    if (hp.new_photons > 0) {
        const float N = static_cast<float>(hp.n);
        const float M = static_cast<float>(hp.new_photons);

        if (N > 0.0f) {
            // PPM progressive radius reduction (iterations 1+)
            // Only apply when we have history from previous iterations
            const float alpha = params.caustics.alpha;
            const float new_N = N + alpha * M;
            const float ratio = new_N / (N + M);

            hp.radius *= sqrtf(ratio);
            hp.n = static_cast<unsigned int>(new_N);

            // Scale flux to account for reduced radius
            hp.flux[0] *= ratio;
            hp.flux[1] *= ratio;
            hp.flux[2] *= ratio;
        } else {
            // First iteration: initialize photon count, keep flux and radius as-is
            hp.n = static_cast<unsigned int>(M);
        }
    }

    // Reset new photon counter for next iteration
    hp.new_photons = 0;
}

//==============================================================================
// Progressive Photon Mapping - Final Radiance Computation (Phase 4)
//==============================================================================

/**
 * OptiX raygen shader to compute final caustic radiance and write to image buffer.
 *
 * Converts accumulated photon flux to radiance estimate:
 *   L = Φ / (π * R² * N_total)
 *
 * Where:
 *   Φ = accumulated flux
 *   R = final search radius
 *   N_total = total photons traced across all iterations
 *
 * Launch with dimensions = (num_hit_points, 1) to process all hit points.
 */
extern "C" __global__ void __raygen__caustics_radiance() {
    const uint3 idx = optixGetLaunchIndex();
    if (idx.x >= *params.caustics.num_hit_points) return;

    const HitPoint& hp = params.caustics.hit_points[idx.x];

    // Skip hit points with no accumulated flux
    // Check flux magnitude instead of photon count (n is only updated by radius kernel)
    const float flux_magnitude = hp.flux[0] + hp.flux[1] + hp.flux[2];
    if (flux_magnitude < FLUX_EPSILON) return;

    // C4: Track hit points that received flux
    if (params.caustics.stats) {
        atomicAdd(&params.caustics.stats->hit_points_with_flux, 1ULL);
    }

    // Compute radiance estimate using the PPM density-estimate formula
    //   L = Phi / (pi * R^2 * iterations)
    //
    // Normalization convention (see calculatePhotonFlux): each photon already carries
    // I * dOmega / M of the emitted power, where M = photons_per_iteration. Summed over
    // all `iterations` photon passes, the accumulated flux therefore overcounts the true
    // irradiance by exactly `iterations` (P6). Dividing by it makes the estimate
    // iteration-invariant: doubling the iteration budget refines noise, not brightness.
    const float area = M_PI * hp.radius * hp.radius;

    // Avoid division by zero
    if (area < FLUX_EPSILON) return;

    // P6: normalize by the number of photon passes accumulated into hp.flux.
    const float iter_norm = fmaxf(static_cast<float>(params.caustics.iterations), 1.0f);
    const float denom = area * iter_norm;

    // P5: reflected radiance of a Lambertian floor lit by the caustic irradiance estimate
    // E = τ/(π R² · iterations) is L = (ρ/π)·E, where ρ = hp.weight is the floor albedo
    // captured at hit-point creation. The uniform-disk deposit (τ += Φ, no cosθ, no Gaussian)
    // makes E the physically correct photon-density irradiance.
    const float inv_pi = static_cast<float>(M_1_PI);
    const float3 radiance = make_float3(
        hp.weight[0] * inv_pi * hp.flux[0] / denom,
        hp.weight[1] * inv_pi * hp.flux[1] / denom,
        hp.weight[2] * inv_pi * hp.flux[2] / denom
    );

    // P4: composite the caustic using the SAME global tone map as the rest of the frame, then
    // add it in display space. The private exponential map (exposure 0.06) + screen blend that
    // used to live here crushed the caustic by ~250x and is gone. For ToneMapping.None the
    // global operator is a clamp, so this is an exact linear add + clamp (the physically correct
    // result); for Reinhard/ACES the caustic is tone-mapped separately and added, an
    // approximation until the pre-tone-map float-HDR film buffer lands (backlog F-HDR-FILM).
    const float3 mapped = applyToneMapping(radiance);

    const unsigned int pixel_idx = (hp.pixel_y * params.image_width + hp.pixel_x) * 4;
    const float cur_r = static_cast<float>(params.image[pixel_idx + 0]) / COLOR_BYTE_MAX;
    const float cur_g = static_cast<float>(params.image[pixel_idx + 1]) / COLOR_BYTE_MAX;
    const float cur_b = static_cast<float>(params.image[pixel_idx + 2]) / COLOR_BYTE_MAX;

    const float new_r = fminf(cur_r + mapped.x, 1.0f);
    const float new_g = fminf(cur_g + mapped.y, 1.0f);
    const float new_b = fminf(cur_b + mapped.z, 1.0f);

    // C7: Track brightness metrics
    if (params.caustics.stats) {
        const float caustic_brightness = (mapped.x + mapped.y + mapped.z) / 3.0f;
        // Atomic max for non-negative floats: standard CUDA idiom (IEEE-754 bit ordering).
        atomicMax(reinterpret_cast<int*>(&params.caustics.stats->max_caustic_brightness),
                  __float_as_int(caustic_brightness));
    }

    // Write back to image buffer
    params.image[pixel_idx + 0] = static_cast<unsigned char>(new_r * COLOR_BYTE_MAX);
    params.image[pixel_idx + 1] = static_cast<unsigned char>(new_g * COLOR_BYTE_MAX);
    params.image[pixel_idx + 2] = static_cast<unsigned char>(new_b * COLOR_BYTE_MAX);
}
