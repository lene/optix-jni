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
// Phase 4 - Radiance Computation (__raygen__caustics_radiance, __caustics_update_radii):
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

    // BRDF weight (Lambertian = 1/π, normalized later)
    hp.weight[0] = 1.0f;
    hp.weight[1] = 1.0f;
    hp.weight[2] = 1.0f;
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
        0,                           // SBT offset (primary ray type)
        2,                           // SBT stride
        0,                           // missSBTIndex
        p0, p1, p2, p3
    );

    // Re-compute plane intersection to determine if we have a diffuse hit
    // Use the first active plane for caustic hit point collection
    if (params.num_planes > 0) {
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
 * CUDA kernel to count hit points per grid cell.
 * Called after hit point generation to build spatial hash grid.
 */
extern "C" __global__ void __caustics_count_grid_cells() {
    const unsigned int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx >= *params.caustics.num_hit_points) return;

    const HitPoint& hp = params.caustics.hit_points[idx];
    const float3 pos = make_float3(hp.position[0], hp.position[1], hp.position[2]);

    const uint3 cell = getCausticsGridCell(pos);
    const unsigned int cell_idx = getCausticsGridIndex(cell);

    atomicAdd(&params.caustics.grid_counts[cell_idx], 1);
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
    // Simple brute-force search through all hit points
    // TODO: Use spatial hash grid for efficiency once grid building is implemented
    const unsigned int num_hp = *params.caustics.num_hit_points;

    // Check all hit points (slow but accurate for debugging)
    for (unsigned int hp_idx = 0; hp_idx < num_hp; hp_idx += 1) {
        HitPoint& hp = params.caustics.hit_points[hp_idx];

        // Compute distance from photon to hit point
        const float3 hp_pos = make_float3(hp.position[0], hp.position[1], hp.position[2]);
        const float3 diff = photon_pos - hp_pos;
        const float dist_sq = dot(diff, diff);
        const float radius_sq = hp.radius * hp.radius;

        // Check if photon is within hit point's gather radius
        if (dist_sq < radius_sq) {
            // Weight by cosine of angle (Lambertian BRDF)
            const float3 hp_normal = make_float3(hp.normal[0], hp.normal[1], hp.normal[2]);
            const float cos_theta = fmaxf(0.0f, dot(make_float3(-photon_dir.x, -photon_dir.y, -photon_dir.z), hp_normal));

            if (cos_theta > 0.0f) {
                // Atomic accumulation of flux
                atomicAdd(&hp.flux[0], flux.x * cos_theta);
                atomicAdd(&hp.flux[1], flux.y * cos_theta);
                atomicAdd(&hp.flux[2], flux.z * cos_theta);

                // Count photons for this hit point (this iteration)
                atomicAdd(&hp.new_photons, 1);

                // C4: Track deposition statistics
                if (params.caustics.stats) {
                    atomicAdd(&params.caustics.stats->photons_deposited, 1ULL);
                    const double deposited_flux = static_cast<double>(
                        (flux.x + flux.y + flux.z) * cos_theta
                    );
                    atomicAdd(&params.caustics.stats->total_flux_deposited, deposited_flux);
                }
            }
        }
    }
}

//==============================================================================
// Progressive Photon Mapping - Photon Tracing Helpers (Phase 3)
//==============================================================================

/**
 * Intersect ray with sphere and return distance to nearest hit.
 * Returns -1.0f if no valid intersection.
 */
__device__ float intersectSphere(
    const float3& origin,
    const float3& dir,
    const float3& sphere_center,
    float sphere_radius
) {
    const float3 oc = origin - sphere_center;
    const float a = dot(dir, dir);
    const float half_b = dot(oc, dir);
    const float c = dot(oc, oc) - sphere_radius * sphere_radius;
    const float discriminant = half_b * half_b - a * c;

    if (discriminant <= 0.0f) {
        return -1.0f;  // No intersection
    }

    const float sqrt_d = sqrtf(discriminant);
    float t = (-half_b - sqrt_d) / a;  // Near intersection

    if (t < CONTINUATION_RAY_OFFSET) {
        t = (-half_b + sqrt_d) / a;  // Try far intersection
    }

    return (t > CONTINUATION_RAY_OFFSET) ? t : -1.0f;
}

