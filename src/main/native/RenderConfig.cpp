#include "include/RenderConfig.h"
#include <iostream>

RenderConfig::RenderConfig() {
    // All members initialized via in-class initializers
}

void RenderConfig::setImageDimensions(int width, int height) {
    image_width = width;
    image_height = height;
}

void RenderConfig::setShadows(bool enabled) {
    shadows_enabled = enabled;
}

void RenderConfig::setAntialiasing(bool enabled, int maxDepth, float threshold) {
    aa_enabled = enabled;
    aa_max_depth = maxDepth;
    aa_threshold = threshold;
}

void RenderConfig::setCaustics(bool enabled, int photonsPerIter, int iterations, float initialRadius, float alpha) {
    caustics_enabled = enabled;
    caustics_photons_per_iter = photonsPerIter;
    caustics_iterations = iterations;
    caustics_initial_radius = initialRadius;
    caustics_alpha = alpha;
}

void RenderConfig::clearPlanes() {
    num_planes = 0;
}

// Default material constants for backward-compatible plane methods
static constexpr float PLANE_DEFAULT_ROUGHNESS     = 1.0f;
static constexpr float PLANE_DEFAULT_METALLIC      = 0.0f;
static constexpr float PLANE_DEFAULT_SPECULAR      = 0.5f;
static constexpr float PLANE_DEFAULT_EMISSION      = 0.0f;
static constexpr int   PLANE_DEFAULT_TEXTURE_INDEX = -1;

/** Fill material fields of a PlaneParams with the provided values. */
static void fillPlaneMaterial(
    PlaneParams& p,
    float roughness, float metallic, float specular, float emission, int texture_index
) {
    p.roughness      = roughness;
    p.metallic       = metallic;
    p.specular       = specular;
    p.emission       = emission;
    p.texture_index  = texture_index;
}

void RenderConfig::addPlaneSolidColor(int axis, bool positive, float value, float r, float g, float b) {
    addPlaneSolidColorWithMaterial(axis, positive, value, r, g, b,
        PLANE_DEFAULT_ROUGHNESS, PLANE_DEFAULT_METALLIC,
        PLANE_DEFAULT_SPECULAR, PLANE_DEFAULT_EMISSION,
        PLANE_DEFAULT_TEXTURE_INDEX);
}

void RenderConfig::addPlaneCheckerColors(int axis, bool positive, float value,
                                         float r1, float g1, float b1,
                                         float r2, float g2, float b2) {
    addPlaneCheckerColorsWithMaterial(axis, positive, value,
        r1, g1, b1, r2, g2, b2,
        PLANE_DEFAULT_ROUGHNESS, PLANE_DEFAULT_METALLIC,
        PLANE_DEFAULT_SPECULAR, PLANE_DEFAULT_EMISSION,
        PLANE_DEFAULT_TEXTURE_INDEX);
}

void RenderConfig::addPlaneSolidColorWithMaterial(
    int axis, bool positive, float value,
    float r, float g, float b,
    float roughness, float metallic, float specular, float emission,
    int texture_index
) {
    if (num_planes >= RayTracingConstants::MAX_PLANES) {
        std::cerr << "[RenderConfig] Maximum planes (" << RayTracingConstants::MAX_PLANES << ") reached; plane ignored" << std::endl;
        return;
    }
    PlaneParams& p = planes[num_planes++];
    p.axis = axis;
    p.positive = positive;
    p.value = value;
    p.solid_color = true;
    p.color1[0] = r; p.color1[1] = g; p.color1[2] = b;
    p.color2[0] = 0.0f; p.color2[1] = 0.0f; p.color2[2] = 0.0f;
    p.enabled = true;
    fillPlaneMaterial(p, roughness, metallic, specular, emission, texture_index);
}

void RenderConfig::addPlaneCheckerColorsWithMaterial(
    int axis, bool positive, float value,
    float r1, float g1, float b1,
    float r2, float g2, float b2,
    float roughness, float metallic, float specular, float emission,
    int texture_index
) {
    if (num_planes >= RayTracingConstants::MAX_PLANES) {
        std::cerr << "[RenderConfig] Maximum planes (" << RayTracingConstants::MAX_PLANES << ") reached; plane ignored" << std::endl;
        return;
    }
    PlaneParams& p = planes[num_planes++];
    p.axis = axis;
    p.positive = positive;
    p.value = value;
    p.solid_color = false;
    p.color1[0] = r1; p.color1[1] = g1; p.color1[2] = b1;
    p.color2[0] = r2; p.color2[1] = g2; p.color2[2] = b2;
    p.enabled = true;
    fillPlaneMaterial(p, roughness, metallic, specular, emission, texture_index);
}

void RenderConfig::addPlane(int axis, bool positive, float value) {
    const float light = RayTracingConstants::PLANE_CHECKER_LIGHT_GRAY / 255.0f;
    const float dark  = RayTracingConstants::PLANE_CHECKER_DARK_GRAY  / 255.0f;
    addPlaneCheckerColors(axis, positive, value, light, light, light, dark, dark, dark);
}
