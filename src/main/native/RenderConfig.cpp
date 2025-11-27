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

void RenderConfig::setPlaneSolidColor(float r, float g, float b) {
    plane_solid_color = true;
    plane_color1[0] = r;
    plane_color1[1] = g;
    plane_color1[2] = b;
}

void RenderConfig::setPlaneCheckerColors(float r1, float g1, float b1, float r2, float g2, float b2) {
    plane_solid_color = false;
    plane_color1[0] = r1;
    plane_color1[1] = g1;
    plane_color1[2] = b1;
    plane_color2[0] = r2;
    plane_color2[1] = g2;
    plane_color2[2] = b2;
}
