#include "include/CausticsRenderer.h"
#include "include/SceneParameters.h"
#include "include/OptiXErrorChecking.h"
#include <iostream>
#include <algorithm>

CausticsRenderer::CausticsRenderer(
    OptiXContext& context,
    PipelineManager& pipeline,
    BufferManager& buffers
)
    : optix_context(context)
    , pipeline_manager(pipeline)
    , buffer_manager(buffers)
    , last_caustics_stats{}
{
}

void CausticsRenderer::launchCausticsPass(
    const SceneParameters& scene,
    int width,
    int height,
    OptixProgramGroup raygen_group,
    int launch_width,
    int launch_height
) {
    // Create a temporary SBT with the specified raygen program
    // This is more efficient than rebuilding the entire SBT for each pass
    OptixShaderBindingTable temp_sbt = pipeline_manager.getSBT();

    // Create new raygen record for this program group with actual camera data
    CUdeviceptr temp_raygen_record = pipeline_manager.createTempRaygenSBTRecord(
        raygen_group,
        scene
    );

    temp_sbt.raygenRecord = temp_raygen_record;

    // Launch with custom dimensions
    optix_context.launch(
        pipeline_manager.getPipeline(),
        temp_sbt,
        buffer_manager.getParamsBuffer(),
        launch_width,
        launch_height
    );

    // Clean up temporary SBT record
    pipeline_manager.freeTempRaygenSBTRecord(temp_raygen_record);
}

void CausticsRenderer::renderWithCaustics(
    int width,
    int height,
    const RenderConfig& config,
    const SceneParameters& scene,
    Params& params
) {
    // Progressive Photon Mapping multi-pass rendering:
    // 1. Hit Point Generation: Trace camera rays, store hit points on diffuse surfaces
    // 2. Photon Tracing Iterations: Emit photons from lights, deposit at hit points
    // 3. Final Render: Standard ray trace with caustics contribution

    // =====================================
    // Pass 1: Hit Point Generation
    // =====================================
    buffer_manager.zeroCausticsBuffers();

    // Launch hit point generation (one thread per pixel)
    launchCausticsPass(
        scene,
        width,
        height,
        pipeline_manager.getCausticsHitpointsRaygen(),
        width,
        height
    );
    CUDA_CHECK(cudaDeviceSynchronize());

    // Read back number of hit points
    unsigned int num_hit_points = 0;
    CUDA_CHECK(cudaMemcpy(
        &num_hit_points,
        reinterpret_cast<void*>(buffer_manager.getNumHitPointsBuffer()),
        sizeof(unsigned int),
        cudaMemcpyDeviceToHost
    ));
    std::cout << "[Caustics] Phase 1: Collected " << num_hit_points << " hit points" << std::endl;

    // =====================================
    // Pass 2-N: Photon Tracing Iterations
    // =====================================
    const int photons_per_iter = config.getCausticsPhotonsPerIter();
    const int iterations = config.getCausticsIterations();

    std::cout << "[Caustics] Phase 2: Tracing " << photons_per_iter
              << " photons x " << iterations << " iterations" << std::endl;

    for (int iter = 0; iter < iterations; ++iter) {
        // Update iteration counter in params
        params.caustics.current_iteration = iter;
        buffer_manager.uploadParams(params);

        // Calculate launch dimensions for photon tracing
        // Use a 2D grid that covers photons_per_iteration threads
        int photon_grid_width = std::min(photons_per_iter, 1024);  // Max 1024 threads per row
        int photon_grid_height = (photons_per_iter + photon_grid_width - 1) / photon_grid_width;

        // Launch photon tracing
        launchCausticsPass(
            scene,
            width,
            height,
            pipeline_manager.getCausticsPhotonsRaygen(),
            photon_grid_width,
            photon_grid_height
        );
        CUDA_CHECK(cudaDeviceSynchronize());

        std::cout << "[Caustics]   Iteration " << (iter + 1) << "/" << iterations
                  << " complete" << std::endl;
    }

    // =====================================
    // Final Pass: Standard Render with Caustics
    // =====================================
    // Update total photons traced
    params.caustics.total_photons_traced =
        static_cast<unsigned long long>(photons_per_iter) *
        static_cast<unsigned long long>(iterations);
    buffer_manager.uploadParams(params);

    std::cout << "[Caustics] Phase 3: Rendering scene" << std::endl;

    // Launch standard render (uses accumulated caustics data)
    optix_context.launch(
        pipeline_manager.getPipeline(),
        pipeline_manager.getSBT(),
        buffer_manager.getParamsBuffer(),
        width,
        height
    );
    CUDA_CHECK(cudaDeviceSynchronize());

    // =====================================
    // Post-Processing: Apply Caustics Radiance to Image
    // =====================================
    if (num_hit_points > 0) {
        std::cout << "[Caustics] Phase 4: Computing radiance for " << num_hit_points << " hit points" << std::endl;

        // Launch caustics radiance computation (one thread per hit point)
        launchCausticsPass(
            scene,
            width,
            height,
            pipeline_manager.getCausticsRadianceRaygen(),
            num_hit_points,
            1
        );
        CUDA_CHECK(cudaDeviceSynchronize());
    }

    std::cout << "[Caustics] Complete: " << params.caustics.total_photons_traced << " total photons traced" << std::endl;

    // Download caustics stats for validation
    buffer_manager.downloadCausticsStats(&last_caustics_stats);
}
