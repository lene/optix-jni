// Debug version of sphere_combined.cu to test entering/exiting detection
// This file will help us understand the ray flow through the sphere

#include <optix.h>
#include "../include/OptiXData.h"

extern "C" {
    __constant__ Params params;
}

// Test configuration:
// 1. Color sphere RED when entering (should see red sphere)
// 2. Color sphere GREEN when exiting (should never happen if hypothesis is correct)
// 3. Add visual indicators for entry/exit detection

extern "C" __global__ void __closesthit__ch() {
    // Get hit group data
    const HitGroupData* hit_data = reinterpret_cast<HitGroupData*>(optixGetSbtDataPointer());

    // Get hit point
    const float t = optixGetRayTmax();
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();
    const float3 hit_point = ray_origin + ray_direction * t;

    // Compute surface normal
    const float3 sphere_center = make_float3(
        hit_data->sphere_center[0],
        hit_data->sphere_center[1],
        hit_data->sphere_center[2]
    );

    const float3 to_hit = make_float3(
        hit_point.x - sphere_center.x,
        hit_point.y - sphere_center.y,
        hit_point.z - sphere_center.z
    );
    const float3 outward_normal = normalize(to_hit);

    // Determine if ray is entering or exiting sphere
    const float3 neg_ray_dir = make_float3(-ray_direction.x, -ray_direction.y, -ray_direction.z);
    const float cos_theta_i = dot(neg_ray_dir, outward_normal);
    const bool entering = cos_theta_i > 0.0f;

    // DEBUG: Set color based on entry/exit
    unsigned int r, g, b;
    if (entering) {
        // RED for entering
        r = 255;
        g = 0;
        b = 0;
    } else {
        // GREEN for exiting (should never happen if hypothesis is correct)
        r = 0;
        g = 255;
        b = 0;
    }

    // Set payload
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}