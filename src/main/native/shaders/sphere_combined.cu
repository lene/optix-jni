#include <optix.h>
#include "../include/OptiXData.h"
#include "../include/VectorMath.h"

extern "C" {
    __constant__ Params params;
}

// Import ray tracing constants from OptiXData.h
using namespace RayTracingConstants;

// Beer-Lambert Law: I(d) = I₀ · exp(-α · d)
// Where:
//   I₀ = initial intensity
//   α = absorption coefficient (derived from color RGB and alpha)
//   d = distance traveled through medium
//
// Alpha interpretation (standard graphics convention):
//   alpha=1.0 → fully opaque (maximum absorption)
//   alpha=0.0 → fully transparent (no absorption)
//
// Color interpretation (RGB):
//   Each channel controls wavelength-dependent absorption
//   RGB(1,1,1) → no color tint (white/gray when opaque)
//   RGB(1,0,0) → absorbs green/blue, shows red (red tinted when opaque)

//==============================================================================
// Shadow Ray Tracing Helper
//==============================================================================

/**
 * Trace a shadow ray to determine visibility between hit point and light.
 *
 * Casts a shadow ray from the surface toward the light source to check
 * for occlusion. Handles transparent surfaces with Beer-Lambert absorption.
 *
 * @param hit_point Surface position where shadow ray originates
 * @param normal Surface normal (for offset to avoid self-intersection)
 * @param light_dir Direction to light source (normalized)
 * @return Shadow factor in range [0, 1] where 0=fully shadowed, 1=fully lit
 */
__device__ float traceShadowRay(
    const float3& hit_point,
    const float3& normal,
    const float3& light_dir
) {
    // Offset origin along normal to avoid shadow acne (self-intersection)
    const float3 shadow_origin = hit_point + normal * SHADOW_RAY_OFFSET;

    // Payload: 0.0 if ray hits (shadowed), 1.0 if ray misses (lit)
    // Transparent objects pack alpha-based attenuation
    unsigned int shadow_payload = 0;

    optixTrace(
        params.handle,
        shadow_origin,
        light_dir,
        SHADOW_RAY_OFFSET,           // tmin (avoid immediate intersection)
        SHADOW_RAY_MAX_DISTANCE,     // tmax (effectively infinite)
        0.0f,                         // rayTime
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,         // Need closest hit to get alpha value
        1,                            // SBT offset (shadow ray type)
        2,                            // SBT stride (number of ray types)
        1,                            // missSBTIndex (shadow miss)
        shadow_payload
    );

    // Unpack shadow attenuation
    // shadow_attenuation: 0.0 = no occlusion (fully lit)
    //                     1.0 = full occlusion (fully shadowed)
    const float shadow_attenuation = __uint_as_float(shadow_payload);

    // Convert to shadow factor
    // shadow_factor: 1.0 = fully lit, 0.0 = fully shadowed
    const float shadow_factor = 1.0f - shadow_attenuation;

    // Track shadow ray statistics
    if (params.stats) {
        atomicAdd(&params.stats->shadow_rays, 1ULL);
        atomicAdd(&params.stats->total_rays, 1ULL);
    }

    return shadow_factor;
}

//==============================================================================
// Lighting Calculation Helper
//==============================================================================

/**
 * Calculate total lighting contribution from all lights in scene.
 *
 * Accumulates lighting from multiple light sources (directional and point),
 * with support for shadows, distance attenuation, and ambient lighting.
 *
 * @param hit_point Surface position to light
 * @param normal Surface normal (normalized)
 * @param double_sided If true, use absolute value of NdotL (for planes)
 * @return Total lighting color (RGB) including ambient term, range [0, ∞)
 */
__device__ float3 calculateLighting(
    const float3& hit_point,
    const float3& normal,
    bool double_sided = false
) {
    float3 total_lighting = make_float3(0.0f, 0.0f, 0.0f);

    // Accumulate contribution from each light
    for (int light_idx = 0; light_idx < params.num_lights; ++light_idx) {
        const Light& light = params.lights[light_idx];

        // Calculate light direction and attenuation based on light type
        float3 light_dir;
        float attenuation;

        if (light.type == LightType::DIRECTIONAL) {
            // Directional light: parallel rays from infinite distance
            light_dir = normalize(make_float3(
                light.direction[0],
                light.direction[1],
                light.direction[2]
            ));
            attenuation = 1.0f;  // No distance falloff

        } else if (light.type == LightType::POINT) {
            // Point light: radial emission with inverse-square falloff
            const float3 light_pos = make_float3(
                light.position[0],
                light.position[1],
                light.position[2]
            );
            const float3 to_light = light_pos - hit_point;
            const float distance = length(to_light);
            light_dir = to_light / distance;  // Normalize

            // Inverse-square law: I = I₀ / (1 + d²)
            attenuation = 1.0f / (1.0f + distance * distance);
        }

        // Calculate diffuse term (Lambertian: N · L)
        float ndotl;
        if (double_sided) {
            // Double-sided surface (e.g., plane): use absolute value
            const float raw_ndotl = dot(normal, light_dir);
            ndotl = fabsf(raw_ndotl);
        } else {
            // Single-sided surface (e.g., sphere): clamp to [0, ∞)
            ndotl = fmaxf(0.0f, dot(normal, light_dir));
        }

        // Skip lights that don't contribute (sub-pixel threshold)
        if (ndotl <= MIN_LIGHTING_THRESHOLD) {
            continue;
        }

        // Trace shadow ray if shadows enabled
        const float shadow_factor = params.shadows_enabled
            ? traceShadowRay(hit_point, normal, light_dir)
            : 1.0f;

        // Accumulate light contribution
        const float3 light_color = make_float3(
            light.color[0],
            light.color[1],
            light.color[2]
        );

        total_lighting = total_lighting + light_color * light.intensity * attenuation * ndotl * shadow_factor;
    }

    // Add ambient lighting (prevents pure black shadows)
    const float3 ambient = make_float3(AMBIENT_LIGHT_FACTOR, AMBIENT_LIGHT_FACTOR, AMBIENT_LIGHT_FACTOR);

    // Combine: ambient + diffuse (energy conserving)
    return ambient + total_lighting * (1.0f - AMBIENT_LIGHT_FACTOR);
}

