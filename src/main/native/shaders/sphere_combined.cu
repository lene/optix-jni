#include <optix.h>
#include "../include/OptiXData.h"

extern "C" {
    __constant__ Params params;
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
    float3 ray_origin = make_float3(
        raygen_data->cam_eye[0],
        raygen_data->cam_eye[1],
        raygen_data->cam_eye[2]
    );

    float3 ray_direction = make_float3(
        u * raygen_data->camera_u[0] + v * raygen_data->camera_v[0] + raygen_data->camera_w[0],
        u * raygen_data->camera_u[1] + v * raygen_data->camera_v[1] + raygen_data->camera_w[1],
        u * raygen_data->camera_u[2] + v * raygen_data->camera_v[2] + raygen_data->camera_w[2]
    );

    // Normalize ray direction
    const float len = sqrtf(ray_direction.x * ray_direction.x +
                            ray_direction.y * ray_direction.y +
                            ray_direction.z * ray_direction.z);
    ray_direction.x /= len;
    ray_direction.y /= len;
    ray_direction.z /= len;

    // Trace ray
    unsigned int p0, p1, p2;  // Payload for RGB color
    optixTrace(
        params.handle,           // Acceleration structure
        ray_origin,              // Ray origin
        ray_direction,           // Ray direction
        0.0f,                    // tmin
        1e16f,                   // tmax
        0.0f,                    // rayTime
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
    const unsigned int r = static_cast<unsigned int>(miss_data->r * 255.99f);
    const unsigned int g = static_cast<unsigned int>(miss_data->g * 255.99f);
    const unsigned int b = static_cast<unsigned int>(miss_data->b * 255.99f);

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

    const float3 hit_point = make_float3(
        ray_origin.x + t * ray_direction.x,
        ray_origin.y + t * ray_direction.y,
        ray_origin.z + t * ray_direction.z
    );

    // For sphere at origin - normal is just hit_point normalized
    // TODO: Handle arbitrary sphere centers when needed
    float3 normal = hit_point;
    const float len = sqrtf(normal.x * normal.x + normal.y * normal.y + normal.z * normal.z);
    normal.x /= len;
    normal.y /= len;
    normal.z /= len;

    // Light direction (negated for dot product)
    const float3 light_dir = make_float3(
        -hit_data->light_dir[0],
        -hit_data->light_dir[1],
        -hit_data->light_dir[2]
    );

    // Lambertian shading: max(0, N · L)
    float ndotl = normal.x * light_dir.x + normal.y * light_dir.y + normal.z * light_dir.z;
    ndotl = fmaxf(0.0f, ndotl);

    // Apply light intensity and material color (white)
    const float intensity = ndotl * hit_data->light_intensity;

    // Convert to RGB [0, 255]
    const unsigned int color = static_cast<unsigned int>(intensity * 255.99f);

    // Set payload (grayscale for now)
    optixSetPayload_0(color);
    optixSetPayload_1(color);
    optixSetPayload_2(color);
}
