#ifndef OPTIX_DATA_H
#define OPTIX_DATA_H

#include <optix.h>
#include <cuda_runtime.h>

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
    // Multiple light sources
    constexpr int MAX_LIGHTS = 8;         // Maximum number of simultaneous light sources
    constexpr int MAX_SHADOW_SAMPLES = 16; // Maximum shadow samples per area light

    // Multiple planes (floor, walls, etc.)
    constexpr int MAX_PLANES = 4;  // Maximum number of simultaneous planes

    // Instance Acceleration Structure (IAS) limits
    constexpr unsigned int MAX_INSTANCES = 64;  // Maximum object instances in scene

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
    constexpr int AA_SUBPIXEL_COUNT = AA_SUBDIVISION_FACTOR * AA_SUBDIVISION_FACTOR;  // 9 subpixels in 3x3 grid
    constexpr float AA_GRID_SUBDIVISION_DIVISOR = 1.5f;  // Divisor for grid step calculation (positions at -1, 0, +1)
    constexpr float AA_DEFAULT_THRESHOLD = 0.1f;    // Default color difference threshold

    // Progressive Photon Mapping (Caustics)
    constexpr int MAX_HIT_POINTS = 2000000;         // Maximum stored hit points for caustics
    constexpr int DEFAULT_PHOTONS_PER_ITER = 100000; // Photons emitted per PPM iteration
    constexpr int DEFAULT_CAUSTICS_ITERATIONS = 10;  // Number of PPM iterations
    constexpr float DEFAULT_INITIAL_RADIUS = 1.0f;   // Initial photon gather radius
    constexpr float DEFAULT_PPM_ALPHA = 0.7f;        // Radius reduction factor (controls convergence)
    constexpr int CAUSTICS_GRID_RESOLUTION = 256;    // Spatial hash grid resolution (256^3 cells)
    constexpr int MAX_PHOTON_BOUNCES = 10;           // Maximum bounces for photon tracing

    // Numerical thresholds
    constexpr float RAY_PARALLEL_THRESHOLD = 1e-6f;    // Ray nearly parallel to surface (avoid div by zero)
    constexpr float FLUX_EPSILON = 1e-10f;             // Near-zero flux/area threshold for caustics
    constexpr float HIT_POINT_RAY_TMIN = 0.0001f;      // Hit point collection ray tmin (smaller than CONTINUATION_RAY_OFFSET)
    constexpr float CYLINDER_QUADRATIC_TOLERANCE = 1e-8f;      // Cylinder quadratic equation validity threshold
    constexpr float CYLINDER_CAP_PARALLEL_THRESHOLD = 1e-8f;   // Ray-cylinder axis parallel check threshold

    // Photon emission geometry
    constexpr float PHOTON_EMISSION_DISTANCE = 20.0f;      // Distance behind sphere for photon origin
    constexpr float PHOTON_DISK_RADIUS_MULTIPLIER = 2.0f;  // Disk radius = multiplier * sphere_radius

    // Thin-film interference constants (helpers.cu: computeThinFilmReflectance)
    constexpr float THIN_FILM_COSINE_CLAMP_MIN = 0.001f;  // Prevents cos(θ) ≤ 0 in Airy formula
    constexpr float THIN_FILM_AIRY_DENOM_GUARD = 1e-8f;   // Prevents divide-by-zero in Airy denominator
    constexpr float CIE_Y_INTEGRAL_NORM        = 106.5f;  // CIE 1931 Y colour-matching integral (D65)

    // Mathematical constants
    constexpr float SQRT_3 = 1.732050808f;             // sqrt(3), RGB cube diagonal for color distance normalization

    // Output buffer
    constexpr unsigned char ALPHA_OPAQUE_BYTE = 255;   // Fully opaque alpha for output image
}

// Material constants (Index of Refraction)
namespace MaterialConstants {
    constexpr float IOR_VACUUM = 1.0f;     // Vacuum/air (no refraction)
    constexpr float IOR_WATER = 1.33f;     // Water
    constexpr float IOR_GLASS = 1.5f;      // Standard glass
    constexpr float IOR_DIAMOND = 2.42f;   // Diamond (high dispersion)
}

