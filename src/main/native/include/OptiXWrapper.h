#ifndef OPTIX_WRAPPER_H
#define OPTIX_WRAPPER_H

#include <memory>
#include <optix.h>
#include <cuda_runtime.h>

// Shared data structures for OptiX shaders (used by both C++ and CUDA code)
#include "OptiXData.h"

/**
 * C++ wrapper for OptiX ray tracing context and rendering.
 * This class encapsulates all OptiX state and provides a simplified
 * interface for the JNI layer.
 *
 * Requires CUDA Toolkit 12.0+ and NVIDIA OptiX SDK 8.0+.
 */
class OptiXWrapper {
public:
    OptiXWrapper();
    ~OptiXWrapper();

    // Initialization
    bool initialize();

    // Geometry configuration
    void setSphere(float x, float y, float z, float radius);
    void setSphereColor(float r, float g, float b, float a = 1.0f);
    void setIOR(float ior);
    void setScale(float scale);

    // Camera configuration
    void setCamera(const float* eye, const float* lookAt, const float* up, float fov);
    void updateImageDimensions(int width, int height);

    // Light configuration
    void setLight(const float* direction, float intensity);

    // Shadow configuration
    void setShadows(bool enabled);

    // Plane configuration
    void setPlane(int axis, bool positive, float value);

    // Rendering
    void render(int width, int height, unsigned char* output, RayStats* stats = nullptr);

    // Cleanup
    void dispose();

private:
    struct Impl;
    std::unique_ptr<Impl> impl;

    // Pipeline build steps (called in sequence by buildPipeline)
    void buildPipeline();
    void buildGeometryAccelerationStructure();
    OptixModule loadPTXModules();
    void createProgramGroups(OptixModule sphere_module);
    void createPipeline();
    void setupShaderBindingTable();
};

#endif // OPTIX_WRAPPER_H
