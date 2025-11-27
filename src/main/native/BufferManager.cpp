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

void BufferManager::ensureImageBuffer(int width, int height) {
    const size_t required_size = width * height * 4; // RGBA
    if (current_image_size != required_size) {
        image_buffer.allocate(required_size);
        current_image_size = required_size;
    }
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
    const size_t hit_points_size = RayTracingConstants::MAX_HIT_POINTS;
    const size_t grid_size = RayTracingConstants::CAUSTICS_GRID_RESOLUTION *
                            RayTracingConstants::CAUSTICS_GRID_RESOLUTION *
                            RayTracingConstants::CAUSTICS_GRID_RESOLUTION;

    if (current_hit_points_size != hit_points_size) {
        // Allocate all buffers (RAII ensures cleanup on exception)
        hit_points_buffer.allocate(hit_points_size);
        num_hit_points_buffer.allocate(1);
        caustics_grid_buffer.allocate(grid_size);
        caustics_grid_counts_buffer.allocate(grid_size);
        caustics_grid_offsets_buffer.allocate(grid_size);
        caustics_stats_buffer.allocate(1);

        current_hit_points_size = hit_points_size;
    }
}

void BufferManager::freeCausticsBuffers() {
    hit_points_buffer.free();
    num_hit_points_buffer.free();
    caustics_grid_buffer.free();
    caustics_grid_counts_buffer.free();
    caustics_grid_offsets_buffer.free();
    caustics_stats_buffer.free();
    current_hit_points_size = 0;
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
}

void BufferManager::downloadImage(unsigned char* output, int width, int height) {
    const size_t pixel_count = width * height * 4; // RGBA
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

void BufferManager::uploadParams(const Params& params) {
    if (params_buffer.isAllocated()) {
        params_buffer.uploadFrom(&params, 1);
    }
}
