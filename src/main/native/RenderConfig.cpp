#include "include/RenderConfig.h"

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

void RenderConfig::addPlaneSolidColor(int axis, bool positive, float value, float r, float g, float b) {
    if (num_planes >= 4) return;
    PlaneParams& p = planes[num_planes++];
    p.axis = axis;
    p.positive = positive;
    p.value = value;
    p.solid_color = true;
    p.color1[0] = r; p.color1[1] = g; p.color1[2] = b;
    p.color2[0] = 0.0f; p.color2[1] = 0.0f; p.color2[2] = 0.0f;
    p.enabled = true;
}

void RenderConfig::addPlaneCheckerColors(int axis, bool positive, float value,
                                         float r1, float g1, float b1,
                                         float r2, float g2, float b2) {
    if (num_planes >= 4) return;
    PlaneParams& p = planes[num_planes++];
    p.axis = axis;
    p.positive = positive;
    p.value = value;
    p.solid_color = false;
    p.color1[0] = r1; p.color1[1] = g1; p.color1[2] = b1;
    p.color2[0] = r2; p.color2[1] = g2; p.color2[2] = b2;
    p.enabled = true;
}

void RenderConfig::addPlane(int axis, bool positive, float value) {
    const float light = RayTracingConstants::PLANE_CHECKER_LIGHT_GRAY / 255.0f;
    const float dark  = RayTracingConstants::PLANE_CHECKER_DARK_GRAY  / 255.0f;
    addPlaneCheckerColors(axis, positive, value, light, light, light, dark, dark, dark);
}
