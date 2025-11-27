#ifndef RENDER_CONFIG_H
#define RENDER_CONFIG_H

// This header is only included by .cpp files, so it's safe to include OptiXData.h
// (OptiXData.h has CUDA-specific syntax that won't work if transitively included in other headers)
#ifndef __CUDACC__
#define __align__(n)  alignas(n)
#endif

#include "OptiXData.h"

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

    // Plane appearance configuration
    void setPlaneSolidColor(float r, float g, float b);
    void setPlaneCheckerColors(float r1, float g1, float b1, float r2, float g2, float b2);
    bool isPlaneSolidColor() const { return plane_solid_color; }
    const float* getPlaneColor1() const { return plane_color1; }
    const float* getPlaneColor2() const { return plane_color2; }

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

    // Plane appearance
    bool plane_solid_color = false;
    float plane_color1[3] = {RayTracingConstants::PLANE_CHECKER_LIGHT_GRAY / 255.0f,
                             RayTracingConstants::PLANE_CHECKER_LIGHT_GRAY / 255.0f,
                             RayTracingConstants::PLANE_CHECKER_LIGHT_GRAY / 255.0f};
    float plane_color2[3] = {RayTracingConstants::PLANE_CHECKER_DARK_GRAY / 255.0f,
                             RayTracingConstants::PLANE_CHECKER_DARK_GRAY / 255.0f,
                             RayTracingConstants::PLANE_CHECKER_DARK_GRAY / 255.0f};
};

#endif // RENDER_CONFIG_H
