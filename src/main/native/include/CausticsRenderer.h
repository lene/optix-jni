#ifndef CAUSTICS_RENDERER_H
#define CAUSTICS_RENDERER_H

#include <optix.h>
#include "OptiXContext.h"
#include "PipelineManager.h"
#include "BufferManager.h"
#include "RenderConfig.h"

// Forward declaration
struct Params;

/**
 * Handles Progressive Photon Mapping (PPM) for caustics rendering.
 * Implements multi-pass rendering: hit point generation, photon tracing, and radiance estimation.
 *
 * Responsibilities:
 * - Orchestrate PPM multi-pass rendering
 * - Launch hit point generation pass
 * - Launch iterative photon tracing passes
 * - Launch radiance estimation pass
 * - Track caustics statistics
 */
class CausticsRenderer {
public:
    CausticsRenderer(OptiXContext& context, PipelineManager& pipeline, BufferManager& buffers);

    // Main caustics rendering method
    void renderWithCaustics(
        int width,
        int height,
        const RenderConfig& config,
        const SceneParameters& scene,
        Params& params
    );

    // Get statistics from last render
    CausticsStats getLastStats() const { return last_caustics_stats; }

private:
    OptiXContext& optix_context;
    PipelineManager& pipeline_manager;
    BufferManager& buffer_manager;

    CausticsStats last_caustics_stats;

    // Launch a caustics pass with specified raygen program
    void launchCausticsPass(
        const SceneParameters& scene,
        int width,
        int height,
        OptixProgramGroup raygen_group,
        int launch_width,
        int launch_height
    );
};

#endif // CAUSTICS_RENDERER_H
