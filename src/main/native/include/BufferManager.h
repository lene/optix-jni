#ifndef BUFFER_MANAGER_H
#define BUFFER_MANAGER_H

#include "CudaBuffer.h"
#include "OptiXContext.h"
#include "OptiXData.h"

/**
 * Manages all GPU buffers for OptiX rendering.
 * Uses RAII wrappers to ensure automatic cleanup and prevent memory leaks.
 *
 * Addresses Issue 5.2: If allocation fails partway through, all previously
 * allocated buffers are automatically freed via RAII destructors.
 *
 * Responsibilities:
 * - Allocate and manage core rendering buffers (image, params, stats, GAS)
 * - Allocate and manage caustics buffers (hit points, spatial grid)
 * - Resize buffers as needed
 * - Automatic cleanup via RAII
 */
class BufferManager {
public:
    explicit BufferManager(OptiXContext& context);
    ~BufferManager();

    // Core buffer management
    void ensureImageBuffer(int width, int height);
    void ensureParamsBuffer();
    void ensureStatsBuffer();
    void ensureGASBuffer(const OptiXContext::GASBuildResult& gasResult);

    // Caustics buffer management
    void ensureCausticsBuffers();
    void freeCausticsBuffers();

    // Initialize buffers before render
    void zeroStatsBuffer();
    void zeroCausticsBuffers();

    // Access buffers
    CUdeviceptr getImageBuffer() const { return image_buffer.get(); }
    CUdeviceptr getParamsBuffer() const { return params_buffer.get(); }
    CUdeviceptr getStatsBuffer() const { return stats_buffer.get(); }
    CUdeviceptr getGASBuffer() const { return gas_buffer; }

    CUdeviceptr getHitPointsBuffer() const { return hit_points_buffer.get(); }
    CUdeviceptr getNumHitPointsBuffer() const { return num_hit_points_buffer.get(); }
    CUdeviceptr getCausticsGridBuffer() const { return caustics_grid_buffer.get(); }
    CUdeviceptr getCausticsGridCountsBuffer() const { return caustics_grid_counts_buffer.get(); }
    CUdeviceptr getCausticsGridOffsetsBuffer() const { return caustics_grid_offsets_buffer.get(); }
    CUdeviceptr getCausticsStatsBuffer() const { return caustics_stats_buffer.get(); }

    // Download data from GPU
    void downloadImage(unsigned char* output, int width, int height);
    void downloadStats(RayStats* stats);
    void downloadCausticsStats(CausticsStats* stats);

    // Upload data to GPU
    void uploadParams(const Params& params);

private:
    OptiXContext& optix_context;

    // Core buffers
    CudaBuffer<unsigned char> image_buffer;     // RGBA image output
    CudaBuffer<Params> params_buffer;           // Launch parameters
    CudaBuffer<RayStats> stats_buffer;          // Ray statistics
    CUdeviceptr gas_buffer = 0;                 // Geometry Acceleration Structure (managed by OptiXContext)

    // Caustics buffers
    CudaBuffer<HitPoint> hit_points_buffer;              // Array of hit points for PPM
    CudaBuffer<unsigned int> num_hit_points_buffer;      // Counter for atomicAdd
    CudaBuffer<unsigned int> caustics_grid_buffer;       // Spatial hash grid
    CudaBuffer<unsigned int> caustics_grid_counts_buffer;   // Hit points per cell
    CudaBuffer<unsigned int> caustics_grid_offsets_buffer;  // Prefix sum for sorting
    CudaBuffer<CausticsStats> caustics_stats_buffer;     // Caustics statistics

    // Track current sizes to avoid unnecessary reallocations
    size_t current_image_size = 0;
    size_t current_hit_points_size = 0;
};

#endif // BUFFER_MANAGER_H
