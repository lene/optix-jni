#ifndef OPTIX_DATA_H
#define OPTIX_DATA_H

#include <optix.h>

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