//==============================================================================
// Adaptive Antialiasing Helpers
//==============================================================================

/**
 * Calculate Euclidean color distance between two RGB colors.
 *
 * Computes the L2 norm in RGB color space for edge detection.
 * Higher values indicate greater color difference.
 *
 * @param c1 First color (RGB, 0-255)
 * @param c2 Second color (RGB, 0-255)
 * @return Euclidean distance normalized to [0, 1] range (max distance √3 ≈ 1.732)
 */
__device__ float colorDistance(
    const unsigned int r1, const unsigned int g1, const unsigned int b1,
    const unsigned int r2, const unsigned int g2, const unsigned int b2
) {
    // Normalize to [0, 1] range
    const float fr1 = static_cast<float>(r1) / COLOR_BYTE_MAX;
    const float fg1 = static_cast<float>(g1) / COLOR_BYTE_MAX;
    const float fb1 = static_cast<float>(b1) / COLOR_BYTE_MAX;

    const float fr2 = static_cast<float>(r2) / COLOR_BYTE_MAX;
    const float fg2 = static_cast<float>(g2) / COLOR_BYTE_MAX;
    const float fb2 = static_cast<float>(b2) / COLOR_BYTE_MAX;

    // Compute Euclidean distance
    const float dr = fr1 - fr2;
    const float dg = fg1 - fg2;
    const float db = fb1 - fb2;

    // Return normalized distance (max distance in RGB cube is √3)
    return sqrtf(dr * dr + dg * dg + db * db) / 1.732050808f;
}

/**
 * Trace a single ray and return the RGB color.
 *
 * Helper function to trace a ray through the scene and get back the color.
 *
 * @param ray_origin Ray origin point
 * @param ray_direction Ray direction (normalized)
 * @param r Output: red channel (0-255)
 * @param g Output: green channel (0-255)
 * @param b Output: blue channel (0-255)
 */
__device__ void traceRay(
    const float3& ray_origin,
    const float3& ray_direction,
    unsigned int& r,
    unsigned int& g,
    unsigned int& b
) {
    unsigned int depth = 0;
    optixTrace(
        params.handle,
        ray_origin,
        ray_direction,
        0.0f,
        MAX_RAY_DISTANCE,
        0.0f,
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        0, 2, 0,  // ray_type=0 (primary), stride=2, miss_index=0
        r, g, b, depth
    );

    // Track AA ray statistics (this function is only called from AA subdivision)
    if (params.stats) {
        atomicAdd(&params.stats->aa_rays, 1ULL);
        atomicAdd(&params.stats->total_rays, 1ULL);
    }
}

/**
 * Stack entry for iterative adaptive antialiasing.
 * OptiX doesn't support recursive device functions, so we use an explicit stack.
 */
struct AAStackEntry {
    float center_u;
    float center_v;
    float half_size;
    int depth;
};

// Maximum stack size: at max depth 4 with 3×3 subdivision, we'd need at most 9^4 entries
// but we process breadth-first so max stack size is 9 * max_depth = 36
constexpr int AA_STACK_SIZE = 64;

/**
 * Iteratively subdivide pixel into 3×3 grid with adaptive sampling.
 * Uses explicit stack instead of recursion (required for OptiX).
 *
 * @param center_u Center U coordinate in normalized device coordinates
 * @param center_v Center V coordinate in normalized device coordinates
 * @param half_size Half-width of current pixel/sub-pixel in NDC
 * @param depth Current recursion depth (0 = top level)
 * @param camera_u Camera right vector
 * @param camera_v Camera up vector
 * @param camera_w Camera forward vector
 * @param ray_origin Camera position
 * @param sum_r Output: accumulated red (will be divided by sample count)
 * @param sum_g Output: accumulated green
 * @param sum_b Output: accumulated blue
 * @param sample_count Output: number of samples accumulated
 */
__device__ void subdividePixel(
    float init_center_u,
    float init_center_v,
    float init_half_size,
    int init_depth,
    const float3& camera_u,
    const float3& camera_v,
    const float3& camera_w,
    const float3& ray_origin,
    unsigned long long& sum_r,
    unsigned long long& sum_g,
    unsigned long long& sum_b,
    unsigned int& sample_count
) {
    // Explicit stack for iterative processing
    AAStackEntry stack[AA_STACK_SIZE];
    int stack_top = 0;

    // Push initial entry
    stack[stack_top++] = {init_center_u, init_center_v, init_half_size, init_depth};

    while (stack_top > 0) {
        // Pop entry from stack
        const AAStackEntry entry = stack[--stack_top];
        const float center_u = entry.center_u;
        const float center_v = entry.center_v;
        const float half_size = entry.half_size;
        const int depth = entry.depth;

        // Sample 3×3 grid within current region
        unsigned int samples[9][3];  // RGB for each of 9 samples
        float max_diff = 0.0f;

        // Grid positions: -1, 0, +1 (in units of half_size/1.5 for 3×3 subdivision)
        const float step = half_size / 1.5f;

        for (int iy = 0; iy < 3; ++iy) {
            for (int ix = 0; ix < 3; ++ix) {
                const int idx = iy * 3 + ix;

                // Calculate sample position
                const float u = center_u + (ix - 1) * step;
                const float v = center_v + (iy - 1) * step;

                // Construct ray direction
                const float3 ray_dir = normalize(camera_u * u + camera_v * v + camera_w);

                // Trace ray
                traceRay(ray_origin, ray_dir, samples[idx][0], samples[idx][1], samples[idx][2]);

                // Check color difference with neighbors for edge detection
                if (ix > 0) {
                    const int left_idx = iy * 3 + (ix - 1);
                    const float diff = colorDistance(
                        samples[idx][0], samples[idx][1], samples[idx][2],
                        samples[left_idx][0], samples[left_idx][1], samples[left_idx][2]
                    );
                    max_diff = fmaxf(max_diff, diff);
                }

                if (iy > 0) {
                    const int top_idx = (iy - 1) * 3 + ix;
                    const float diff = colorDistance(
                        samples[idx][0], samples[idx][1], samples[idx][2],
                        samples[top_idx][0], samples[top_idx][1], samples[top_idx][2]
                    );
                    max_diff = fmaxf(max_diff, diff);
                }
            }
        }

        // Edge detected and can recurse further?
        const bool should_subdivide = (max_diff > params.aa_threshold) && (depth < params.aa_max_depth);

        if (should_subdivide && stack_top + 9 <= AA_STACK_SIZE) {
            // Push 9 sub-pixel tasks onto stack
            const float new_half_size = half_size / 3.0f;

            for (int iy = 0; iy < 3; ++iy) {
                for (int ix = 0; ix < 3; ++ix) {
                    const float sub_u = center_u + (ix - 1) * step;
                    const float sub_v = center_v + (iy - 1) * step;

                    stack[stack_top++] = {sub_u, sub_v, new_half_size, depth + 1};
                }
            }
        } else {
            // No edge, max depth reached, or stack full - accumulate these 9 samples
            for (int i = 0; i < 9; ++i) {
                sum_r += samples[i][0];
                sum_g += samples[i][1];
                sum_b += samples[i][2];
                sample_count++;
            }
        }
    }
}