// Default PBR material values
namespace MaterialDefaults {
    constexpr float DEFAULT_ROUGHNESS = 0.5f;  // Default middle roughness (0=mirror, 1=diffuse)
    constexpr float DEFAULT_METALLIC = 0.0f;    // Default non-metallic (dielectric)
    constexpr float DEFAULT_SPECULAR = 0.5f;    // Default specular intensity
}

// Rendering and physics constants for ray tracing
namespace RenderingConstants {
    // Normalized Device Coordinates (NDC)
    constexpr float PIXEL_CENTER_OFFSET = 0.5f;          // Pixel center offset for ray generation
    constexpr float NDC_SCALE = 2.0f;                    // Scale factor for NDC [-1,1] range
    constexpr float NDC_OFFSET = 1.0f;                   // Offset for NDC calculation
    
    // Physics and ray tracing
    constexpr float REFLECTION_SCALE = 2.0f;                 // Scale factor for reflection calculation
    constexpr float DIFFUSE_BLEND_FACTOR = 1.0f - RayTracingConstants::AMBIENT_LIGHT_FACTOR;  // Diffuse contribution (energy conservation: ambient + diffuse = 1.0)
}

// Shader Binding Table (SBT) parameters
namespace SBTConstants {
    // Ray types
    constexpr unsigned int RAY_TYPE_PRIMARY = 0;           // Primary camera ray
    constexpr unsigned int RAY_TYPE_SHADOW = 1;            // Shadow ray
    constexpr unsigned int RAY_TYPE_PHOTON = 2;            // Photon ray (caustics)

    // SBT indices
    constexpr unsigned int MISS_PRIMARY = 0;               // Primary ray miss shader
    constexpr unsigned int MISS_SHADOW = 1;                // Shadow ray miss shader
    constexpr unsigned int MISS_PHOTON = 2;                // Photon ray miss shader
    constexpr unsigned int OFFSET_SHADOW = 1;                // SBT offset for shadow rays
    constexpr unsigned int STRIDE_RAY_TYPES = 3;            // SBT stride (number of ray types)
    
    // Common ray parameters
    constexpr unsigned int PAYLOAD_SHADOW_FACTOR = 0;        // Payload index for shadow attenuation
    constexpr float SHADOW_FACTOR_FULLY_LIT = 1.0f;     // Shadow factor for fully lit pixels
    constexpr float RAY_TMIN_PRIMARY = 0.0f;            // Primary ray minimum distance
}

// Geometry types for IAS/SBT offset calculation
enum GeometryType {
    GEOMETRY_TYPE_SPHERE = 0,    // Custom sphere primitive (uses intersection program)
    GEOMETRY_TYPE_TRIANGLE = 1,  // Built-in triangle mesh (cube, sponge)
    GEOMETRY_TYPE_CYLINDER = 2,  // Custom cylinder primitive (uses intersection program)
    GEOMETRY_TYPE_CONE = 3,      // Custom cone primitive (uses intersection program)
    GEOMETRY_TYPE_PLANE = 4,     // Custom plane primitive (uses intersection program)
    GEOMETRY_TYPE_MENGER4D = 5,      // 4D Menger sponge analog (iterative IFS in custom IS)
    GEOMETRY_TYPE_SIERPINSKI4D = 6,  // 4D Sierpinski pentachoron analog (iterative IFS in custom IS)
    GEOMETRY_TYPE_HEXADECACHORON4D = 7,  // 4D Sierpinski 16-cell analog (iterative IFS in custom IS)
    GEOMETRY_TYPE_COUNT = 8          // Number of geometry types
};

