#ifndef MATERIAL_PRESETS_H
#define MATERIAL_PRESETS_H

#include "OptiXData.h"

// Material presets for physically-based rendering (Sprint 7)
// Provides factory functions for common material types with accurate physical properties
//
// IOR values from real-world measurements:
// - Air/vacuum: 1.0
// - Water: 1.33
// - Glass (crown): 1.5
// - Diamond: 2.42
//
// Fresnel reflectance at perpendicular incidence (Schlick approximation):
// - F0 = ((n1 - n2) / (n1 + n2))^2
// - Glass (n=1.5): ~4%
// - Water (n=1.33): ~2%
// - Diamond (n=2.42): ~17%

namespace MaterialPresets {

constexpr float DIELECTRIC_ALPHA = 0.02f;  // Near-transparent alpha for glass/water/diamond

// Helper to create default material with all fields initialized
inline MaterialProperties createDefault() {
    MaterialProperties mat{};
    mat.color[0] = 1.0f;
    mat.color[1] = 1.0f;
    mat.color[2] = 1.0f;
    mat.color[3] = 1.0f;  // Fully opaque
    mat.ior = 1.0f;
    mat.roughness = 0.5f;
    mat.metallic = 0.0f;
    mat.specular = 0.5f;
    mat.base_color_texture = -1;
    mat.normal_texture = -1;
    mat.roughness_texture = -1;
    mat.padding[0] = 0;
    mat.padding[1] = 0;
    return mat;
}

// Glass: Clear dielectric with refraction
// IOR 1.5 gives ~4% Fresnel reflection at perpendicular
inline MaterialProperties glass() {
    MaterialProperties mat = createDefault();
    mat.color[3] = DIELECTRIC_ALPHA;  // Very transparent (alpha near 0)
    mat.ior = 1.5f;
    mat.roughness = 0.0f;  // Perfect smooth surface
    mat.metallic = 0.0f;   // Dielectric
    mat.specular = 1.0f;   // Full specular
    return mat;
}

// Water: Lower IOR than glass, ~2% Fresnel reflection
inline MaterialProperties water() {
    MaterialProperties mat = createDefault();
    mat.color[3] = DIELECTRIC_ALPHA;  // Very transparent
    mat.ior = 1.33f;
    mat.roughness = 0.0f;
    mat.metallic = 0.0f;
    mat.specular = 1.0f;
    return mat;
}

// Diamond: High IOR gives strong refraction and ~17% Fresnel reflection
inline MaterialProperties diamond() {
    MaterialProperties mat = createDefault();
    mat.color[3] = DIELECTRIC_ALPHA;  // Very transparent
    mat.ior = 2.42f;
    mat.roughness = 0.0f;
    mat.metallic = 0.0f;
    mat.specular = 1.0f;
    return mat;
}

// Chrome: Highly reflective metal
inline MaterialProperties chrome() {
    MaterialProperties mat = createDefault();
    mat.color[0] = 0.9f;
    mat.color[1] = 0.9f;
    mat.color[2] = 0.9f;
    mat.color[3] = 1.0f;   // Opaque
    mat.ior = 1.0f;        // Not used for metals (no refraction)
    mat.roughness = 0.0f;  // Mirror-like
    mat.metallic = 1.0f;   // Full metal
    mat.specular = 1.0f;
    return mat;
}

// Gold: Characteristic yellow-orange metallic color
inline MaterialProperties gold() {
    MaterialProperties mat = createDefault();
    mat.color[0] = 1.0f;
    mat.color[1] = 0.84f;
    mat.color[2] = 0.0f;
    mat.color[3] = 1.0f;
    mat.ior = 1.0f;
    mat.roughness = 0.1f;  // Slightly rough
    mat.metallic = 1.0f;
    mat.specular = 1.0f;
    return mat;
}

// Copper: Reddish-brown metallic color
inline MaterialProperties copper() {
    MaterialProperties mat = createDefault();
    mat.color[0] = 0.72f;
    mat.color[1] = 0.45f;
    mat.color[2] = 0.20f;
    mat.color[3] = 1.0f;
    mat.ior = 1.0f;
    mat.roughness = 0.2f;
    mat.metallic = 1.0f;
    mat.specular = 1.0f;
    return mat;
}

// Metal: Generic metal with customizable color
// Colored reflections, no refraction
inline MaterialProperties metal(float r = 0.8f, float g = 0.8f, float b = 0.8f) {
    MaterialProperties mat = createDefault();
    mat.color[0] = r;
    mat.color[1] = g;
    mat.color[2] = b;
    mat.color[3] = 1.0f;
    mat.ior = 1.0f;
    mat.roughness = 0.1f;
    mat.metallic = 1.0f;
    mat.specular = 1.0f;
    return mat;
}

// Plastic: Glossy dielectric with white specular highlights
// Shows colored diffuse with white reflections
inline MaterialProperties plastic(float r = 1.0f, float g = 1.0f, float b = 1.0f) {
    MaterialProperties mat = createDefault();
    mat.color[0] = r;
    mat.color[1] = g;
    mat.color[2] = b;
    mat.color[3] = 1.0f;
    mat.ior = 1.5f;        // Similar to glass for Fresnel
    mat.roughness = 0.3f;  // Glossy but not mirror
    mat.metallic = 0.0f;   // Dielectric
    mat.specular = 0.5f;
    return mat;
}

// Matte: Pure Lambertian diffuse, no specular
inline MaterialProperties matte(float r = 1.0f, float g = 1.0f, float b = 1.0f) {
    MaterialProperties mat = createDefault();
    mat.color[0] = r;
    mat.color[1] = g;
    mat.color[2] = b;
    mat.color[3] = 1.0f;
    mat.ior = 1.0f;
    mat.roughness = 1.0f;  // Maximum roughness = pure diffuse
    mat.metallic = 0.0f;
    mat.specular = 0.0f;   // No specular highlights
    return mat;
}

// Get preset by MaterialType enum
inline MaterialProperties fromType(MaterialType type,
                                   float r = 1.0f, float g = 1.0f, float b = 1.0f) {
    switch (type) {
        case MATERIAL_GLASS:   return glass();
        case MATERIAL_WATER:   return water();
        case MATERIAL_DIAMOND: return diamond();
        case MATERIAL_CHROME:  return chrome();
        case MATERIAL_GOLD:    return gold();
        case MATERIAL_COPPER:  return copper();
        case MATERIAL_METAL:   return metal(r, g, b);
        case MATERIAL_PLASTIC: return plastic(r, g, b);
        case MATERIAL_MATTE:   return matte(r, g, b);
        case MATERIAL_CUSTOM:
        default:               return createDefault();
    }
}

}  // namespace MaterialPresets

#endif // MATERIAL_PRESETS_H