//==============================================================================
// Custom Sphere Intersection Program
// From NVIDIA OptiX SDK 9.0 sphere.cu
// Uses normalized direction + length correction for numerical stability
//==============================================================================
extern "C" __global__ void __intersection__sphere()
{
    const HitGroupData* hit_data = reinterpret_cast<HitGroupData*>(optixGetSbtDataPointer());

    const float3 ray_orig = optixGetWorldRayOrigin();
    const float3 ray_dir  = optixGetWorldRayDirection();
    const float  ray_tmin = optixGetRayTmin();
    const float  ray_tmax = optixGetRayTmax();

    const float3 center = make_float3(
        hit_data->sphere_center[0],
        hit_data->sphere_center[1],
        hit_data->sphere_center[2]
    );
    const float radius = hit_data->sphere_radius;

    // SDK approach: normalize ray direction and track length separately
    // This provides better numerical stability
    const float3 O = ray_orig - center;
    const float  l = 1.0f / length(ray_dir);  // Inverse length
    const float3 D = ray_dir * l;             // Normalized direction

    // Ray-sphere intersection with normalized direction
    float b    = dot(O, D);
    float c    = dot(O, O) - radius * radius;
    float disc = b * b - c;

    if (disc > 0.0f)
    {
        float sdisc        = sqrtf(disc);
        float root1        = (-b - sdisc);  // Near intersection (in normalized space)
        float root11       = 0.0f;
        bool  check_second = true;

        // Numerical refinement for large distances (SDK feature)
        const bool do_refine = fabsf(root1) > (SPHERE_INTERSECTION_REFINEMENT_THRESHOLD * radius);
        if (do_refine)
        {
            // Refine root1 for better accuracy
            float3 O1 = O + root1 * D;
            b         = dot(O1, D);
            c         = dot(O1, O1) - radius * radius;
            disc      = b * b - c;

            if (disc > 0.0f)
            {
                sdisc  = sqrtf(disc);
                root11 = (-b - sdisc);
            }
        }

        // Report near intersection (entry)
        float  t;
        float3 normal;
        t = (root1 + root11) * l;  // Convert back to real t using inverse length
        if (t > ray_tmin && t < ray_tmax)
        {
            normal = (O + (root1 + root11) * D) / radius;  // Outward normal
            if (optixReportIntersection(t, 0,
                __float_as_uint(normal.x),
                __float_as_uint(normal.y),
                __float_as_uint(normal.z),
                __float_as_uint(radius)))
                check_second = false;
        }

        // Report far intersection (exit)
        if (check_second)
        {
            float root2 = (-b + sdisc) + (do_refine ? root1 : 0);
            t           = root2 * l;  // Convert back to real t
            normal      = (O + root2 * D) / radius;  // Outward normal
            if (t > ray_tmin && t < ray_tmax)
                optixReportIntersection(t, 1,  // hit_kind = 1 for EXIT
                    __float_as_uint(normal.x),
                    __float_as_uint(normal.y),
                    __float_as_uint(normal.z),
                    __float_as_uint(radius));
        }
    }
}

