#include "include/BufferManager.h"
#include "include/OptiXErrorChecking.h"

BufferManager::BufferManager(OptiXContext& context)
    : optix_context(context) {
}

BufferManager::~BufferManager() {
    // RAII buffers automatically clean up
    // Only need to clean up GAS buffer which is managed by OptiXContext
    if (gas_buffer) {
        optix_context.destroyGAS(gas_buffer);
        gas_buffer = 0;
    }
}

// The buffers below are scratch: their old content does not matter, so a changed size frees first
// and then allocates (least peak memory). No size is cached apart from the buffer: CudaBuffer
// knows its own size, and after a failed allocation it is empty, so the next call allocates again
// (optix-jni#96: a cached size claimed "allocated" and the next launch got a null image pointer).
void BufferManager::ensureImageBuffer(int width, int height) {
    // size_t before multiplying (optix-jni#68); width/height are bounded at the JNI entry.
    image_buffer.allocate(static_cast<size_t>(width) * static_cast<size_t>(height) * 4); // RGBA
}

void BufferManager::ensureDenoiseBuffers(int width, int height) {
    const size_t pixel_count = static_cast<size_t>(width) * static_cast<size_t>(height);
    linear_color_buffer.allocate(pixel_count);
    accumulated_linear_color_buffer.allocate(pixel_count);
    denoised_color_buffer.allocate(pixel_count);
    denoise_albedo_buffer.allocate(pixel_count);
    denoise_normal_buffer.allocate(pixel_count);
}

void BufferManager::ensureParamsBuffer() {
    if (!params_buffer.isAllocated()) {
        params_buffer.allocate(1);
    }
}

void BufferManager::ensureStatsBuffer() {
    if (!stats_buffer.isAllocated()) {
        stats_buffer.allocate(1);
    }
}

void BufferManager::ensureGASBuffer(const OptiXContext::GASBuildResult& gasResult) {
    // Free old GAS if it exists
    if (gas_buffer) {
        optix_context.destroyGAS(gas_buffer);
    }
    gas_buffer = gasResult.gas_buffer;
}

void BufferManager::ensureCausticsBuffers() {
    const size_t grid_size = RayTracingConstants::CAUSTICS_GRID_RESOLUTION *
                            RayTracingConstants::CAUSTICS_GRID_RESOLUTION *
                            RayTracingConstants::CAUSTICS_GRID_RESOLUTION;

    // RAII ensures cleanup on exception; each buffer is checked against its own size
    hit_points_buffer.allocate(RayTracingConstants::MAX_HIT_POINTS);
    num_hit_points_buffer.allocate(1);
    caustics_grid_buffer.allocate(grid_size);
    caustics_grid_counts_buffer.allocate(grid_size);
    caustics_grid_offsets_buffer.allocate(grid_size);
    caustics_stats_buffer.allocate(1);
}

void BufferManager::freeCausticsBuffers() {
    hit_points_buffer.free();
    num_hit_points_buffer.free();
    caustics_grid_buffer.free();
    caustics_grid_counts_buffer.free();
    caustics_grid_offsets_buffer.free();
    caustics_stats_buffer.free();
}

void BufferManager::zeroStatsBuffer() {
    if (stats_buffer.isAllocated()) {
        RayStats zero_stats = {};
        zero_stats.min_depth_reached = UINT_MAX;  // Initialize to max value for atomicMin
        stats_buffer.uploadFrom(&zero_stats, 1);
    }
}

void BufferManager::zeroCausticsBuffers() {
    if (num_hit_points_buffer.isAllocated()) {
        num_hit_points_buffer.zero(1);
    }
    if (caustics_stats_buffer.isAllocated()) {
        caustics_stats_buffer.zero(1);
    }
    zeroCausticsGridCounts();
}

void BufferManager::zeroCausticsGridCounts() {
    const size_t grid_size = RayTracingConstants::CAUSTICS_GRID_RESOLUTION *
                            RayTracingConstants::CAUSTICS_GRID_RESOLUTION *
                            RayTracingConstants::CAUSTICS_GRID_RESOLUTION;
    if (caustics_grid_counts_buffer.isAllocated()) {
        caustics_grid_counts_buffer.zero(grid_size);
    }
    if (caustics_grid_offsets_buffer.isAllocated()) {
        caustics_grid_offsets_buffer.zero(grid_size);
    }
}

void BufferManager::downloadImage(unsigned char* output, int width, int height) {
    const size_t pixel_count = static_cast<size_t>(width) * static_cast<size_t>(height) * 4; // RGBA
    image_buffer.downloadTo(output, pixel_count);
}

void BufferManager::downloadStats(RayStats* stats) {
    if (stats && stats_buffer.isAllocated()) {
        stats_buffer.downloadTo(stats, 1);
    }
}

void BufferManager::downloadCausticsStats(CausticsStats* stats) {
    if (stats && caustics_stats_buffer.isAllocated()) {
        caustics_stats_buffer.downloadTo(stats, 1);
    }
}

void BufferManager::uploadParams(const BaseParams& params) {
    if (params_buffer.isAllocated()) {
        params_buffer.uploadFrom(&params, 1);
    }
}