// Per-instance material data for IAS (indexed by instance ID)
// Stored in GPU array, accessed via optixGetInstanceId()
struct InstanceMaterial {
    float color[4];             // RGBA color (alpha: 0=transparent, 1=opaque)
    float ior;                  // Index of refraction
    float roughness;            // 0=mirror, 1=diffuse (default: 0.5)
    float metallic;             // 0=dielectric, 1=metal (default: 0.0)
    float specular;             // Specular intensity (default: 0.5)
    float emission;             // Emission intensity (0.0-10.0, default: 0.0)
    unsigned int geometry_type; // GeometryType enum value
    int geometry_data_index;    // Index into geometry-specific data buffer (cone_data, plane_data, etc.; -1 = unused)
    float film_thickness;       // Thin-film thickness in nm (0 = no thin-film interference)
    int procedural_type;        // 0=none, 1=value_noise, 2=fbm, 3=worley, 4=gradient
    float procedural_scale;     // Noise coordinate scale (default 1.0)
    int normal_texture_index;   // Normal map index (-1 = no normal map)
    int roughness_texture_index; // Roughness map index (-1 = no roughness map)
    int image_texture_index;    // Image texture index (-1 = no texture)
    // Per-mesh triangle buffer pointers (IAS mode only)
    // Populated for triangle instances; nullptr/0 for spheres/cylinders
    float* vertices;            // Device pointer to vertex data
    unsigned int* indices;      // Device pointer to index data
    unsigned int vertex_stride; // Floats per vertex (6, 8, or 9)
};

// Extended material properties for physically-based rendering (Sprint 7)
// Builds on InstanceMaterial, adding PBR properties and texture references
struct MaterialProperties {
    // Base properties (compatible with InstanceMaterial)
    float color[4];              // RGBA (alpha: 0=transparent, 1=opaque)
    float ior;                   // Index of refraction (1.0 = no refraction)

    // Extended PBR properties
    float roughness;             // 0=mirror, 1=diffuse (default: 0.5)
    float metallic;              // 0=dielectric, 1=metal (default: 0.0)
    float specular;              // Specular intensity (default: 0.5)
    float emission;              // Emission intensity (0.0-10.0, default: 0.0)

    // Texture indices (-1 = no texture)
    int base_color_texture;      // Albedo/diffuse texture index

    // Thin-film interference
    float film_thickness;        // Film thickness in nm (0 = no thin-film interference)

    // Procedural texture and reserved texture fields
    int   procedural_type;    // 0=none, 1=value_noise, 2=fbm, 3=worley, 4=gradient
    float procedural_scale;   // Noise coordinate scale (default 1.0)
    int   roughness_texture;  // -1 = no texture (reserved for Task 20.7)
    int   normal_texture;     // -1 = no texture (reserved for Task 20.7)
};

// Material type for presets
enum MaterialType {
    MATERIAL_CUSTOM = 0,
    MATERIAL_GLASS = 1,
    MATERIAL_WATER = 2,
    MATERIAL_DIAMOND = 3,
    MATERIAL_CHROME = 4,
    MATERIAL_GOLD = 5,
    MATERIAL_COPPER = 6,
    MATERIAL_METAL = 7,
    MATERIAL_PLASTIC = 8,
    MATERIAL_MATTE = 9,
    MATERIAL_FILM = 10
};

// Maximum textures supported in a scene
constexpr unsigned int MAX_TEXTURES = 32;

// Vertex format constants (Sprint 7: UV coordinates)
// Vertices can be either 6 floats (pos + normal) or 8 floats (pos + normal + uv)
constexpr unsigned int VERTEX_STRIDE_NO_UV = 6;    // 6 floats: pos(3) + normal(3)
constexpr unsigned int VERTEX_STRIDE_WITH_UV = 8;  // 8 floats: pos(3) + normal(3) + uv(2)
constexpr unsigned int VERTEX_STRIDE_WITH_ALPHA = 9;  // 9 floats: pos(3) + normal(3) + uv(2) + alpha(1)

// Light source types
enum class LightType {
    DIRECTIONAL = 0,  // Parallel rays from infinity (sun-like), no distance attenuation
    POINT = 1,        // Radiate from position, inverse-square falloff
    AREA = 2          // Finite-size disk emitter; produces soft shadow penumbra via multi-sample tracing
};

// Area light emitter shapes — currently only DISK is implemented.
// Enum is present at all layers (C++, JNI Scala, DSL) so future shapes (RECT=1, SPHERE=2)
// can be added without changing any interface.
enum class AreaLightShape {
    DISK = 0   // Circular disk defined by position, normal, and radius
};