//==============================================================================
// Ray generation shader - generates primary rays from camera
//==============================================================================
extern "C" __global__ void __raygen__rg() {
    // Get ray generation data (camera parameters)
    const RayGenData* raygen_data = reinterpret_cast<RayGenData*>(optixGetSbtDataPointer());

    // Get pixel coordinates
    const uint3 idx = optixGetLaunchIndex();
    const uint3 dim = optixGetLaunchDimensions();

    // Calculate normalized device coordinates [-1, 1]
    // Screen convention: idx.y=0 is top of screen, idx.y=height is bottom
    // For floor at bottom: top screen should look up (positive v), bottom should look down (negative v)
    // Since idx.y increases downward but v should increase upward, we need to flip:
    // idx.y=0 → v=+1 (top of screen looks up), idx.y=height → v=-1 (bottom looks down)
    const float u = (static_cast<float>(idx.x) + 0.5f) / static_cast<float>(dim.x) * 2.0f - 1.0f;
    const float v = 1.0f - (static_cast<float>(idx.y) + 0.5f) / static_cast<float>(dim.y) * 2.0f;

    // Construct ray direction from camera basis vectors
    const float3 ray_origin = make_float3(
        raygen_data->cam_eye[0],
        raygen_data->cam_eye[1],
        raygen_data->cam_eye[2]
    );

    const float3 camera_u = make_float3(raygen_data->camera_u[0], raygen_data->camera_u[1], raygen_data->camera_u[2]);
    const float3 camera_v = make_float3(raygen_data->camera_v[0], raygen_data->camera_v[1], raygen_data->camera_v[2]);
    const float3 camera_w = make_float3(raygen_data->camera_w[0], raygen_data->camera_w[1], raygen_data->camera_w[2]);

    unsigned int r, g, b;

    if (params.aa_enabled) {
        // Adaptive antialiasing: recursively subdivide pixel if edge detected
        unsigned long long sum_r = 0, sum_g = 0, sum_b = 0;
        unsigned int sample_count = 0;

        // Calculate pixel half-size in NDC (for initial subdivision)
        const float pixel_half_width = 1.0f / static_cast<float>(dim.x);
        const float pixel_half_height = 1.0f / static_cast<float>(dim.y);
        const float pixel_half_size = fmaxf(pixel_half_width, pixel_half_height);

        // Start recursive subdivision at depth 0
        subdividePixel(
            u, v, pixel_half_size, 0,
            camera_u, camera_v, camera_w, ray_origin,
            sum_r, sum_g, sum_b, sample_count
        );

        // Average the accumulated samples
        r = static_cast<unsigned int>(sum_r / sample_count);
        g = static_cast<unsigned int>(sum_g / sample_count);
        b = static_cast<unsigned int>(sum_b / sample_count);

    } else {
        // Standard rendering: single ray per pixel
        const float3 ray_direction = normalize(camera_u * u + camera_v * v + camera_w);

        // Track primary ray in statistics
        if (params.stats) {
            atomicAdd(&params.stats->primary_rays, 1ULL);
            atomicAdd(&params.stats->total_rays, 1ULL);
        }

        // Trace ray
        unsigned int p3 = 0;  // Initial depth = 0
        optixTrace(
            params.handle,
            ray_origin,
            ray_direction,
            0.0f,
            MAX_RAY_DISTANCE,
            0.0f,
            OptixVisibilityMask(255),
            OPTIX_RAY_FLAG_NONE,
            0, 2, 0,  // ray_type=0 (primary), stride=2, miss_index=0
            r, g, b, p3
        );
    }

    // Write to output buffer
    const unsigned int pixel_index = idx.y * params.image_width + idx.x;
    params.image[pixel_index * 4 + 0] = static_cast<unsigned char>(r);
    params.image[pixel_index * 4 + 1] = static_cast<unsigned char>(g);
    params.image[pixel_index * 4 + 2] = static_cast<unsigned char>(b);
    params.image[pixel_index * 4 + 3] = 255;  // Alpha
}

//==============================================================================
// Miss shader - returns checkered plane or background color when ray hits nothing
//==============================================================================
extern "C" __global__ void __miss__ms() {
    // Get miss data (background color)
    const MissData* miss_data = reinterpret_cast<MissData*>(optixGetSbtDataPointer());

    // Get ray data
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();

    // Extract plane parameters from params (moved from SBT for performance)
    const int plane_axis = params.plane_axis;         // 0=X, 1=Y, 2=Z
    const float plane_value = params.plane_value;     // Position along axis
    // Note: plane_positive (normal direction) is currently not used - plane is visible from both sides

    // Get ray origin/direction component for the plane axis
    float ray_orig_comp, ray_dir_comp;
    if (plane_axis == 0) {  // X axis
        ray_orig_comp = ray_origin.x;
        ray_dir_comp = ray_direction.x;
    } else if (plane_axis == 1) {  // Y axis
        ray_orig_comp = ray_origin.y;
        ray_dir_comp = ray_direction.y;
    } else {  // Z axis
        ray_orig_comp = ray_origin.z;
        ray_dir_comp = ray_direction.z;
    }

    unsigned int r, g, b;

    // Plane visible from both sides - just check for valid intersection
    // Only compute if ray direction component is non-zero
    if (fabsf(ray_dir_comp) > 1e-6f) {
        const float t = (plane_value - ray_orig_comp) / ray_dir_comp;

        if (t > 0.0f) {  // Intersection is in front of ray origin
            // Compute full intersection point
            const float3 hit_point = ray_origin + ray_direction * t;

            // For checker pattern, use the two components perpendicular to plane axis
            float checker_u, checker_v;
            if (plane_axis == 0) {  // X axis plane (YZ plane)
                checker_u = hit_point.y;
                checker_v = hit_point.z;
            } else if (plane_axis == 1) {  // Y axis plane (XZ plane)
                checker_u = hit_point.x;
                checker_v = hit_point.z;
            } else {  // Z axis plane (XY plane)
                checker_u = hit_point.x;
                checker_v = hit_point.y;
            }

            // Plane coloring: solid or checkerboard using custom colors (0.0-1.0 floats)
            float3 plane_rgb;
            if (params.plane_solid_color) {
                // Solid color from params
                plane_rgb = make_float3(
                    params.plane_color1[0],
                    params.plane_color1[1],
                    params.plane_color1[2]
                );
            } else {
                // Checkered pattern with two custom colors
                const float checker_size = PLANE_CHECKER_SIZE;
                const int check_u = static_cast<int>(floorf(checker_u / checker_size));
                const int check_v = static_cast<int>(floorf(checker_v / checker_size));

                // XOR to create checkerboard pattern
                const bool is_light = ((check_u + check_v) & 1) == 0;

                if (is_light) {
                    plane_rgb = make_float3(
                        params.plane_color1[0],
                        params.plane_color1[1],
                        params.plane_color1[2]
                    );
                } else {
                    plane_rgb = make_float3(
                        params.plane_color2[0],
                        params.plane_color2[1],
                        params.plane_color2[2]
                    );
                }
            }
            // Convert 0.0-1.0 to 0-255
            r = static_cast<unsigned int>(plane_rgb.x * COLOR_BYTE_MAX);
            g = static_cast<unsigned int>(plane_rgb.y * COLOR_BYTE_MAX);
            b = static_cast<unsigned int>(plane_rgb.z * COLOR_BYTE_MAX);

            // Apply lighting from multiple light sources to plane
            // Compute plane normal based on axis
            float3 plane_normal;
            if (plane_axis == 0) {  // X axis
                plane_normal = make_float3(1.0f, 0.0f, 0.0f);
            } else if (plane_axis == 1) {  // Y axis
                plane_normal = make_float3(0.0f, 1.0f, 0.0f);
            } else {  // Z axis
                plane_normal = make_float3(0.0f, 0.0f, 1.0f);
            }

            // Calculate lighting (double-sided for plane)
            const float3 lighting = calculateLighting(hit_point, plane_normal, true);
            r = static_cast<unsigned int>(r * lighting.x);
            g = static_cast<unsigned int>(g * lighting.y);
            b = static_cast<unsigned int>(b * lighting.z);
        } else {
            // No intersection (t < 0), use background color
            r = static_cast<unsigned int>(miss_data->r * COLOR_SCALE_FACTOR);
            g = static_cast<unsigned int>(miss_data->g * COLOR_SCALE_FACTOR);
            b = static_cast<unsigned int>(miss_data->b * COLOR_SCALE_FACTOR);
        }
    } else {
        // Ray not pointing towards plane, use background color
        r = static_cast<unsigned int>(miss_data->r * COLOR_SCALE_FACTOR);
        g = static_cast<unsigned int>(miss_data->g * COLOR_SCALE_FACTOR);
        b = static_cast<unsigned int>(miss_data->b * COLOR_SCALE_FACTOR);
    }

    // Set payload
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}

