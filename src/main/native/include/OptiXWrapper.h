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
};

#endif // OPTIX_WRAPPER_H
