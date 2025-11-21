#ifndef OPTIX_DATA_H
#define OPTIX_DATA_H

#include <optix.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// Ray tracing configuration
// This constant is used by both the C++ pipeline setup and CUDA shaders
constexpr unsigned int MAX_TRACE_DEPTH = 5;  // Allow internal reflections in glass (entry + exit + reflections)

// Ray tracing constants (shared between C++ and CUDA shaders)
namespace RayTracingConstants {
    // Ray distance limits
    constexpr float MAX_RAY_DISTANCE = 1e16f;          // Maximum ray travel distance
    constexpr float SHADOW_RAY_MAX_DISTANCE = 1e16f;   // Shadow rays use effectively infinite distance
    constexpr float CONTINUATION_RAY_OFFSET = 0.001f;  // Offset to avoid self-intersection
    constexpr float SHADOW_RAY_OFFSET = 0.001f;        // Offset for shadow ray origin (avoid shadow acne)

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
    constexpr float AMBIENT_LIGHT_FACTOR = 0.3f;       // Ambient light contribution (30%)
    constexpr float MIN_LIGHTING_THRESHOLD = 1.0f / 255.0f;  // Minimum NdotL to contribute (sub-pixel)

    // Plane rendering (checkered pattern)
    constexpr float PLANE_CHECKER_SIZE = 1.0f;            // Size of checker squares
    constexpr unsigned int PLANE_CHECKER_LIGHT_GRAY = 120;  // Light gray checker RGB value
    constexpr unsigned int PLANE_CHECKER_DARK_GRAY = 20;    // Dark gray checker RGB value
    constexpr unsigned int PLANE_SOLID_LIGHT_GRAY = 200;    // Solid plane color (good for shadow visibility)

    // Multiple light sources
    constexpr int MAX_LIGHTS = 8;  // Maximum number of simultaneous light sources

    // Default geometry values
    constexpr float DEFAULT_SPHERE_RADIUS = 1.5f;      // Default sphere size for demos and tests
    constexpr float DEFAULT_CAMERA_Z_DISTANCE = 3.0f;  // Default camera distance from origin
    constexpr float DEFAULT_FOV_DEGREES = 60.0f;       // Default field of view
    constexpr float DEFAULT_FLOOR_PLANE_Y = -2.0f;     // Default floor plane position

    // Angle conversion
    constexpr float DEG_TO_RAD = M_PI / 180.0f;        // Degrees to radians multiplier
    constexpr float RAD_TO_DEG = 180.0f / M_PI;        // Radians to degrees multiplier

    // Adaptive antialiasing
    constexpr int AA_MAX_DEPTH_LIMIT = 4;           // Maximum recursion depth for AA
    constexpr int AA_SUBDIVISION_FACTOR = 3;        // 3x3 subdivision per level
    constexpr float AA_DEFAULT_THRESHOLD = 0.1f;    // Default color difference threshold

    // Progressive Photon Mapping (Caustics)
    constexpr int MAX_HIT_POINTS = 2000000;         // Maximum stored hit points for caustics
    constexpr int DEFAULT_PHOTONS_PER_ITER = 100000; // Photons emitted per PPM iteration
    constexpr int DEFAULT_CAUSTICS_ITERATIONS = 10;  // Number of PPM iterations
    constexpr float DEFAULT_INITIAL_RADIUS = 0.1f;   // Initial photon gather radius
    constexpr float DEFAULT_PPM_ALPHA = 0.7f;        // Radius reduction factor (controls convergence)
    constexpr int CAUSTICS_GRID_RESOLUTION = 128;    // Spatial hash grid resolution (128^3 cells)
    constexpr int MAX_PHOTON_BOUNCES = 10;           // Maximum bounces for photon tracing
}

// Material constants (Index of Refraction)
namespace MaterialConstants {
    constexpr float IOR_VACUUM = 1.0f;     // Vacuum/air (no refraction)
    constexpr float IOR_WATER = 1.33f;     // Water
    constexpr float IOR_GLASS = 1.5f;      // Standard glass
    constexpr float IOR_DIAMOND = 2.42f;   // Diamond (high dispersion)
}