//==============================================================================
// Closest hit shader - computes Fresnel reflection and Snell's law refraction
//==============================================================================
extern "C" __global__ void __closesthit__ch() {
    // Get hit group data
    const HitGroupData* hit_data = reinterpret_cast<HitGroupData*>(optixGetSbtDataPointer());

    // Get hit point (ray origin + t * ray direction)
    const float t = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    const float3 hit_point = ray_origin + ray_direction * t;

    // Get hit type from custom intersection program
    const unsigned int hit_kind = optixGetHitKind();
    const bool entering = (hit_kind == 0);  // 0=entry, 1=exit

    // Get surface normal from intersection attributes
    const float3 outward_normal = make_float3(
        __uint_as_float(optixGetAttribute_0()),
        __uint_as_float(optixGetAttribute_1()),
        __uint_as_float(optixGetAttribute_2())
    );

    // Surface normal (points toward incoming ray)
    const float3 normal = entering ? outward_normal : make_float3(-outward_normal.x, -outward_normal.y, -outward_normal.z);

    // Get current depth from payload
    const unsigned int depth = optixGetPayload_3();

    // Track depth statistics
    if (params.stats) {
        atomicMax(&params.stats->max_depth_reached, depth + 1);
        atomicMin(&params.stats->min_depth_reached, depth + 1);
    }

    // Get sphere alpha from params (moved from SBT for performance)
    const float sphere_alpha = params.sphere_color[3];

    // Handle fully transparent spheres - trace continuation ray through sphere
    if (sphere_alpha < ALPHA_FULLY_TRANSPARENT_THRESHOLD) {
        // Sphere is fully transparent, let ray continue as if sphere doesn't exist
        const float3 continue_origin = hit_point + ray_direction * CONTINUATION_RAY_OFFSET;
        unsigned int continue_r = 0, continue_g = 0, continue_b = 0;
        unsigned int next_depth = depth;  // Don't increment depth for transparent pass-through

        optixTrace(
            params.handle,
            continue_origin,
            ray_direction,  // Continue in same direction
            CONTINUATION_RAY_OFFSET,
            MAX_RAY_DISTANCE,
            0.0f,
            OptixVisibilityMask(255),
            OPTIX_RAY_FLAG_NONE,
            0, 2, 0,  // ray_type=0 (primary), stride=2, miss_index=0
            continue_r, continue_g, continue_b, next_depth
        );

        optixSetPayload_0(continue_r);
        optixSetPayload_1(continue_g);
        optixSetPayload_2(continue_b);
        return;
    }

    // Handle fully opaque spheres (alpha >= threshold) - solid surface with diffuse shading
    if (sphere_alpha >= ALPHA_FULLY_OPAQUE_THRESHOLD) {
        // Calculate lighting (single-sided for sphere)
        const float3 lighting = calculateLighting(hit_point, normal);

        // Get sphere color from params (RGB only, ignore alpha for solid spheres)
        const float3 sphere_color = make_float3(
            params.sphere_color[0],
            params.sphere_color[1],
            params.sphere_color[2]
        );

        // Apply lighting to solid sphere surface (component-wise multiplication)
        const float3 lit_color = make_float3(
            sphere_color.x * lighting.x,
            sphere_color.y * lighting.y,
            sphere_color.z * lighting.z
        );

        const unsigned int r = static_cast<unsigned int>(fminf(lit_color.x * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
        const unsigned int g = static_cast<unsigned int>(fminf(lit_color.y * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));
        const unsigned int b = static_cast<unsigned int>(fminf(lit_color.z * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX));

        optixSetPayload_0(r);
        optixSetPayload_1(g);
        optixSetPayload_2(b);
        return;
    }

    // If max depth reached, trace one final non-recursive ray to get background/plane color
    // This avoids black artifacts from depth cutoff
    if (depth >= MAX_TRACE_DEPTH) {
        // Compute reflection direction for final ray
        const float cos_theta = fabsf(dot(ray_direction, normal));
        const float3 reflect_dir = make_float3(
            ray_direction.x - 2.0f * cos_theta * normal.x,
            ray_direction.y - 2.0f * cos_theta * normal.y,
            ray_direction.z - 2.0f * cos_theta * normal.z
        );

        // Trace one final ray at MAX_TRACE_DEPTH (will not recurse further)
        unsigned int final_r = 0, final_g = 0, final_b = 0;
        unsigned int final_depth = MAX_TRACE_DEPTH;  // Keep at max depth to prevent recursion
        const float3 final_origin = hit_point + reflect_dir * CONTINUATION_RAY_OFFSET;
        optixTrace(
            params.handle,
            final_origin,
            reflect_dir,
            CONTINUATION_RAY_OFFSET,
            MAX_RAY_DISTANCE,
            0.0f,
            OptixVisibilityMask(255),
            OPTIX_RAY_FLAG_NONE,
            0, 2, 0,  // ray_type=0 (primary), stride=2, miss_index=0
            final_r, final_g, final_b, final_depth
        );

        optixSetPayload_0(final_r);
        optixSetPayload_1(final_g);
        optixSetPayload_2(final_b);
        return;
    }

    // Compute Fresnel reflectance using Schlick approximation
    // Get IOR from params (moved from SBT for performance)
    const float n1 = entering ? 1.0f : params.sphere_ior;
    const float n2 = entering ? params.sphere_ior : 1.0f;
    const float r0 = (n1 - n2) / (n1 + n2);
    const float R0 = r0 * r0;
    const float cos_theta = fabsf(dot(ray_direction, normal));
    const float one_minus_cos = 1.0f - cos_theta;
    const float fresnel = R0 + (1.0f - R0) * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos;

    // Compute reflection direction: r = d - 2(d·n)n
    const float3 reflect_dir = make_float3(
        ray_direction.x - 2.0f * cos_theta * normal.x,
        ray_direction.y - 2.0f * cos_theta * normal.y,
        ray_direction.z - 2.0f * cos_theta * normal.z
    );

    // Trace reflected ray
    if (params.stats) {
        atomicAdd(&params.stats->reflected_rays, 1ULL);
        atomicAdd(&params.stats->total_rays, 1ULL);
    }

    unsigned int reflect_r = 0, reflect_g = 0, reflect_b = 0;
    const float3 reflect_origin = hit_point + reflect_dir * CONTINUATION_RAY_OFFSET;
    unsigned int next_depth = depth + 1;
    optixTrace(
        params.handle,
        reflect_origin,
        reflect_dir,
        CONTINUATION_RAY_OFFSET,
        MAX_RAY_DISTANCE,
        0.0f,
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        0, 2, 0,  // ray_type=0 (primary), stride=2, miss_index=0
        reflect_r, reflect_g, reflect_b, next_depth
    );

    // Compute refraction with Snell's law
    const float eta = n1 / n2;
    const float k = 1.0f - eta * eta * (1.0f - cos_theta * cos_theta);

    unsigned int refract_r = 0, refract_g = 0, refract_b = 0;
    if (k >= 0.0f) {
        // Refraction possible
        if (params.stats) {
            atomicAdd(&params.stats->refracted_rays, 1ULL);
            atomicAdd(&params.stats->total_rays, 1ULL);
        }

        const float coeff = eta * cos_theta - sqrtf(k);
        const float3 refract_dir = make_float3(
            eta * ray_direction.x + coeff * normal.x,
            eta * ray_direction.y + coeff * normal.y,
            eta * ray_direction.z + coeff * normal.z
        );

        const float3 refract_origin = hit_point + refract_dir * CONTINUATION_RAY_OFFSET;
        optixTrace(
            params.handle,
            refract_origin,
            refract_dir,
            CONTINUATION_RAY_OFFSET,
            MAX_RAY_DISTANCE,
            0.0f,
            OptixVisibilityMask(255),
            OPTIX_RAY_FLAG_NONE,
            0, 2, 0,  // ray_type=0 (primary), stride=2, miss_index=0
            refract_r, refract_g, refract_b, next_depth
        );
    } else {
        // Total internal reflection - use reflected ray result for refraction too
        refract_r = reflect_r;
        refract_g = reflect_g;
        refract_b = reflect_b;
    }

    // Apply Beer-Lambert absorption to refracted component (only at EXIT)
    float3 refract_color = make_float3(
        static_cast<float>(refract_r) / RayTracingConstants::COLOR_BYTE_MAX,
        static_cast<float>(refract_g) / RayTracingConstants::COLOR_BYTE_MAX,
        static_cast<float>(refract_b) / RayTracingConstants::COLOR_BYTE_MAX
    );

    if (!entering)  // Exiting - apply absorption to refracted ray
    {
        // Get glass color and alpha from params (moved from SBT for performance)
        const float3 glass_color = make_float3(
            params.sphere_color[0],
            params.sphere_color[1],
            params.sphere_color[2]
        );
        const float glass_alpha = params.sphere_color[3];

        // Calculate extinction constant from color and alpha
        // STANDARD GRAPHICS ALPHA CONVENTION:
        // alpha=0.0 → fully transparent (no absorption)
        // alpha=1.0 → fully opaque (maximum absorption)
        const float absorption_scale = BEER_LAMBERT_ABSORPTION_SCALE;
        const float3 extinction_constant = make_float3(
            -logf(fmaxf(glass_color.x, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
            -logf(fmaxf(glass_color.y, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
            -logf(fmaxf(glass_color.z, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale
        );

        // Apply Beer-Lambert using t (which varies from ~0.7 at edges to ~1.2 at center)
        // Get scale from params (moved from SBT for performance)
        const float3 beer_attenuation = make_float3(
            expf(-extinction_constant.x * t * params.sphere_scale),
            expf(-extinction_constant.y * t * params.sphere_scale),
            expf(-extinction_constant.z * t * params.sphere_scale)
        );

        // Apply absorption to refracted ray
        refract_color = refract_color * beer_attenuation;
    }

    // Blend reflected and refracted rays using Fresnel coefficient
    const float3 reflect_color = make_float3(
        static_cast<float>(reflect_r) / RayTracingConstants::COLOR_BYTE_MAX,
        static_cast<float>(reflect_g) / RayTracingConstants::COLOR_BYTE_MAX,
        static_cast<float>(reflect_b) / RayTracingConstants::COLOR_BYTE_MAX
    );

    // Final color = fresnel * reflected + (1 - fresnel) * refracted
    const float3 final_color = make_float3(
        fresnel * reflect_color.x + (1.0f - fresnel) * refract_color.x,
        fresnel * reflect_color.y + (1.0f - fresnel) * refract_color.y,
        fresnel * reflect_color.z + (1.0f - fresnel) * refract_color.z
    );

    optixSetPayload_0(static_cast<unsigned int>(fminf(final_color.x * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX)));
    optixSetPayload_1(static_cast<unsigned int>(fminf(final_color.y * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX)));
    optixSetPayload_2(static_cast<unsigned int>(fminf(final_color.z * RayTracingConstants::COLOR_BYTE_MAX, RayTracingConstants::COLOR_BYTE_MAX)));
}

//==============================================================================
// Shadow ray miss shader - light is visible (no occlusion)
//==============================================================================
extern "C" __global__ void __miss__shadow() {
    // Shadow ray missed all geometry - no occlusion, return 0.0 attenuation
    optixSetPayload_0(__float_as_uint(0.0f));
}

//==============================================================================
// Shadow ray closest hit shader - marks shadow ray as occluded with transparency
//==============================================================================
extern "C" __global__ void __closesthit__shadow() {
    // Shadow ray hit the sphere - return alpha as shadow attenuation
    // alpha=0.0 (transparent) → attenuation=0.0 (no shadow)
    // alpha=1.0 (opaque) → attenuation=1.0 (full shadow)
    const float sphere_alpha = params.sphere_color[3];

    // Pack float as bits into unsigned int payload
    optixSetPayload_0(__float_as_uint(sphere_alpha));
}

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

    // Generate camera ray (same as __raygen__rg)
    // Screen convention: idx.y=0 is top, so flip to get v=+1 at top, v=-1 at bottom
    const float u = (static_cast<float>(idx.x) + 0.5f) / static_cast<float>(dim.x) * 2.0f - 1.0f;
    const float v = 1.0f - (static_cast<float>(idx.y) + 0.5f) / static_cast<float>(dim.y) * 2.0f;
    const float2 d = make_float2(u, v);

    const float3 cam_eye = make_float3(rt_data->cam_eye[0], rt_data->cam_eye[1], rt_data->cam_eye[2]);
    const float3 cam_u = make_float3(rt_data->camera_u[0], rt_data->camera_u[1], rt_data->camera_u[2]);
    const float3 cam_v = make_float3(rt_data->camera_v[0], rt_data->camera_v[1], rt_data->camera_v[2]);
    const float3 cam_w = make_float3(rt_data->camera_w[0], rt_data->camera_w[1], rt_data->camera_w[2]);

    const float3 ray_direction = normalize(d.x * cam_u + d.y * cam_v + cam_w);

    // Trace ray to find first hit
    // Payload: p0 = hit_type (0=miss, 1=diffuse/plane, 2=specular/sphere)
    //          p1,p2,p3 = hit position (if diffuse)
    unsigned int p0 = 0, p1 = 0, p2 = 0, p3 = 0;

    optixTrace(
        params.handle,
        cam_eye,
        ray_direction,
        0.0001f,                     // tmin
        MAX_RAY_DISTANCE,            // tmax
        0.0f,                        // rayTime
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        0,                           // SBT offset (primary ray type)
        2,                           // SBT stride
        0,                           // missSBTIndex
        p0, p1, p2, p3
    );

    // Check if we hit a diffuse surface (plane)
    // The miss shader sets p0 differently for plane hits vs background
    // For caustics, we detect plane hits by checking if ray would have hit the plane

    // Re-compute plane intersection here to determine if it's a diffuse hit
    const int plane_axis = params.plane_axis;
    const float plane_value = params.plane_value;

    float ray_orig_comp, ray_dir_comp;
    if (plane_axis == 0) {
        ray_orig_comp = cam_eye.x;
        ray_dir_comp = ray_direction.x;
    } else if (plane_axis == 1) {
        ray_orig_comp = cam_eye.y;
        ray_dir_comp = ray_direction.y;
    } else {
        ray_orig_comp = cam_eye.z;
        ray_dir_comp = ray_direction.z;
    }

    // Check for plane intersection
    if (fabsf(ray_dir_comp) > 1e-6f) {
        const float t_plane = (plane_value - ray_orig_comp) / ray_dir_comp;

        if (t_plane > 0.0f) {
            const float3 plane_hit = cam_eye + ray_direction * t_plane;

            // Check if ray hit sphere first (sphere would have closer t)
            // We can't easily get the sphere t from the trace, so we'll
            // compute it here for comparison
            // For simplicity in this version, we'll trace a separate ray
            // to determine if sphere is in the way

            // For now, assume plane is visible if ray reached miss shader
            // (This is a simplification - in practice we'd need to check)

            // Allocate hit point slot atomically (use pointer to GPU memory, not constant memory)
            const unsigned int hp_idx = atomicAdd(params.caustics.num_hit_points, 1);

            if (hp_idx < MAX_HIT_POINTS) {
                HitPoint& hp = params.caustics.hit_points[hp_idx];

                hp.position[0] = plane_hit.x;
                hp.position[1] = plane_hit.y;
                hp.position[2] = plane_hit.z;

                // Plane normal based on axis
                hp.normal[0] = (plane_axis == 0) ? 1.0f : 0.0f;
                hp.normal[1] = (plane_axis == 1) ? 1.0f : 0.0f;
                hp.normal[2] = (plane_axis == 2) ? 1.0f : 0.0f;

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
    // Get sphere geometry for intersection testing
    // Note: We trace against the same acceleration structure, reusing existing intersection

    for (int bounce = 0; bounce < max_bounces; ++bounce) {
        // Trace photon ray to find next intersection
        // We'll use a simplified approach: check for sphere intersection first,
        // then plane intersection if sphere missed

        // For this implementation, we trace the photon and check what it hits
        // We reuse the primary ray closest hit shader, but interpret results differently

        // Check intersection with sphere analytically
        // Sphere center is at origin (0, 0, 0) by default, radius is configurable
        const float3 sphere_center = make_float3(0.0f, 0.0f, 0.0f);  // Default sphere center
        const float sphere_radius = 1.5f;  // Default radius (from HitGroupData in render)

        const float3 oc = origin - sphere_center;
        const float a = dot(dir, dir);
        const float half_b = dot(oc, dir);
        const float c = dot(oc, oc) - sphere_radius * sphere_radius;
        const float discriminant = half_b * half_b - a * c;

        if (discriminant > 0.0f) {
            // Photon hits sphere - compute intersection point
            const float sqrt_d = sqrtf(discriminant);
            float t = (-half_b - sqrt_d) / a;  // Near intersection

            // Check if intersection is in front
            if (t < 0.001f) {
                t = (-half_b + sqrt_d) / a;  // Try far intersection
            }

            if (t > 0.001f) {
                // Valid sphere intersection
                const float3 hit_point = origin + dir * t;
                const float3 outward_normal = normalize(hit_point - sphere_center);

                // C2: Track sphere hit statistics
                if (params.caustics.stats) {
                    atomicAdd(&params.caustics.stats->sphere_hits, 1ULL);
                }

                // Determine if entering or exiting
                const bool entering = dot(dir, outward_normal) < 0.0f;
                const float3 normal = entering ? outward_normal : make_float3(-outward_normal.x, -outward_normal.y, -outward_normal.z);

                // Get material properties
                const float n1 = entering ? 1.0f : params.sphere_ior;
                const float n2 = entering ? params.sphere_ior : 1.0f;
                const float eta = n1 / n2;

                const float cos_theta_i = fabsf(dot(dir, normal));
                const float sin_theta_i_sq = 1.0f - cos_theta_i * cos_theta_i;
                const float sin_theta_t_sq = eta * eta * sin_theta_i_sq;

                // Apply Beer-Lambert absorption when exiting
                if (!entering) {
                    const float3 glass_color = make_float3(
                        params.sphere_color[0],
                        params.sphere_color[1],
                        params.sphere_color[2]
                    );
                    const float glass_alpha = params.sphere_color[3];

                    // Calculate extinction from color and alpha
                    const float absorption_scale = BEER_LAMBERT_ABSORPTION_SCALE;
                    const float3 extinction = make_float3(
                        -logf(fmaxf(glass_color.x, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
                        -logf(fmaxf(glass_color.y, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale,
                        -logf(fmaxf(glass_color.z, COLOR_CHANNEL_MIN_SAFE_VALUE)) * glass_alpha * absorption_scale
                    );

                    // Beer-Lambert attenuation
                    const float3 attenuation = make_float3(
                        expf(-extinction.x * t * params.sphere_scale),
                        expf(-extinction.y * t * params.sphere_scale),
                        expf(-extinction.z * t * params.sphere_scale)
                    );

                    // C5: Track absorption losses before modifying flux
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

                // Check for total internal reflection
                if (sin_theta_t_sq > 1.0f) {
                    // Total internal reflection
                    // C3: Track TIR events
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
                    // C3: Track refraction events
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

                continue;  // Continue tracing refracted/reflected photon
            }
        }

        // Photon missed sphere - check plane intersection
        const int plane_axis = params.plane_axis;
        const float plane_value = params.plane_value;

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

        if (fabsf(ray_dir_comp) > 1e-6f) {
            const float t_plane = (plane_value - ray_orig_comp) / ray_dir_comp;

            if (t_plane > 0.001f) {
                // Photon hits the diffuse plane - deposit energy
                const float3 plane_hit = origin + dir * t_plane;
                depositPhoton(plane_hit, dir, flux);
                return;  // Photon absorbed by diffuse surface
            }
        }

        // C2: Track sphere miss (photon missed sphere on this bounce)
        if (params.caustics.stats && bounce == 0) {
            // Only count misses on first bounce (direct from light)
            atomicAdd(&params.caustics.stats->sphere_misses, 1ULL);
        }

        // Photon escaped scene
        return;
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
    float3 photon_flux;

    if (light.type == LightType::DIRECTIONAL) {
        // Directional light: emit parallel rays targeting the sphere
        // Sample a disk perpendicular to light direction, centered on sphere
        const float3 light_dir = normalize(make_float3(
            light.direction[0],
            light.direction[1],
            light.direction[2]
        ));

        // Create tangent space around light direction
        float3 tangent, bitangent;
        createONB(light_dir, tangent, bitangent);

        // Sample disk with radius large enough to cover sphere (radius ~1.5)
        const float disk_radius = 3.0f;  // Cover sphere + margin
        const float2 disk_sample = sampleDisk(rnd(seed), rnd(seed));

        // Photon starts from disk behind the sphere
        const float3 sphere_center = make_float3(0.0f, 0.0f, 0.0f);
        photon_origin = sphere_center
                       + tangent * (disk_sample.x * disk_radius)
                       + bitangent * (disk_sample.y * disk_radius)
                       - light_dir * 20.0f;  // Start well behind sphere

        photon_dir = light_dir;

    } else {
        // Point light: emit in random direction from light position
        photon_origin = make_float3(
            light.position[0],
            light.position[1],
            light.position[2]
        );
        photon_dir = sampleSphere(rnd(seed), rnd(seed));
    }

    // Calculate photon flux
    // Total flux from light is distributed among all photons
    const float total_photons = static_cast<float>(dim.x * dim.y);
    photon_flux = make_float3(
        light.color[0] * light.intensity / total_photons,
        light.color[1] * light.intensity / total_photons,
        light.color[2] * light.intensity / total_photons
    );

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
    if (flux_magnitude < 1e-10f) return;

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
    if (area < 1e-10f) return;

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
        // Use atomicMax for peak brightness (requires casting to int for atomic compare)
        // Simple approach: just track using atomic operations
        atomicMax(reinterpret_cast<int*>(&params.caustics.stats->max_caustic_brightness),
                  __float_as_int(caustic_brightness));
    }

    // Write back to image buffer
    params.image[pixel_idx + 0] = static_cast<unsigned char>(new_r * COLOR_BYTE_MAX);
    params.image[pixel_idx + 1] = static_cast<unsigned char>(new_g * COLOR_BYTE_MAX);
    params.image[pixel_idx + 2] = static_cast<unsigned char>(new_b * COLOR_BYTE_MAX);
}