// Light source definition
struct Light {
    LightType type;       // Directional, point, or area light
    float direction[3];   // Direction TO light source (for DIRECTIONAL only)
    float position[3];    // Light position (for POINT and AREA)
    float color[3];       // RGB color (0.0-1.0)
    float intensity;      // Brightness multiplier
    // Area light fields — ignored for DIRECTIONAL and POINT:
    AreaLightShape shape;  // Emitter shape (DISK only currently)
    float normal[3];       // Disk facing direction, normalized, pointing toward scene
    float radius;          // Disk radius in world units
    int   shadow_samples;  // Number of shadow rays cast per shaded point (1–MAX_SHADOW_SAMPLES)
};

// Triangle mesh data for rendering arbitrary geometry (host-side)
// Used to pass mesh data from Scala through JNI to C++
struct TriangleMeshData {
    float* vertices;              // Interleaved position (x,y,z) + normal (nx,ny,nz)
    unsigned int* indices;        // Triangle indices (3 per triangle)
    unsigned int num_vertices;    // Number of vertices
    unsigned int num_triangles;   // Number of triangles

    // Material properties (same as sphere for consistency)
    float color[4];               // RGBA (alpha: 0=transparent, 1=opaque)
    float ior;                    // Index of refraction (1.0 = no refraction)
};

// Hit group data for triangle mesh (stored in SBT, used by shaders)
// Contains device pointers to GPU buffers
struct TriangleHitGroupData {
    float* vertices;              // Device pointer to vertex data
    unsigned int* indices;        // Device pointer to index data
    unsigned int vertex_stride;   // Floats per vertex (6 or 8)
    float color[4];               // Material color (RGBA)
    float ior;                    // Index of refraction
    cudaTextureObject_t base_color_texture;  // 0 if no texture
};

// Texture data stored on host (for management)
struct TextureData {
    cudaArray_t cuda_array;           // CUDA array holding texture data
    cudaTextureObject_t texture_obj;  // Texture object for shader access
    unsigned int width;
    unsigned int height;
    unsigned int bytes_per_pixel;
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

// Photon payload for RAY_TYPE_PHOTON traces
// Packed into 10 uint32_t registers for optixTrace
struct PhotonPayload {
    float flux[3];        // [p0-p2] photon energy (in: initial flux; out: updated after Fresnel/Beer-Lambert)
    float new_origin[3];  // [p3-p5] next bounce origin (out: set by closesthit)
    float new_dir[3];     // [p6-p8] refracted direction (out: set by closesthit)
    unsigned int flags;   // [p9] bit 0 = alive, bit 1 = deposited
};

// Caustics statistics for validation (C1-C8 test ladder)
// Tracks energy flow and convergence metrics for PPM implementation
struct CausticsStats {
    // C1: Photon emission
    unsigned long long photons_emitted;       // Total photons emitted this render
    unsigned long long photons_toward_sphere; // Photons directed toward sphere

    // C2: Sphere hit rate
    unsigned long long sphere_hits;           // Photons that hit the sphere
    unsigned long long sphere_misses;         // Photons that missed the sphere

    // C3: Refraction (tracked per-photon is expensive, so aggregate)
    unsigned long long refraction_events;     // Total refraction events
    unsigned long long tir_events;            // Total internal reflection events

    // C4: Deposition
    unsigned long long photons_deposited;     // Photons that deposited energy on hit points
    unsigned long long hit_points_with_flux;  // Hit points that received any flux

    // C5: Energy conservation
    double total_flux_emitted;                // Sum of emitted photon flux (RGB summed)
    double total_flux_deposited;              // Sum of deposited flux on hit points
    double total_flux_absorbed;               // Sum of Beer-Lambert absorption losses
    double total_flux_reflected;              // Sum of Fresnel reflection losses

    // C6: Convergence metrics (per-iteration tracking)
    float avg_radius;                         // Average hit point radius
    float min_radius;                         // Minimum hit point radius
    float max_radius;                         // Maximum hit point radius
    float flux_variance;                      // Variance in flux across hit points

