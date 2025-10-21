#ifndef OPTIX_WRAPPER_H
#define OPTIX_WRAPPER_H

#include <memory>
#include <optix.h>
#include <cuda_runtime.h>

/**
 * C++ wrapper for OptiX ray tracing context and rendering.
 * This class encapsulates all OptiX state and provides a simplified
 * interface for the JNI layer.
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
