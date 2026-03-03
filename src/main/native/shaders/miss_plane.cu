//==============================================================================
// Miss Shader Helper Functions
//==============================================================================

/**
 * Extract ray components for the specified plane axis.
 */
__device__ void getRayPlaneComponents(
    const float3& ray_origin,
    const float3& ray_direction,
    int plane_axis,
    float& ray_orig_comp,
    float& ray_dir_comp
) {
    if (plane_axis == 0) {
        ray_orig_comp = ray_origin.x;
        ray_dir_comp = ray_direction.x;
    } else if (plane_axis == 1) {
        ray_orig_comp = ray_origin.y;
        ray_dir_comp = ray_direction.y;
    } else {
        ray_orig_comp = ray_origin.z;
        ray_dir_comp = ray_direction.z;
    }
}

/**
 * Get UV coordinates for checkerboard pattern based on plane axis.
 */
__device__ void getCheckerboardCoordinates(
    const float3& hit_point,
    int plane_axis,
    float& checker_u,
    float& checker_v
) {
    if (plane_axis == 0) {
        checker_u = hit_point.y;
        checker_v = hit_point.z;
    } else if (plane_axis == 1) {
        checker_u = hit_point.x;
        checker_v = hit_point.z;
    } else {
        checker_u = hit_point.x;
        checker_v = hit_point.y;
    }
}

/**
 * Get plane color (solid or checkered pattern) for a specific PlaneParams entry.
 */
__device__ float3 getPlaneColor(const PlaneParams& plane, float checker_u, float checker_v) {
    if (plane.solid_color) {
        return make_float3(plane.color1[0], plane.color1[1], plane.color1[2]);
    }

    const float checker_size = PLANE_CHECKER_SIZE;
    const int check_u = static_cast<int>(floorf(checker_u / checker_size));
    const int check_v = static_cast<int>(floorf(checker_v / checker_size));
    const bool is_light = ((check_u + check_v) & 1) == 0;

    if (is_light) {
        return make_float3(plane.color1[0], plane.color1[1], plane.color1[2]);
    } else {
        return make_float3(plane.color2[0], plane.color2[1], plane.color2[2]);
    }
}

/**
 * Get plane normal based on axis and orientation.
 * Negates the normal when positive=false so lighting is computed
 * from the correct side of the plane.
 */
__device__ float3 getPlaneNormal(const PlaneParams& plane) {
    float3 n;
    if (plane.axis == 0) {
        n = make_float3(1.0f, 0.0f, 0.0f);
    } else if (plane.axis == 1) {
        n = make_float3(0.0f, 1.0f, 0.0f);
    } else {
        n = make_float3(0.0f, 0.0f, 1.0f);
    }
    return plane.positive ? n : make_float3(-n.x, -n.y, -n.z);
}

/**
 * Convert background color to RGB (reads from params, set per-scene).
 */
__device__ void getBackgroundColor(
    unsigned int& r,
    unsigned int& g,
    unsigned int& b
) {
    r = static_cast<unsigned int>(params.bg_r * COLOR_SCALE_FACTOR);
    g = static_cast<unsigned int>(params.bg_g * COLOR_SCALE_FACTOR);
    b = static_cast<unsigned int>(params.bg_b * COLOR_SCALE_FACTOR);
}

//==============================================================================
// Miss shader - returns checkered plane or background color when ray hits nothing
//==============================================================================
extern "C" __global__ void __miss__ms() {
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();

    unsigned int r, g, b;

    // Find the closest plane hit among all active planes
    float best_t = -1.0f;
    int   best_plane = -1;

    for (int i = 0; i < params.num_planes; ++i) {
        const PlaneParams& plane = params.planes[i];
        if (!plane.enabled) continue;

        float ray_orig_comp, ray_dir_comp;
        getRayPlaneComponents(ray_origin, ray_direction, plane.axis, ray_orig_comp, ray_dir_comp);

        if (fabsf(ray_dir_comp) > RAY_PARALLEL_THRESHOLD) {
            const float t = (plane.value - ray_orig_comp) / ray_dir_comp;
            if (t > 0.0f && (best_plane < 0 || t < best_t)) {
                best_t = t;
                best_plane = i;
            }
        }
    }

    if (best_plane >= 0) {
        const PlaneParams& plane = params.planes[best_plane];
        const float3 hit_point = ray_origin + ray_direction * best_t;

        float checker_u, checker_v;
        getCheckerboardCoordinates(hit_point, plane.axis, checker_u, checker_v);

        const float3 plane_rgb = getPlaneColor(plane, checker_u, checker_v);
        r = static_cast<unsigned int>(plane_rgb.x * COLOR_BYTE_MAX);
        g = static_cast<unsigned int>(plane_rgb.y * COLOR_BYTE_MAX);
        b = static_cast<unsigned int>(plane_rgb.z * COLOR_BYTE_MAX);

        const float3 plane_normal = getPlaneNormal(plane);
        const float3 lighting = calculateLighting(hit_point, plane_normal, true);
        r = static_cast<unsigned int>(r * lighting.x);
        g = static_cast<unsigned int>(g * lighting.y);
        b = static_cast<unsigned int>(b * lighting.z);
    } else {
        getBackgroundColor(r, g, b);
    }

    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}