/**
 * Apply Beer-Lambert absorption to photon flux when exiting the sphere.
 *
 * Beer-Lambert Law: I(d) = I₀ · exp(-α · d)
 * Where α is derived from glass color and opacity.
 *
 * @param flux Photon flux (modified in place)
 * @param distance Distance traveled through the medium
 * @param entering True if photon is entering the sphere (no absorption)
 */
__device__ void applyPhotonBeerLambert(float3& flux, float distance, bool entering) {
    if (entering) return;  // No absorption when entering

    const float3 glass_color = make_float3(
        params.sphere_color[0],
        params.sphere_color[1],
        params.sphere_color[2]
    );
    const float glass_alpha = params.sphere_color[3];

    const float absorption_scale = BEER_LAMBERT_ABSORPTION_SCALE;
    const float3 extinction = make_float3(
        -logf(fmaxf(glass_color.x, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
        -logf(fmaxf(glass_color.y, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
        -logf(fmaxf(glass_color.z, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale
    );

    const float3 attenuation = make_float3(
        expf(-extinction.x * distance * params.sphere_scale),
        expf(-extinction.y * distance * params.sphere_scale),
        expf(-extinction.z * distance * params.sphere_scale)
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

/**
 * Handle photon interaction with sphere surface.
 * Applies Beer-Lambert absorption when exiting, then refracts or reflects photon.
 * Updates origin and dir for the next bounce.
 * Returns false if photon should terminate (absorbed).
 */
__device__ bool handlePhotonSphereHit(
    float3& origin,
    float3& dir,
    float3& flux,
    float t,
    const float3& sphere_center
) {
    const float3 hit_point = origin + dir * t;
    const float3 outward_normal = normalize(hit_point - sphere_center);

    if (params.caustics.stats) {
        atomicAdd(&params.caustics.stats->sphere_hits, 1ULL);
    }

    const bool entering = dot(dir, outward_normal) < 0.0f;
    const float3 normal = entering ? outward_normal : make_float3(-outward_normal.x, -outward_normal.y, -outward_normal.z);

    const float n1 = entering ? 1.0f : params.sphere_ior;
    const float n2 = entering ? params.sphere_ior : 1.0f;
    const float eta = n1 / n2;

    const float cos_theta_i = fabsf(dot(dir, normal));
    const float sin_theta_i_sq = 1.0f - cos_theta_i * cos_theta_i;
    const float sin_theta_t_sq = eta * eta * sin_theta_i_sq;

    // Apply Beer-Lambert absorption when exiting
    applyPhotonBeerLambert(flux, t, entering);

    // Check for total internal reflection
    if (sin_theta_t_sq > 1.0f) {
        if (params.caustics.stats) {
            atomicAdd(&params.caustics.stats->tir_events, 1ULL);
        }
        const float3 reflect_dir = make_float3(
            dir.x - 2.0f * cos_theta_i * normal.x,
            dir.y - 2.0f * cos_theta_i * normal.y,
            dir.z - 2.0f * cos_theta_i * normal.z
        );
        origin = hit_point + reflect_dir * CONTINUATION_RAY_OFFSET;
        dir = normalize(reflect_dir);
    } else {
        // Refraction using Snell's law
        if (params.caustics.stats) {
            atomicAdd(&params.caustics.stats->refraction_events, 1ULL);
        }
        const float cos_theta_t = sqrtf(1.0f - sin_theta_t_sq);
        const float3 refract_dir = make_float3(
            eta * dir.x + (eta * cos_theta_i - cos_theta_t) * normal.x,
            eta * dir.y + (eta * cos_theta_i - cos_theta_t) * normal.y,
            eta * dir.z + (eta * cos_theta_i - cos_theta_t) * normal.z
        );
        origin = hit_point + refract_dir * CONTINUATION_RAY_OFFSET;
        dir = normalize(refract_dir);
    }

    return true;  // Continue tracing
}

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
    if (params.num_planes <= 0) return false;

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
 * Trace a single photon through the scene.
 *
 * - If photon hits the sphere, apply refraction (Snell's law) and Beer-Lambert absorption
 * - If photon hits the diffuse plane, deposit its energy at nearby hit points
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
    const float3 sphere_center = make_float3(
        params.caustics.sphere_center[0],
        params.caustics.sphere_center[1],
        params.caustics.sphere_center[2]
    );
    const float sphere_radius = params.caustics.sphere_radius;

    for (int bounce = 0; bounce < max_bounces; ++bounce) {
        // Check sphere intersection
        const float t = intersectSphere(origin, dir, sphere_center, sphere_radius);

        if (t > 0.0f) {
            // Photon hits sphere - apply refraction/reflection
            handlePhotonSphereHit(origin, dir, flux, t, sphere_center);
            continue;
        }

        // Sphere missed - check plane intersection
        if (checkPlaneIntersection(origin, dir, flux)) {
            return;  // Photon deposited on plane
        }

        // Track sphere miss on first bounce
        if (params.caustics.stats && bounce == 0) {
            atomicAdd(&params.caustics.stats->sphere_misses, 1ULL);
        }

        return;  // Photon escaped scene
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
    float3& photon_dir
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

    // Sample disk with radius large enough to cover sphere
    const float disk_radius = PHOTON_DISK_RADIUS_MULTIPLIER * params.caustics.sphere_radius;
    const float2 disk_sample = sampleDisk(rnd(seed), rnd(seed));

    // Photon starts from disk behind the sphere
    const float3 sphere_center = make_float3(
        params.caustics.sphere_center[0],
        params.caustics.sphere_center[1],
        params.caustics.sphere_center[2]
    );
    photon_origin = sphere_center
                   + tangent * (disk_sample.x * disk_radius)
                   + bitangent * (disk_sample.y * disk_radius)
                   - light_dir * PHOTON_EMISSION_DISTANCE;

    photon_dir = light_dir;
}

/**
 * Emit a photon from a point light source.
 *
 * Generates a ray in a random direction from the light position
 * using uniform sphere sampling.
 *
 * @param light The point light source
 * @param seed RNG seed (modified in place)
 * @param photon_origin Output: photon starting position (light position)
 * @param photon_dir Output: photon direction (normalized, random)
 */
__device__ void emitPointPhoton(
    const Light& light,
    unsigned int& seed,
    float3& photon_origin,
    float3& photon_dir
) {
    photon_origin = make_float3(
        light.position[0],
        light.position[1],
        light.position[2]
    );
    photon_dir = sampleSphere(rnd(seed), rnd(seed));
}

/**
 * Calculate photon flux based on light properties and total photon count.
 *
 * Distributes total light energy evenly among all photons in this iteration.
 *
 * @param light The light source
 * @param total_photons Total number of photons being emitted
 * @return RGB flux carried by each photon
 */
__device__ float3 calculatePhotonFlux(const Light& light, float total_photons) {
    return make_float3(
        light.color[0] * light.intensity / total_photons,
        light.color[1] * light.intensity / total_photons,
        light.color[2] * light.intensity / total_photons
    );
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

    if (light.type == LightType::DIRECTIONAL) {
        emitDirectionalPhoton(light, seed, photon_origin, photon_dir);
    } else {
        emitPointPhoton(light, seed, photon_origin, photon_dir);
    }

    // Calculate photon flux
    const float total_photons = static_cast<float>(dim.x * dim.y);
    const float3 photon_flux = calculatePhotonFlux(light, total_photons);

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
extern "C" __global__ void __caustics_update_radii() {
    const unsigned int idx = blockIdx.x * blockDim.x + threadIdx.x;
    if (idx >= *params.caustics.num_hit_points) return;

    HitPoint& hp = params.caustics.hit_points[idx];

    // Only update if we received photons this iteration
    if (hp.new_photons > 0) {
        const float alpha = params.caustics.alpha;
        const float N = static_cast<float>(hp.n);
        const float M = static_cast<float>(hp.new_photons);

        // PPM radius reduction
        const float new_N = N + alpha * M;
        const float ratio = new_N / (N + M);

        // Update radius
        hp.radius *= sqrtf(ratio);

        // Update photon count
        hp.n = static_cast<unsigned int>(new_N);

        // Scale flux to account for reduced radius
        // (maintains energy conservation)
        hp.flux[0] *= ratio;
        hp.flux[1] *= ratio;
        hp.flux[2] *= ratio;
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

    // Compute radiance estimate using PPM formula
    // L = Φ / (π × R²)
    //
    // Note: The flux Φ is already normalized by total_photons during emission:
    //   photon_flux = light_intensity / total_photons
    // So we should NOT divide by total_photons again here - that was causing
    // the need for a 10000x magic scale factor.
    const float area = M_PI * hp.radius * hp.radius;

    // Avoid division by zero
    if (area < FLUX_EPSILON) return;

    // Radiance = flux / (π * R²)
    // Note: For Lambertian BRDF, the reflected radiance is flux/(π*area).
    // Our flux already includes the cos(θ) term from Lambertian weighting.
    const float3 radiance = make_float3(
        hp.flux[0] / area,
        hp.flux[1] / area,
        hp.flux[2] / area
    );

    // Scale caustic contribution
    // With the corrected formula (no double-normalization), values should be
    // in a reasonable range. A small scale may still be needed to account for:
    // - Light intensity calibration differences
    // - Scene-specific exposure adjustment
    // Start with 1.0 (physics-based), adjust if caustics are too dim/bright.
    const float caustic_scale = 1.0f;

    // Add caustic radiance to the pixel (additive blending)
    const unsigned int pixel_idx = (hp.pixel_y * params.image_width + hp.pixel_x) * 4;

    // Read current pixel color
    const float cur_r = static_cast<float>(params.image[pixel_idx + 0]) / COLOR_BYTE_MAX;
    const float cur_g = static_cast<float>(params.image[pixel_idx + 1]) / COLOR_BYTE_MAX;
    const float cur_b = static_cast<float>(params.image[pixel_idx + 2]) / COLOR_BYTE_MAX;

    // Add caustic contribution (with clamping)
    const float new_r = fminf(cur_r + radiance.x * caustic_scale, 1.0f);
    const float new_g = fminf(cur_g + radiance.y * caustic_scale, 1.0f);
    const float new_b = fminf(cur_b + radiance.z * caustic_scale, 1.0f);

    // C7: Track brightness metrics
    if (params.caustics.stats) {
        const float caustic_brightness = (radiance.x + radiance.y + radiance.z) * caustic_scale / 3.0f;
        // Atomic max for non-negative floats: This is a standard CUDA idiom.
        // IEEE 754 guarantees that for non-negative floats, the bit representation
        // preserves ordering (larger float = larger unsigned int when interpreted as bits).
        // CUDA's __float_as_int/__int_as_float intrinsics are designed for this pattern,
        // and NVCC does not enforce strict aliasing like host C++ compilers.
        atomicMax(reinterpret_cast<int*>(&params.caustics.stats->max_caustic_brightness),
                  __float_as_int(caustic_brightness));
    }

    // Write back to image buffer
    params.image[pixel_idx + 0] = static_cast<unsigned char>(new_r * COLOR_BYTE_MAX);
    params.image[pixel_idx + 1] = static_cast<unsigned char>(new_g * COLOR_BYTE_MAX);
    params.image[pixel_idx + 2] = static_cast<unsigned char>(new_b * COLOR_BYTE_MAX);
}