    // C7: Brightness metrics
    float max_caustic_brightness;             // Peak brightness in caustic region
    float avg_floor_brightness;               // Average brightness on floor (ambient)

    // Timing
    float hit_point_generation_ms;            // Time for Phase 1
    float photon_tracing_ms;                  // Time for Phase 2 (all iterations)
    float radiance_computation_ms;            // Time for Phase 3
};

// Parameters for Progressive Photon Mapping (caustics)
struct CausticsParams {
    bool enabled;                    // Enable caustics rendering
    int photons_per_iteration;       // Photons to emit each PPM iteration
    int iterations;                  // Number of PPM iterations to run
    float initial_radius;            // Starting search radius
    float alpha;                     // Radius reduction factor (0.7 typical)
    int current_iteration;           // Current iteration (for RNG seeding)

    // Target geometry bounding sphere for photon emission aiming
    float caustic_target_center[3];  // Bounding sphere center of refractive geometry
    float caustic_target_radius;     // Bounding sphere radius of refractive geometry

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

    // Validation statistics (pointer to GPU buffer)
    CausticsStats* stats;            // Detailed caustics statistics for validation
};

// Ray statistics tracking
struct RayStats {
    unsigned long long total_rays;      // Total rays cast during render
    unsigned long long primary_rays;    // Camera rays (equals pixel count)
    unsigned long long reflected_rays;  // Rays from Fresnel reflection
    unsigned long long refracted_rays;  // Rays transmitted through sphere
    unsigned long long shadow_rays;     // Rays cast to check light occlusion
    unsigned long long aa_rays;         // Additional rays from adaptive antialiasing
    unsigned long long aa_stack_overflows; // Times AA subdivision skipped due to full stack
    unsigned int max_depth_reached;     // Deepest ray recursion
    unsigned int min_depth_reached;     // Shallowest ray recursion (should be 1)
};

// Plane definition for miss shader (up to 4 simultaneous planes)
struct PlaneParams {
    int axis;        // 0=X, 1=Y, 2=Z
    bool positive;   // which side the plane normal faces
    float value;     // position along axis
    bool solid_color;
    float color1[3]; // primary or checker-A color
    float color2[3]; // checker-B color (ignored for solid)
    bool enabled;
    // Material properties (Sprint 13.1) — defaults give original matte behavior
    float roughness;       // 0.0 (mirror-smooth) to 1.0 (fully diffuse)
    float metallic;        // 0.0 (dielectric) to 1.0 (metallic)
    float specular;        // specular reflectance at normal incidence
    float emission;        // emissive brightness
    int   texture_index;   // -1 = no texture, >= 0 = index into params.textures[]
};

// Cylinder geometry data for ray intersection
// Stored in params.cylinder_data buffer, accessed in intersection shader via instance material's geometry_data_index
struct CylinderData {
    float p0[3];      // Start point (12 bytes)
    float radius;     // Radius (4 bytes)
    float p1[3];      // End point (12 bytes)
    float padding;    // Alignment padding (4 bytes)
    // Total: 32 bytes (GPU-friendly alignment)
};

// Cone geometry data for ray intersection
// Stored in params.cone_data buffer, indexed via InstanceMaterial.geometry_data_index
struct ConeData {
    float apex[3];    // Apex point (12 bytes)
    float radius;     // Base radius (4 bytes)
    float base[3];    // Base center point (12 bytes)
    float padding;    // Alignment padding (4 bytes)
    // Total: 32 bytes (GPU-friendly alignment)
};

// Plane geometry data for ray intersection
// Stored in params.plane_data buffer, indexed via InstanceMaterial.geometry_data_index
struct PlaneData {
    float normal[3];      // Unit normal vector (12 bytes)
    float distance;       // Signed distance from origin: dot(normal, point) = distance (4 bytes)
    float color1[3];      // Primary / checker-A color (12 bytes)
    float checker_size;   // Checker square size in world units (4 bytes)
    float color2[3];      // Checker-B color (12 bytes)
    int   solid_color;    // 1 = solid (color1 only), 0 = checker pattern (4 bytes)
    // Total: 48 bytes
};

// 4D Menger sponge per-instance data for the IFS intersection shader.
// Stored in params.menger4d_data buffer, indexed via InstanceMaterial.geometry_data_index
struct Menger4DData {
    float pos[3];          // 3D world position of the sponge center (12 bytes)
    float scale;           // World scale (projected coords multiplied by this) (4 bytes)
    float rotation4d[16];  // 4x4 rotation matrix in 4D, row-major (64 bytes)
    float eye_w;           // W coordinate of perspective eye point (4 bytes)
    float screen_w;        // W coordinate of projection screen (4 bytes)
    int   level;           // IFS recursion depth (4 bytes)
    int   dist_threshold;  // Generator keep predicate: abs-sum > dist_threshold (4 bytes)
    // Total: 96 bytes
};

// 4D Sierpinski pentachoron per-instance data for the IFS intersection shader.
// Stored in params.sierpinski4d_data buffer, indexed via InstanceMaterial.geometry_data_index
struct Sierpinski4DData {
    float pos[3];          // 3D world position of the fractal center (12 bytes)
    float scale;           // World scale (projected coords multiplied by this) (4 bytes)
    float rotation4d[16];  // 4x4 rotation matrix in 4D, row-major (64 bytes)
    float eye_w;           // W coordinate of perspective eye point (4 bytes)
    float screen_w;        // W coordinate of projection screen (4 bytes)
    int   level;           // IFS recursion depth (4 bytes)
    float hit_bias;        // Added to reported t to let the fine instance win over the coarse (4 bytes)
    // Total: 96 bytes
};

// 4D Sierpinski 16-cell (hexadecachoron) per-instance data for the IFS intersection shader.
// Stored in params.hexadecachoron4d_data buffer, indexed via InstanceMaterial.geometry_data_index
struct Hexadecachoron4DData {
    float pos[3];          // 3D world position of the fractal center (12 bytes)
    float scale;           // World scale (projected coords multiplied by this) (4 bytes)
    float rotation4d[16];  // 4x4 rotation matrix in 4D, row-major (64 bytes)
    float eye_w;           // W coordinate of perspective eye point (4 bytes)
    float screen_w;        // W coordinate of projection screen (4 bytes)
    int   level;           // IFS recursion depth (4 bytes)
    float hit_bias;        // Added to reported t to let the fine instance win over the coarse (4 bytes)
    // Total: 96 bytes
};

// Launch parameters passed to OptiX shaders
// NOTE: Dynamic scene data moved here from SBT for better performance
// (parameter changes require only cudaMemcpy, not SBT rebuild)
struct BaseParams {
    unsigned char* image;        // Output image buffer (RGBA)
    float4*        linear_color; // Optional linear HDR color output for denoising
    float4*        denoise_albedo; // Optional albedo guide output
    float4*        denoise_normal; // Optional normal guide output
    bool           write_denoise_guides; // Write first-hit guide AOVs for primary rays
    unsigned int   image_width;
    unsigned int   image_height;
    OptixTraversableHandle handle; // Scene geometry handle (GAS or IAS)

