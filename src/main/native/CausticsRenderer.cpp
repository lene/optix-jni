#include "include/CausticsRenderer.h"
#include "include/SceneParameters.h"
#include "include/OptiXErrorChecking.h"
#include "include/OptiXData.h"
#include <iostream>
#include <algorithm>
#include <vector>

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
    BaseParams& params
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
    // Grid Building: Spatial acceleration for photon deposition
    // =====================================
    if (num_hit_points > 0) {
        buildGrid(num_hit_points, scene, width, height, params);
    }

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
        constexpr int MAX_PHOTON_THREADS_PER_ROW = 1024;
        int photon_grid_width = std::min(photons_per_iter, MAX_PHOTON_THREADS_PER_ROW);
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

        // PPM progressive radius reduction
        if (num_hit_points > 0) {
            launchCausticsPass(
                scene, width, height,
                pipeline_manager.getCausticsUpdateRadiiRaygen(),
                num_hit_points, 1
            );
            CUDA_CHECK(cudaDeviceSynchronize());
        }

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

void CausticsRenderer::buildGrid(
    unsigned int num_hit_points,
    const SceneParameters& scene,
    int width,
    int height,
    const BaseParams& params
) {
    // Step 1: Zero grid counts
    buffer_manager.zeroCausticsGridCounts();
    buffer_manager.uploadParams(params);

    // Step 2: Count hit points per cell
    launchCausticsPass(
        scene, width, height,
        pipeline_manager.getCausticsGridCountRaygen(),
        num_hit_points, 1
    );
    CUDA_CHECK(cudaDeviceSynchronize());

    // Step 3: CPU-side exclusive prefix sum → grid_offsets
    const unsigned int grid_res = params.caustics.grid_resolution;
    const size_t grid_cells = static_cast<size_t>(grid_res) * grid_res * grid_res;

    std::vector<unsigned int> counts(grid_cells);
    CUDA_CHECK(cudaMemcpy(
        counts.data(),
        reinterpret_cast<void*>(buffer_manager.getCausticsGridCountsBuffer()),
        grid_cells * sizeof(unsigned int),
        cudaMemcpyDeviceToHost
    ));

    std::vector<unsigned int> offsets(grid_cells);
    unsigned int running_sum = 0;
    for (size_t i = 0; i < grid_cells; ++i) {
        offsets[i] = running_sum;
        running_sum += counts[i];
    }

    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(buffer_manager.getCausticsGridOffsetsBuffer()),
        offsets.data(),
        grid_cells * sizeof(unsigned int),
        cudaMemcpyHostToDevice
    ));

    // Step 4: Zero only grid_counts (reused as insertion counters during scatter)
    // Don't call zeroCausticsGridCounts() since that also zeros grid_offsets
    CUDA_CHECK(cudaMemset(
        reinterpret_cast<void*>(buffer_manager.getCausticsGridCountsBuffer()),
        0,
        grid_cells * sizeof(unsigned int)
    ));

    // Step 5: Scatter hit point indices into sorted grid array
    launchCausticsPass(
        scene, width, height,
        pipeline_manager.getCausticsGridScatterRaygen(),
        num_hit_points, 1
    );
    CUDA_CHECK(cudaDeviceSynchronize());

    // After scatter, grid_counts is restored to original values (each hit point
    // does exactly one atomicAdd on its cell). grid_offsets + grid_counts define
    // the range of hit point indices per cell in the grid[] array.

    std::cout << "[Caustics] Grid built: " << grid_res << "^3 cells, "
              << num_hit_points << " entries" << std::endl;
}