// Light source types
enum class LightType {
    DIRECTIONAL = 0,  // Parallel rays from infinity (sun-like), no distance attenuation
    POINT = 1         // Radiate from position, inverse-square falloff
};

// Light source definition
struct Light {
    LightType type;       // Directional or point light
    float direction[3];   // Light direction (normalized) for directional lights
    float position[3];    // Light position for point lights
    float color[3];       // RGB color (0.0-1.0)
    float intensity;      // Brightness multiplier
};

// Photon for Progressive Photon Mapping (caustics)
// Represents a single photon emitted from a light source
struct Photon {
    float position[3];    // World position where photon landed
    float direction[3];   // Incoming direction (normalized)
    float flux[3];        // RGB energy/power carried by photon
    float pad;            // Alignment padding
};

// Hit point for Progressive Photon Mapping (caustics)
// Represents a diffuse surface point that can receive caustics
struct HitPoint {
    float position[3];    // World position on diffuse surface
    float normal[3];      // Surface normal at hit point
    float flux[3];        // Accumulated photon flux (RGB)
    float radius;         // Current search radius (shrinks over iterations)
    unsigned int n;       // Number of photons accumulated so far
    unsigned int pixel_x; // Pixel X coordinate for final write
    unsigned int pixel_y; // Pixel Y coordinate for final write
    float weight[3];      // BRDF weight for this view direction
    unsigned int new_photons; // Photons accumulated this iteration (for radius update)
    float pad;            // Alignment padding
};

// Parameters for Progressive Photon Mapping (caustics)
struct CausticsParams {
    bool enabled;                    // Enable caustics rendering
    int photons_per_iteration;       // Photons to emit each PPM iteration
    int iterations;                  // Number of PPM iterations to run
    float initial_radius;            // Starting search radius
    float alpha;                     // Radius reduction factor (0.7 typical)
    int current_iteration;           // Current iteration (for RNG seeding)

    // GPU buffers (set by host before launch)
    HitPoint* hit_points;            // Array of hit points on diffuse surfaces
    unsigned int* num_hit_points;    // Pointer to GPU counter (for atomicAdd)
    unsigned int* grid;              // Spatial hash grid (cell -> first hit point index)
    unsigned int* grid_counts;       // Number of hit points per cell
    unsigned int* grid_offsets;      // Prefix sum for sorted hit point indices

    // Grid bounding box and parameters
    float grid_min[3];               // Bounding box minimum
    float grid_max[3];               // Bounding box maximum
    float cell_size;                 // Size of each grid cell
    unsigned int grid_resolution;    // Number of cells per dimension

    // Statistics
    unsigned long long total_photons_traced;  // Total photons traced across all iterations
};

// Ray statistics tracking
struct RayStats {
    unsigned long long total_rays;      // Total rays cast during render
    unsigned long long primary_rays;    // Camera rays (equals pixel count)
    unsigned long long reflected_rays;  // Rays from Fresnel reflection
    unsigned long long refracted_rays;  // Rays transmitted through sphere
    unsigned long long shadow_rays;     // Rays cast to check light occlusion
    unsigned long long aa_rays;         // Additional rays from adaptive antialiasing
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
    Light lights[RayTracingConstants::MAX_LIGHTS];  // Array of light sources
    int   num_lights;           // Number of active lights
    int   plane_axis;           // 0=X, 1=Y, 2=Z
    bool  plane_positive;       // true=positive normal, false=negative normal
    float plane_value;          // Plane position along axis
    bool  plane_solid_color;    // true=solid color, false=checkerboard pattern
    float plane_color1[3];      // RGB for solid color or light checker (0.0-1.0)
    float plane_color2[3];      // RGB for dark checker (0.0-1.0, only used when !plane_solid_color)
    bool  shadows_enabled;      // Enable shadow ray tracing

    // Ray statistics (GPU buffer)
    RayStats* stats;            // Pointer to GPU stats buffer

    // Adaptive antialiasing
    bool  aa_enabled;           // Enable adaptive antialiasing
    int   aa_max_depth;         // Maximum recursion depth (1-4)
    float aa_threshold;         // Color difference threshold for edge detection (0.0-1.0)

    // Progressive Photon Mapping (Caustics)
    CausticsParams caustics;    // Caustics rendering parameters and GPU buffers
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
