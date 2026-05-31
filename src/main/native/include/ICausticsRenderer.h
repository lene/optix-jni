#ifndef ICAUSTICS_RENDERER_H
#define ICAUSTICS_RENDERER_H

#include "OptiXData.h"
#include "RenderConfig.h"

// Forward declarations
struct SceneParameters;
struct BaseParams;

class ICausticsRenderer {
public:
    virtual void renderWithCaustics(
        int width,
        int height,
        const RenderConfig& config,
        const SceneParameters& scene,
        BaseParams& params
    ) = 0;

    virtual CausticsStats getLastStats() const = 0;

    virtual ~ICausticsRenderer() = default;
};

#endif // ICAUSTICS_RENDERER_H
