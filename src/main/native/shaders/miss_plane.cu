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
 * Get plane color (solid or checkered pattern).
 */
__device__ float3 getPlaneColor(float checker_u, float checker_v) {
    if (params.plane_solid_color) {
        return make_float3(
            params.plane_color1[0],
            params.plane_color1[1],
            params.plane_color1[2]
        );
    }

    const float checker_size = PLANE_CHECKER_SIZE;
    const int check_u = static_cast<int>(floorf(checker_u / checker_size));
    const int check_v = static_cast<int>(floorf(checker_v / checker_size));
    const bool is_light = ((check_u + check_v) & 1) == 0;

    if (is_light) {
        return make_float3(
            params.plane_color1[0],
            params.plane_color1[1],
            params.plane_color1[2]
        );
    } else {
        return make_float3(
            params.plane_color2[0],
            params.plane_color2[1],
            params.plane_color2[2]
        );
    }
}

/**
 * Get plane normal based on axis.
 */
__device__ float3 getPlaneNormal(int plane_axis) {
    if (plane_axis == 0) {
        return make_float3(1.0f, 0.0f, 0.0f);
    } else if (plane_axis == 1) {
        return make_float3(0.0f, 1.0f, 0.0f);
    } else {
        return make_float3(0.0f, 0.0f, 1.0f);
    }
}

/**
 * Convert miss data background color to RGB.
 */
__device__ void getBackgroundColor(
    const MissData* miss_data,
    unsigned int& r,
    unsigned int& g,
    unsigned int& b
) {
    r = static_cast<unsigned int>(miss_data->r * COLOR_SCALE_FACTOR);
    g = static_cast<unsigned int>(miss_data->g * COLOR_SCALE_FACTOR);
    b = static_cast<unsigned int>(miss_data->b * COLOR_SCALE_FACTOR);
}

//==============================================================================
// Miss shader - returns checkered plane or background color when ray hits nothing
//==============================================================================
extern "C" __global__ void __miss__ms() {
    const MissData* miss_data = reinterpret_cast<MissData*>(optixGetSbtDataPointer());
    const float3 ray_origin = optixGetWorldRayOrigin();
    const float3 ray_direction = optixGetWorldRayDirection();

    const int plane_axis = params.plane_axis;
    const float plane_value = params.plane_value;

    float ray_orig_comp, ray_dir_comp;
    getRayPlaneComponents(ray_origin, ray_direction, plane_axis, ray_orig_comp, ray_dir_comp);

    unsigned int r, g, b;

    if (fabsf(ray_dir_comp) > RAY_PARALLEL_THRESHOLD) {
        const float t = (plane_value - ray_orig_comp) / ray_dir_comp;

        if (t > 0.0f) {
            const float3 hit_point = ray_origin + ray_direction * t;

            float checker_u, checker_v;
            getCheckerboardCoordinates(hit_point, plane_axis, checker_u, checker_v);

            const float3 plane_rgb = getPlaneColor(checker_u, checker_v);
            r = static_cast<unsigned int>(plane_rgb.x * COLOR_BYTE_MAX);
            g = static_cast<unsigned int>(plane_rgb.y * COLOR_BYTE_MAX);
            b = static_cast<unsigned int>(plane_rgb.z * COLOR_BYTE_MAX);

            const float3 plane_normal = getPlaneNormal(plane_axis);
            const float3 lighting = calculateLighting(hit_point, plane_normal, true);
            r = static_cast<unsigned int>(r * lighting.x);
            g = static_cast<unsigned int>(g * lighting.y);
            b = static_cast<unsigned int>(b * lighting.z);
        } else {
            getBackgroundColor(miss_data, r, g, b);
        }
    } else {
        getBackgroundColor(miss_data, r, g, b);
    }

    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}
