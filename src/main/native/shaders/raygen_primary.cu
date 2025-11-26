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