    // Instance Acceleration Structure (IAS) support
    bool use_ias;                           // true = multi-object mode (use IAS), false = single GAS
    unsigned int sbt_base_offset;           // SBT hitgroup base offset for single-object mode
                                            // = geometry_type * STRIDE_RAY_TYPES (0 in IAS mode)
    InstanceMaterial* instance_materials;   // Device pointer to per-instance material array
    unsigned int num_instances;             // Number of active instances

    // Texture support (IAS mode only)
    cudaTextureObject_t* textures;          // Device pointer to array of texture objects
    unsigned int num_textures;              // Number of textures in array

    // Dynamic scene data (moved from SBT for performance)
    float sphere_color[4];      // Sphere color (RGBA, 0.0-1.0)
    float sphere_ior;           // Index of refraction (1.0 = no refraction, 1.5 = glass)
    float sphere_scale;         // Physical scale (1.0 = meters, 0.01 = centimeters)
    Light lights[RayTracingConstants::MAX_LIGHTS];  // Array of light sources
    int   num_lights;           // Number of active lights
    PlaneParams planes[RayTracingConstants::MAX_PLANES];  // Up to MAX_PLANES simultaneous planes
    int   num_planes;           // Number of active planes (0 = no plane, show background)
    bool  shadows_enabled;             // Enable shadow ray tracing
    bool  transparent_shadows_enabled; // Enable colored shadows through transparent objects (Sprint 13.2)
    float bg_r, bg_g, bg_b;    // Background color (overrides MissData SBT)
    bool                env_map_enabled;   // true = sample equirectangular env map
    cudaTextureObject_t env_map_texture;   // equirectangular HDR texture object
    int   tonemap_operator;  // 0=none (clip), 1=reinhard, 2=aces
    float tonemap_exposure;  // pre-tone-map exposure multiplier (default 1.0)
    float camera_u[3];       // Camera right vector for guide normal transform
    float camera_v[3];       // Camera up vector for guide normal transform
    float camera_w[3];       // Camera forward vector for guide normal transform

