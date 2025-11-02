#ifndef OPTIX_WRAPPER_H
#define OPTIX_WRAPPER_H

#include <memory>

// Conditional includes based on availability
#ifdef HAVE_OPTIX
#include <optix.h>
#endif

#ifdef HAVE_CUDA
#include <cuda_runtime.h>
#endif

// Shared data structures for OptiX shaders (used by both C++ and CUDA code)
#include "OptiXData.h"

/**
 * C++ wrapper for OptiX ray tracing context and rendering.
 * This class encapsulates all OptiX state and provides a simplified
 * interface for the JNI layer.
 *
 * When built without CUDA/OptiX support (HAVE_CUDA and HAVE_OPTIX not defined),
 * provides stub implementations that return placeholder data.
 */
class OptiXWrapper {
public:
    OptiXWrapper();
    ~OptiXWrapper();

    // Initialization
    bool initialize();

    // Geometry configuration
    void setSphere(float x, float y, float z, float radius);
    void setSphereColor(float r, float g, float b);

    // Camera configuration
    void setCamera(const float* eye, const float* lookAt, const float* up, float fov);

    // Light configuration
    void setLight(const float* direction, float intensity);

    // Rendering
    void render(int width, int height, unsigned char* output);

    // Cleanup
    void dispose();

private:
    struct Impl;
    std::unique_ptr<Impl> impl;

#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
    // Pipeline build steps (called in sequence by buildPipeline)
    void buildPipeline();
    void buildGeometryAccelerationStructure();
    OptixModule loadPTXModules();
    void createProgramGroups(OptixModule sphere_module);
    void createPipeline();
    void setupShaderBindingTable();
#endif
};

#endif // OPTIX_WRAPPER_H
