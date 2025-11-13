#ifndef OPTIX_DATA_H
#define OPTIX_DATA_H

#include <optix.h>

// Ray tracing configuration
// This constant is used by both the C++ pipeline setup and CUDA shaders
constexpr unsigned int MAX_TRACE_DEPTH = 5;  // Allow internal reflections in glass (entry + exit + reflections)

// Ray tracing constants (shared between C++ and CUDA shaders)
namespace RayTracingConstants {
    // Ray distance limits
    constexpr float MAX_RAY_DISTANCE = 1e16f;          // Maximum ray travel distance
    constexpr float CONTINUATION_RAY_OFFSET = 0.001f;  // Offset to avoid self-intersection

    // Color conversion (float [0,1] to byte [0,255])
    constexpr float COLOR_SCALE_FACTOR = 255.99f;      // Slightly less than 256 to avoid overflow
    constexpr float COLOR_BYTE_MAX = 255.0f;           // Maximum byte value for RGB

    // Alpha channel thresholds (based on 1-byte precision: 1/255 and 254/255)
    constexpr float ALPHA_FULLY_TRANSPARENT_THRESHOLD = 1.0f / 255.0f;   // ~0.00392 (alpha < this = transparent)
    constexpr float ALPHA_FULLY_OPAQUE_THRESHOLD = 254.0f / 255.0f;      // ~0.99608 (alpha >= this = opaque)

    // Beer-Lambert absorption (for volume rendering)
    constexpr float COLOR_CHANNEL_MIN_SAFE_VALUE = 1.0f / 255.0f;  // Minimum to avoid log(0) in absorption
    constexpr float BEER_LAMBERT_ABSORPTION_SCALE = 5.0f;          // Scale factor for absorption intensity

    // Sphere intersection refinement
    constexpr float SPHERE_INTERSECTION_REFINEMENT_THRESHOLD = 10.0f;  // Refine if |root| > threshold * radius

    // Lighting model
    constexpr float AMBIENT_LIGHT_FACTOR = 0.3f;  // Ambient light contribution (30%)

    // Plane rendering (checkered pattern)
    constexpr float PLANE_CHECKER_SIZE = 1.0f;            // Size of checker squares
    constexpr unsigned int PLANE_CHECKER_LIGHT_GRAY = 120;  // Light gray checker RGB value
    constexpr unsigned int PLANE_CHECKER_DARK_GRAY = 20;    // Dark gray checker RGB value
}

// Ray statistics tracking
struct RayStats {
    unsigned long long total_rays;      // Total rays cast during render
    unsigned long long primary_rays;    // Camera rays (equals pixel count)
    unsigned long long reflected_rays;  // Rays from Fresnel reflection
    unsigned long long refracted_rays;  // Rays transmitted through sphere
    unsigned int max_depth_reached;     // Deepest ray recursion
    unsigned int min_depth_reached;     // Shallowest ray recursion (should be 1)
};

// Launch parameters passed to OptiX shaders
// NOTE: Dynamic scene data moved here from SBT for better performance
// (parameter changes require only cudaMemcpy, not SBT rebuild)
struct Params {
    unsigned char* image;        // Output image buffer (RGBA)
    unsigned int   image_width;
    unsigned int   image_height;
    OptixTraversableHandle handle; // Scene geometry handle

    // Dynamic scene data (moved from SBT for performance)
    float sphere_color[4];      // Sphere color (RGBA, 0.0-1.0)
    float sphere_ior;           // Index of refraction (1.0 = no refraction, 1.5 = glass)
    float sphere_scale;         // Physical scale (1.0 = meters, 0.01 = centimeters)
    float light_dir[3];         // Light direction
    float light_intensity;      // Light intensity
    int   plane_axis;           // 0=X, 1=Y, 2=Z
    bool  plane_positive;       // true=positive normal, false=negative normal
    float plane_value;          // Plane position along axis

    // Ray statistics (GPU buffer)
    RayStats* stats;            // Pointer to GPU stats buffer
};

// Ray generation shader data (camera)
struct RayGenData {
    float cam_eye[3];       // Camera position
    float camera_u[3];      // Camera right vector
    float camera_v[3];      // Camera up vector
    float camera_w[3];      // Camera forward vector (points toward lookAt)
};

// Miss shader data (background only - plane moved to Params)
struct MissData {
    float r, g, b;          // Background color
};

// Hit group shader data (geometry only - material moved to Params)
struct HitGroupData {
    float sphere_center[3]; // Sphere center position
    float sphere_radius;    // Sphere radius
};

// Shader Binding Table (SBT) record structures
// These combine the OptiX header with our custom data
template <typename T>
struct SbtRecord {
    __align__(OPTIX_SBT_RECORD_ALIGNMENT) char header[OPTIX_SBT_RECORD_HEADER_SIZE];
    T data;
};

typedef SbtRecord<RayGenData>   RayGenSbtRecord;
typedef SbtRecord<MissData>     MissSbtRecord;
typedef SbtRecord<HitGroupData> HitGroupSbtRecord;

#endif // OPTIX_DATA_H