    // IBL — image-based lighting
    bool                ibl_enabled;
    float               ibl_strength;
    int                 ibl_samples;
    int                 env_width;           // HDR env map pixel width
    int                 env_height;          // HDR env map pixel height
    unsigned int        frame_seed_offset;   // varies per accumulation frame
    cudaTextureObject_t env_cdf_marginal;    // 1D, height floats — marginal CDF
    cudaTextureObject_t env_cdf_cond;        // 2D, width×height floats — conditional CDF
    cudaTextureObject_t env_pdf;             // 2D, width×height floats — luminance PDF

    // Ray statistics (GPU buffer)
    RayStats* stats;            // Pointer to GPU stats buffer

    // Cylinder geometry data buffer (for cylinder intersection shader)
    // Indexed by cylinder_index stored in InstanceMaterial.geometry_data_index for cylinder instances
    CylinderData* cylinder_data;    // Device pointer to array of cylinder geometry
    unsigned int num_cylinders;     // Number of cylinders in array

    // Cone geometry data buffer (for cone intersection shader)
    ConeData* cone_data;            // Device pointer to array of cone geometry
    unsigned int num_cones;         // Number of cones in array

    // Plane geometry data buffer (for plane intersection shader)
    PlaneData* plane_data;          // Device pointer to array of plane geometry
    unsigned int num_plane_data;    // Number of IS-plane instances

    // 4D Menger sponge geometry data buffer (for IFS intersection shader)
    Menger4DData* menger4d_data;    // Device pointer to array of Menger4DData
    unsigned int num_menger4d;      // Number of menger4d instances

    // 4D Sierpinski pentachoron geometry data buffer (for IFS intersection shader)
    Sierpinski4DData* sierpinski4d_data;    // Device pointer to array of Sierpinski4DData
    unsigned int num_sierpinski4d;          // Number of sierpinski4d instances

    // 4D Sierpinski 16-cell (hexadecachoron) geometry data buffer (for IFS intersection shader)
    Hexadecachoron4DData* hexadecachoron4d_data;    // Device pointer to array of Hexadecachoron4DData
    unsigned int num_hexadecachoron4d;              // Number of hexadecachoron4d instances

    // Adaptive antialiasing
    bool  aa_enabled;           // Enable adaptive antialiasing
    int   aa_max_depth;         // Maximum recursion depth (1-4)
    float aa_threshold;         // Color difference threshold for edge detection (0.0-1.0)

    // Ray depth
    int   max_ray_depth;        // Maximum ray bounce depth (must be <= MAX_TRACE_DEPTH)

    // Progressive Photon Mapping (Caustics)
    CausticsParams caustics;    // Caustics rendering parameters and GPU buffers

    // Fog / depth cue
    float fog_density;     // 0.0 = no fog; exponential: exp(-fog_density * t)
    float fog_r, fog_g, fog_b;  // Fog color [0,1]
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
typedef SbtRecord<TriangleHitGroupData> TriangleHitGroupSbtRecord;
typedef SbtRecord<CylinderData> CylinderHitGroupSbtRecord;

#endif // OPTIX_DATA_H
