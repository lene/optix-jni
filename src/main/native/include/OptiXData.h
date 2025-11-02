#ifndef OPTIX_DATA_H
#define OPTIX_DATA_H

#ifdef HAVE_OPTIX
#include <optix.h>

// Launch parameters passed to OptiX shaders
struct Params {
    unsigned char* image;        // Output image buffer (RGBA)
    unsigned int   image_width;
    unsigned int   image_height;
    OptixTraversableHandle handle; // Scene geometry handle
};

// Ray generation shader data (camera)
struct RayGenData {
    float cam_eye[3];       // Camera position
    float camera_u[3];      // Camera right vector
    float camera_v[3];      // Camera up vector
    float camera_w[3];      // Camera forward vector (points toward lookAt)
};

// Miss shader data (background)
struct MissData {
    float r, g, b;          // Background color
};

// Hit group shader data (sphere material)
struct HitGroupData {
    float sphere_center[3]; // Sphere center position
    float sphere_color[3];  // Sphere color (RGB, 0.0-1.0)
    float light_dir[3];     // Light direction
    float light_intensity;  // Light intensity
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

#endif // HAVE_OPTIX

#endif // OPTIX_DATA_H
