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

    // Trace ray
    unsigned int p0, p1, p2;  // Payload for RGB color
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
        p0, p1, p2               // Payload
    );

    // Convert payload to RGBA
    const unsigned int r = p0;
    const unsigned int g = p1;
    const unsigned int b = p2;

    // Write to output buffer
    const unsigned int pixel_index = idx.y * params.image_width + idx.x;
    params.image[pixel_index * 4 + 0] = static_cast<unsigned char>(r);
    params.image[pixel_index * 4 + 1] = static_cast<unsigned char>(g);
    params.image[pixel_index * 4 + 2] = static_cast<unsigned char>(b);
    params.image[pixel_index * 4 + 3] = 255;  // Alpha
}

//==============================================================================
// Miss shader - returns background color when ray hits nothing
//==============================================================================
extern "C" __global__ void __miss__ms() {
    // Get miss data (background color)
    const MissData* miss_data = reinterpret_cast<MissData*>(optixGetSbtDataPointer());

    // Convert float [0,1] to unsigned int [0,255]
    const unsigned int r = static_cast<unsigned int>(miss_data->r * Constants::COLOR_SCALE_FACTOR);
    const unsigned int g = static_cast<unsigned int>(miss_data->g * Constants::COLOR_SCALE_FACTOR);
    const unsigned int b = static_cast<unsigned int>(miss_data->b * Constants::COLOR_SCALE_FACTOR);

    // Set payload
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}

//==============================================================================
// Closest hit shader - computes Lambertian shading for sphere
//==============================================================================
extern "C" __global__ void __closesthit__ch() {
    // Get hit group data (light parameters)
    const HitGroupData* hit_data = reinterpret_cast<HitGroupData*>(optixGetSbtDataPointer());

    // Get hit point (ray origin + t * ray direction)
    const float t = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();

    const float3 hit_point = ray_origin + ray_direction * t;

    // Compute surface normal: normal = normalize(hit_point - sphere_center)
    // The sphere center is passed through the HitGroupData SBT record
    const float3 sphere_center = make_float3(
        hit_data->sphere_center[0],
        hit_data->sphere_center[1],
        hit_data->sphere_center[2]
    );

    const float3 normal = normalize(hit_point + sphere_center * -1.0f);

    // Light direction (negated for dot product)
    const float3 light_dir = make_float3(
        -hit_data->light_dir[0],
        -hit_data->light_dir[1],
        -hit_data->light_dir[2]
    );

    // Lambertian shading: max(0, N · L)
    const float ndotl = fmaxf(0.0f, dot(normal, light_dir));

    // Apply light intensity
    const float intensity = ndotl * hit_data->light_intensity;

    // Load sphere_color from SBT into separate variables
    // (workaround for CUDA optimizer bug that eliminates loads when all channels use identical operations)
    const float color_r = hit_data->sphere_color[0];
    const float color_g = hit_data->sphere_color[1];
    const float color_b = hit_data->sphere_color[2];
    const float alpha = hit_data->sphere_color[3];

    // Apply material color and convert to RGB [0, 255]
    unsigned int r = static_cast<unsigned int>(color_r * intensity * Constants::COLOR_SCALE_FACTOR);
    unsigned int g = static_cast<unsigned int>(color_g * intensity * Constants::COLOR_SCALE_FACTOR);
    unsigned int b = static_cast<unsigned int>(color_b * intensity * Constants::COLOR_SCALE_FACTOR);

    // Handle transparency: if alpha < 1.0, cast continuation ray and blend
    if (alpha < 1.0f) {
        // Cast continuation ray from slightly beyond the hit point
        const float3 continuation_origin = hit_point + ray_direction * Constants::CONTINUATION_RAY_OFFSET;

        unsigned int bg_r, bg_g, bg_b;
        optixTrace(
            params.handle,
            continuation_origin,
            ray_direction,
            Constants::CONTINUATION_RAY_OFFSET,
            Constants::MAX_RAY_DISTANCE,
            0.0f,
            OptixVisibilityMask(255),
            OPTIX_RAY_FLAG_NONE,
            0,  // SBT offset
            1,  // SBT stride
            0,  // missSBTIndex
            bg_r, bg_g, bg_b  // Background color payload
        );

        // Alpha blend: result = alpha * foreground + (1-alpha) * background
        r = static_cast<unsigned int>(alpha * r + (1.0f - alpha) * bg_r);
        g = static_cast<unsigned int>(alpha * g + (1.0f - alpha) * bg_g);
        b = static_cast<unsigned int>(alpha * b + (1.0f - alpha) * bg_b);
    }

    // Set payload (RGB color)
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}
