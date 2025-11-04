#include <optix.h>
#include "../include/OptiXData.h"

extern "C" {
    __constant__ Params params;
}

// Constants for ray tracing
namespace Constants {
    constexpr float MAX_RAY_DISTANCE = 1e16f;
    constexpr float COLOR_SCALE_FACTOR = 255.99f;  // Slightly less than 256 to avoid overflow
    constexpr float CONTINUATION_RAY_OFFSET = 0.001f;  // Small offset to avoid self-intersection
}

// Device-side vector math helper functions
__device__ inline float3 normalize(float3 v) {
    const float len = sqrtf(v.x * v.x + v.y * v.y + v.z * v.z);
    return make_float3(v.x / len, v.y / len, v.z / len);
}

__device__ inline float dot(float3 a, float3 b) {
    return a.x * b.x + a.y * b.y + a.z * b.z;
}

__device__ inline float3 operator+(float3 a, float3 b) {
    return make_float3(a.x + b.x, a.y + b.y, a.z + b.z);
}

__device__ inline float3 operator*(float3 v, float s) {
    return make_float3(v.x * s, v.y * s, v.z * s);
}

// Payload packing/unpacking utilities
// Payload layout: p0=R, p1=G, p2=B, p3=entry_distance, p4=absorption_r, p5=absorption_g, p6=absorption_b
__device__ inline float uint_as_float(unsigned int ui) {
    return __uint_as_float(ui);
}

__device__ inline unsigned int float_as_uint(float f) {
    return __float_as_uint(f);
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
    const float u = (static_cast<float>(idx.x) + 0.5f) / static_cast<float>(dim.x) * 2.0f - 1.0f;
    const float v = (static_cast<float>(idx.y) + 0.5f) / static_cast<float>(dim.y) * 2.0f - 1.0f;

    // Construct ray direction from camera basis vectors
    const float3 ray_origin = make_float3(
        raygen_data->cam_eye[0],
        raygen_data->cam_eye[1],
        raygen_data->cam_eye[2]
    );

    const float3 camera_u = make_float3(raygen_data->camera_u[0], raygen_data->camera_u[1], raygen_data->camera_u[2]);
    const float3 camera_v = make_float3(raygen_data->camera_v[0], raygen_data->camera_v[1], raygen_data->camera_v[2]);
    const float3 camera_w = make_float3(raygen_data->camera_w[0], raygen_data->camera_w[1], raygen_data->camera_w[2]);

    const float3 ray_direction = normalize(camera_u * u + camera_v * v + camera_w);

    // Trace ray with payload:
    // - RGB color channels (as uint)
    // - entry_distance: t-value where ray entered current volume (as float packed in uint)
    // - absorption coefficients for each color channel (as float packed in uint)
    unsigned int color_r, color_g, color_b;
    unsigned int entry_distance = float_as_uint(-1.0f);  // -1 = not inside any volume
    unsigned int absorption_r = float_as_uint(0.0f);
    unsigned int absorption_g = float_as_uint(0.0f);
    unsigned int absorption_b = float_as_uint(0.0f);

    optixTrace(
        params.handle,                     // Acceleration structure
        ray_origin,                        // Ray origin
        ray_direction,                     // Ray direction
        0.0f,                              // tmin
        Constants::MAX_RAY_DISTANCE,       // tmax
        0.0f,                              // rayTime
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        0,                       // SBT offset
        1,                       // SBT stride
        0,                       // missSBTIndex
        color_r, color_g, color_b,
        entry_distance,
        absorption_r, absorption_g, absorption_b
    );

    // Convert payload to RGBA
    const unsigned int r = color_r;
    const unsigned int g = color_g;
    const unsigned int b = color_b;

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

    // Checkered plane at z=-10
    const float plane_z = -10.0f;

    // Ray-plane intersection: origin.z + t * direction.z = plane_z
    // Solve for t: t = (plane_z - origin.z) / direction.z
    unsigned int r, g, b;

    if (ray_direction.z < 0.0f) {  // Ray pointing towards plane (negative z)
        const float t = (plane_z - ray_origin.z) / ray_direction.z;

        if (t > 0.0f) {  // Intersection is in front of ray origin
            // Compute intersection point
            const float hit_x = ray_origin.x + t * ray_direction.x;
            const float hit_y = ray_origin.y + t * ray_direction.y;

            // Checkered pattern with 0.5 unit squares
            const float checker_size = 0.5f;
            const int check_x = static_cast<int>(floorf(hit_x / checker_size));
            const int check_y = static_cast<int>(floorf(hit_y / checker_size));

            // XOR to create checkerboard pattern
            const bool is_light = ((check_x + check_y) & 1) == 0;

            // Light gray (240, 240, 240) and dark gray (40, 40, 40)
            if (is_light) {
                r = 240;
                g = 240;
                b = 240;
            } else {
                r = 40;
                g = 40;
                b = 40;
            }
        } else {
            // No intersection (t < 0), use background color
            r = static_cast<unsigned int>(miss_data->r * Constants::COLOR_SCALE_FACTOR);
            g = static_cast<unsigned int>(miss_data->g * Constants::COLOR_SCALE_FACTOR);
            b = static_cast<unsigned int>(miss_data->b * Constants::COLOR_SCALE_FACTOR);
        }
    } else {
        // Ray not pointing towards plane, use background color
        r = static_cast<unsigned int>(miss_data->r * Constants::COLOR_SCALE_FACTOR);
        g = static_cast<unsigned int>(miss_data->g * Constants::COLOR_SCALE_FACTOR);
        b = static_cast<unsigned int>(miss_data->b * Constants::COLOR_SCALE_FACTOR);
    }

    // Set payload (only RGB)
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
    // Payload 3-6 (entry_distance, absorption coefficients) are automatically preserved by OptiX
}

