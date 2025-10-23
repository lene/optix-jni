#include <optix.h>
#include "../include/OptiXData.h"

// Closest hit shader - computes Lambertian shading for sphere
extern "C" __global__ void __closesthit__ch() {
    // Get hit group data (light parameters)
    const HitGroupData* hit_data = reinterpret_cast<HitGroupData*>(optixGetSbtDataPointer());

    // Get geometric normal (for built-in sphere primitive, it's provided by OptiX)
    const float3 world_normal = optixGetWorldRayDirection();  // Placeholder - will be computed from hit point

    // Get hit point (ray origin + t * ray direction)
    const float t = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();

    const float3 hit_point = make_float3(
        ray_origin.x + t * ray_direction.x,
        ray_origin.y + t * ray_direction.y,
        ray_origin.z + t * ray_direction.z
    );

    // For analytic sphere intersection, we need sphere center from SBT or compute normal
    // For now, assume sphere at origin - normal is just hit_point normalized
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
