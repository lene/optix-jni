#include <optix.h>
#include "../include/OptiXData.h"

// Miss shader - renders checkered plane at z=-2 when ray hits nothing
extern "C" __global__ void __miss__ms() {
    // Get ray origin and direction
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_dir = optixGetWorldRayDirection();

    // Intersect ray with plane at z = -2
    // Ray equation: P = origin + t * direction
    // Plane equation: z = -2
    // Solve: origin.z + t * direction.z = -2
    const float plane_z = -2.0f;

    unsigned int r, g, b;

    if (ray_dir.z < -0.0001f) {  // Ray pointing towards plane (negative z direction)
        const float t = (plane_z - ray_origin.z) / ray_dir.z;

        if (t > 0.0f) {  // Hit is in front of ray origin
            // Calculate hit point on plane
            const float hit_x = ray_origin.x + t * ray_dir.x;
            const float hit_y = ray_origin.y + t * ray_dir.y;

            // Create checkered pattern with 0.5 unit squares
            const float checker_size = 0.5f;
            const int check_x = static_cast<int>(floorf(hit_x / checker_size));
            const int check_y = static_cast<int>(floorf(hit_y / checker_size));

            // XOR pattern for checkerboard
            const bool is_white = ((check_x + check_y) & 1) == 0;

            if (is_white) {
                r = 240; g = 240; b = 240;  // Light gray
            } else {
                r = 40; g = 40; b = 40;      // Dark gray
            }
        } else {
            // Ray doesn't hit plane (pointing away), use background color
            const MissData* miss_data = reinterpret_cast<MissData*>(optixGetSbtDataPointer());
            r = static_cast<unsigned int>(miss_data->r * 255.99f);
            g = static_cast<unsigned int>(miss_data->g * 255.99f);
            b = static_cast<unsigned int>(miss_data->b * 255.99f);
        }
    } else {
        // Ray parallel or pointing away from plane, use background color
        const MissData* miss_data = reinterpret_cast<MissData*>(optixGetSbtDataPointer());
        r = static_cast<unsigned int>(miss_data->r * 255.99f);
        g = static_cast<unsigned int>(miss_data->g * 255.99f);
        b = static_cast<unsigned int>(miss_data->b * 255.99f);
    }

    // Set payload
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}
