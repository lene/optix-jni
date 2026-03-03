#ifndef RENDER_CONFIG_H
#define RENDER_CONFIG_H

// This header is only included by .cpp files, so it's safe to include OptiXData.h
// (OptiXData.h has CUDA-specific syntax that won't work if transitively included in other headers)
#if !defined(__CUDACC__) && !defined(__align__)
#define __align__(n)  alignas(n)
#endif

#include "OptiXData.h"
#include "OptiXConstants.h"

/**
 * Encapsulates all rendering configuration options.
 * Manages shadows, antialiasing, caustics, plane appearance, and image dimensions.
 *
 * Responsibilities:
 * - Store rendering configuration values
 * - Provide access to rendering settings
 * - Manage plane color modes (solid vs checkerboard)
 */
class RenderConfig {
public:
    RenderConfig();

    // Image dimensions
    void setImageDimensions(int width, int height);
    int getImageWidth() const { return image_width; }
    int getImageHeight() const { return image_height; }

    // Shadow configuration
    void setShadows(bool enabled);
    bool getShadowsEnabled() const { return shadows_enabled; }

    // Antialiasing configuration
    void setAntialiasing(bool enabled, int maxDepth, float threshold);
    bool getAAEnabled() const { return aa_enabled; }
    int getAAMaxDepth() const { return aa_max_depth; }
    float getAAThreshold() const { return aa_threshold; }

    // Caustics (Progressive Photon Mapping) configuration
    void setCaustics(bool enabled, int photonsPerIter, int iterations, float initialRadius, float alpha);
    bool getCausticsEnabled() const { return caustics_enabled; }
    int getCausticsPhotonsPerIter() const { return caustics_photons_per_iter; }
    int getCausticsIterations() const { return caustics_iterations; }
    float getCausticsInitialRadius() const { return caustics_initial_radius; }
    float getCausticsAlpha() const { return caustics_alpha; }

    // Background color configuration
    void setBackgroundColor(float r, float g, float b) { bg_r = r; bg_g = g; bg_b = b; }
    float getBackgroundR() const { return bg_r; }
    float getBackgroundG() const { return bg_g; }
    float getBackgroundB() const { return bg_b; }

    // Plane appearance configuration
    void clearPlanes();
    void addPlaneSolidColor(int axis, bool positive, float value, float r, float g, float b);
    void addPlaneCheckerColors(int axis, bool positive, float value,
                               float r1, float g1, float b1,
                               float r2, float g2, float b2);
    void addPlane(int axis, bool positive, float value);  // default gray checker
    const PlaneParams* getPlanes() const { return planes; }
    int getNumPlanes() const { return num_planes; }

private:
    // Image dimensions
    int image_width = -1;
    int image_height = -1;

    // Shadow configuration
    bool shadows_enabled = false;

    // Antialiasing configuration
    bool aa_enabled = false;
    int aa_max_depth = 2;
    float aa_threshold = RayTracingConstants::AA_DEFAULT_THRESHOLD;

    // Caustics configuration
    bool caustics_enabled = false;
    int caustics_photons_per_iter = RayTracingConstants::DEFAULT_PHOTONS_PER_ITER;
    int caustics_iterations = RayTracingConstants::DEFAULT_CAUSTICS_ITERATIONS;
    float caustics_initial_radius = RayTracingConstants::DEFAULT_INITIAL_RADIUS;
    float caustics_alpha = RayTracingConstants::DEFAULT_PPM_ALPHA;

    // Background color
    float bg_r = OptiXConstants::DEFAULT_BG_R;
    float bg_g = OptiXConstants::DEFAULT_BG_G;
    float bg_b = OptiXConstants::DEFAULT_BG_B;

    // Plane appearance
    PlaneParams planes[RayTracingConstants::MAX_PLANES] = {};
    int num_planes = 0;
};

#endif // RENDER_CONFIG_H