//==============================================================================
// Closest hit shader - computes Fresnel reflection and Snell's law refraction
//==============================================================================
extern "C" __global__ void __closesthit__ch() {
    // DIAGNOSTIC: Track hit count and entry/exit
    const uint3 launch_idx = optixGetLaunchIndex();
    const uint3 launch_dim = optixGetLaunchDimensions();
    const bool is_center_pixel = (launch_idx.x == launch_dim.x/2 && launch_idx.y == launch_dim.y/2);

    // Get hit group data
    const HitGroupData* hit_data = reinterpret_cast<HitGroupData*>(optixGetSbtDataPointer());

    // Get hit point (ray origin + t * ray direction)
    const float t = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    const float3 hit_point = ray_origin + ray_direction * t;

    // DIAGNOSTIC: Print for center pixel only to avoid spam
    if (is_center_pixel) {
        printf("[HIT] t=%.3f, origin=(%.2f,%.2f,%.2f), dir=(%.2f,%.2f,%.2f)\n",
               t, ray_origin.x, ray_origin.y, ray_origin.z,
               ray_direction.x, ray_direction.y, ray_direction.z);
    }

    // Compute surface normal: normal = normalize(hit_point - sphere_center)
    const float3 sphere_center = make_float3(
        hit_data->sphere_center[0],
        hit_data->sphere_center[1],
        hit_data->sphere_center[2]
    );

    // Manual vector subtraction for hit_point - sphere_center
    const float3 to_hit = make_float3(
        hit_point.x - sphere_center.x,
        hit_point.y - sphere_center.y,
        hit_point.z - sphere_center.z
    );
    const float3 outward_normal = normalize(to_hit);

    // Determine if ray is entering or exiting sphere (negated ray direction)
    const float3 neg_ray_dir = make_float3(-ray_direction.x, -ray_direction.y, -ray_direction.z);
    const float cos_theta_i = dot(neg_ray_dir, outward_normal);
    const bool entering = cos_theta_i > 0.0f;

    // DIAGNOSTIC: Check alternative detection methods
    if (is_center_pixel) {
        // Method 1: Check if ray origin is inside sphere (assuming radius 1.5)
        const float sphere_radius = 1.5f;
        const float3 origin_to_center = make_float3(
            ray_origin.x - sphere_center.x,
            ray_origin.y - sphere_center.y,
            ray_origin.z - sphere_center.z
        );
        const float origin_dist = sqrtf(origin_to_center.x * origin_to_center.x +
                                       origin_to_center.y * origin_to_center.y +
                                       origin_to_center.z * origin_to_center.z);
        const bool origin_inside = (origin_dist < sphere_radius - 0.01f);

        printf("[DETECT] cos_theta_i=%.3f, entering=%d, origin_dist=%.3f, origin_inside=%d\n",
               cos_theta_i, entering ? 1 : 0, origin_dist, origin_inside ? 1 : 0);
    }

    // Surface normal (points toward incoming ray)
    const float3 normal = entering ? outward_normal : make_float3(-outward_normal.x, -outward_normal.y, -outward_normal.z);

    // Compute Fresnel reflectance using Schlick approximation
    const float n1 = entering ? 1.0f : hit_data->ior;  // Air or glass
    const float n2 = entering ? hit_data->ior : 1.0f;  // Glass or air
    const float r0 = (n1 - n2) / (n1 + n2);
    const float R0 = r0 * r0;
    const float cos_theta = fabsf(dot(neg_ray_dir, normal));
    const float one_minus_cos = 1.0f - cos_theta;
    const float fresnel = R0 + (1.0f - R0) * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos * one_minus_cos;

    // Compute reflected ray direction: r = d - 2(d·n)n
    const float dot_dn = dot(ray_direction, normal);
    const float3 reflect_dir = make_float3(
        ray_direction.x - 2.0f * dot_dn * normal.x,
        ray_direction.y - 2.0f * dot_dn * normal.y,
        ray_direction.z - 2.0f * dot_dn * normal.z
    );

    // Cast reflected ray (reflection doesn't enter the volume, so reset absorption tracking)
    unsigned int reflect_r, reflect_g, reflect_b;
    unsigned int reflect_entry_dist = float_as_uint(-1.0f);
    unsigned int reflect_absorption_r = float_as_uint(0.0f);
    unsigned int reflect_absorption_g = float_as_uint(0.0f);
    unsigned int reflect_absorption_b = float_as_uint(0.0f);

    const float3 reflect_origin = hit_point + reflect_dir * Constants::CONTINUATION_RAY_OFFSET;
    optixTrace(
        params.handle,
        reflect_origin,
        reflect_dir,
        Constants::CONTINUATION_RAY_OFFSET,
        Constants::MAX_RAY_DISTANCE,
        0.0f,
        OptixVisibilityMask(255),
        OPTIX_RAY_FLAG_NONE,
        0,  // SBT offset
        1,  // SBT stride
        0,  // missSBTIndex
        reflect_r, reflect_g, reflect_b,
        reflect_entry_dist,
        reflect_absorption_r, reflect_absorption_g, reflect_absorption_b
    );

    // Compute refracted ray using Snell's law
    const float eta = n1 / n2;
    const float k = 1.0f - eta * eta * (1.0f - cos_theta * cos_theta);

    unsigned int refract_r, refract_g, refract_b;
    if (k < 0.0f) {
        // Total internal reflection - use reflected color
        refract_r = reflect_r;
        refract_g = reflect_g;
        refract_b = reflect_b;
    } else {
        // Compute refracted direction: t = η·d + (η·cos(θ) - √k)·n
        const float coeff = eta * cos_theta - sqrtf(k);
        const float3 refract_dir = make_float3(
            eta * ray_direction.x + coeff * normal.x,
            eta * ray_direction.y + coeff * normal.y,
            eta * ray_direction.z + coeff * normal.z
        );

        // Set up payload for refracted ray based on whether we're entering or exiting
        unsigned int refract_entry_dist;
        unsigned int refract_absorption_r, refract_absorption_g, refract_absorption_b;

        // IMPORTANT: Save absorption data BEFORE tracing (it will be overwritten by optixTrace)
        float saved_entry_t = -1.0f;
        float saved_alpha_r = 0.0f;
        float saved_alpha_g = 0.0f;
        float saved_alpha_b = 0.0f;

        if (entering) {
            // Entering the volume: record entry point and absorption coefficients
            refract_entry_dist = float_as_uint(t);  // Store entry t-value

            // Compute absorption coefficients from sphere color: α = -log(color) * (1-alpha)
            // The alpha channel controls absorption intensity
            const float min_color = 0.001f;
            const float color_alpha = hit_data->sphere_color[3];
            const float absorption_factor = 1.0f - color_alpha;  // 0=no absorption, 1=full absorption

            const float alpha_r = -logf(fmaxf(hit_data->sphere_color[0], min_color)) * absorption_factor;
            const float alpha_g = -logf(fmaxf(hit_data->sphere_color[1], min_color)) * absorption_factor;
            const float alpha_b = -logf(fmaxf(hit_data->sphere_color[2], min_color)) * absorption_factor;

            refract_absorption_r = float_as_uint(alpha_r * hit_data->scale);
            refract_absorption_g = float_as_uint(alpha_g * hit_data->scale);
            refract_absorption_b = float_as_uint(alpha_b * hit_data->scale);
        } else {
            // Exiting the volume: save entry data from incoming payload BEFORE tracing
            saved_entry_t = uint_as_float(optixGetPayload_3());
            saved_alpha_r = uint_as_float(optixGetPayload_4());
            saved_alpha_g = uint_as_float(optixGetPayload_5());
            saved_alpha_b = uint_as_float(optixGetPayload_6());

            // Pass zeros forward (no longer in volume)
            refract_entry_dist = float_as_uint(-1.0f);
            refract_absorption_r = float_as_uint(0.0f);
            refract_absorption_g = float_as_uint(0.0f);
            refract_absorption_b = float_as_uint(0.0f);
        }

        // Cast refracted ray
        const float3 refract_origin = hit_point + refract_dir * Constants::CONTINUATION_RAY_OFFSET;

        if (is_center_pixel && entering) {
            printf("[REFRACT] origin=(%.3f,%.3f,%.3f), dir=(%.3f,%.3f,%.3f)\n",
                   refract_origin.x, refract_origin.y, refract_origin.z,
                   refract_dir.x, refract_dir.y, refract_dir.z);

            // Check if refract_origin is inside sphere
            const float3 to_center = make_float3(
                refract_origin.x - sphere_center.x,
                refract_origin.y - sphere_center.y,
                refract_origin.z - sphere_center.z
            );
            const float dist_from_center = sqrtf(to_center.x * to_center.x +
                                                 to_center.y * to_center.y +
                                                 to_center.z * to_center.z);
            printf("[REFRACT] origin distance from center: %.3f (radius=1.5)\n", dist_from_center);
        }

        optixTrace(
            params.handle,
            refract_origin,
            refract_dir,
            Constants::CONTINUATION_RAY_OFFSET,
            Constants::MAX_RAY_DISTANCE,
            0.0f,
            OptixVisibilityMask(255),
            OPTIX_RAY_FLAG_NONE,
            0,  // SBT offset
            1,  // SBT stride
            0,  // missSBTIndex
            refract_r, refract_g, refract_b,
            refract_entry_dist,
            refract_absorption_r, refract_absorption_g, refract_absorption_b
        );

        // Apply absorption if we just exited the volume (using saved values from before the trace)
        if (is_center_pixel) {
            printf("[ABSORPTION CHECK] entering=%d, saved_entry_t=%.3f\n",
                   entering ? 1 : 0, saved_entry_t);
        }

        if (!entering && saved_entry_t >= 0.0f) {
            if (is_center_pixel) {
                printf("[APPLYING ABSORPTION!] travel_distance=%.3f\n", t - saved_entry_t);
            }

            const float travel_distance = t - saved_entry_t;

            const float absorption_r = expf(-saved_alpha_r * travel_distance);
            const float absorption_g = expf(-saved_alpha_g * travel_distance);
            const float absorption_b = expf(-saved_alpha_b * travel_distance);

            refract_r = static_cast<unsigned int>(refract_r * absorption_r);
            refract_g = static_cast<unsigned int>(refract_g * absorption_g);
            refract_b = static_cast<unsigned int>(refract_b * absorption_b);
        }
    }

    // Blend reflected and refracted rays based on Fresnel
    const unsigned int r = static_cast<unsigned int>(fresnel * reflect_r + (1.0f - fresnel) * refract_r);
    const unsigned int g = static_cast<unsigned int>(fresnel * reflect_g + (1.0f - fresnel) * refract_g);
    const unsigned int b = static_cast<unsigned int>(fresnel * reflect_b + (1.0f - fresnel) * refract_b);

    // Set payload (RGB color)
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}
