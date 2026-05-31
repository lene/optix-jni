#ifndef CAUSTICS_RENDERER_H
#define CAUSTICS_RENDERER_H

#include <optix.h>
#include "ICausticsRenderer.h"
#include "OptiXContext.h"
#include "PipelineManager.h"
#include "BufferManager.h"
#include "RenderConfig.h"

/**
 * Menger-specific Progressive Photon Mapping (PPM) caustics renderer.
 * Implements ICausticsRenderer; lives in menger-geometry, not optix-jni.
 */
class CausticsRenderer : public ICausticsRenderer {
public:
    CausticsRenderer(OptiXContext& context, PipelineManager& pipeline, BufferManager& buffers);

    void renderWithCaustics(
        int width,
        int height,
        const RenderConfig& config,
        const SceneParameters& scene,
        BaseParams& params
    ) override;

    CausticsStats getLastStats() const override { return last_caustics_stats; }

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

    // Build spatial grid for accelerated photon deposition
    void buildGrid(
        unsigned int num_hit_points,
        const SceneParameters& scene,
        int width,
        int height,
        BaseParams& params
    );
};

#endif // CAUSTICS_RENDERER_H
