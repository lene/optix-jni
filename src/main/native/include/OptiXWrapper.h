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
    bool initialize(unsigned int maxInstances = 64);

    // Geometry configuration
    void setSphere(float x, float y, float z, float radius);
    void setSphereColor(float r, float g, float b, float a = 1.0f);
    void setIOR(float ior);
    void setScale(float scale);

    // Triangle mesh support
    void setTriangleMesh(
        const float* vertices,        // Interleaved pos+normal, 6 floats per vertex
        unsigned int num_vertices,
        const unsigned int* indices,  // 3 indices per triangle
        unsigned int num_triangles
    );
    void setTriangleMeshColor(float r, float g, float b, float a);
    void setTriangleMeshIOR(float ior);
    void clearTriangleMesh();         // Remove mesh (render sphere instead)
    bool hasTriangleMesh() const;     // Check if mesh is set

    // Camera configuration
    void setCamera(const float* eye, const float* lookAt, const float* up, float fov);
    void updateImageDimensions(int width, int height);

    // Light configuration
    void setLight(const float* direction, float intensity);  // Backward compatible (converts to single light)
    void setLights(const Light* lights, int count);  // Multiple lights (up to MAX_LIGHTS)

    // Shadow configuration
    void setShadows(bool enabled);

    // Antialiasing configuration
    void setAntialiasing(bool enabled, int maxDepth, float threshold);

    // Caustics (Progressive Photon Mapping) configuration
    void setCaustics(bool enabled, int photonsPerIter, int iterations, float initialRadius, float alpha);

    // Plane configuration
    void setPlane(int axis, bool positive, float value);
    void setPlaneSolidColor(float r, float g, float b);  // Set solid color mode with RGB 0.0-1.0
    void setPlaneCheckerColors(float r1, float g1, float b1, float r2, float g2, float b2);  // RGB 0.0-1.0

    // Rendering
    void render(int width, int height, unsigned char* output, RayStats* stats = nullptr);

    // Statistics
    bool getCausticsStats(CausticsStats* stats);  // Get PPM validation statistics

    // Multi-object instance management (IAS mode)
    // Transform is 4x3 row-major matrix: [m00 m01 m02 m03; m10 m11 m12 m13; m20 m21 m22 m23]
    // where m03, m13, m23 are translation components
    int addSphereInstance(const float* transform, float r, float g, float b, float a, float ior);
    int addTriangleMeshInstance(const float* transform, float r, float g, float b, float a, float ior);
    void removeInstance(int instanceId);
    void clearAllInstances();
    int getInstanceCount() const;
    bool isIASMode() const;
    void setIASMode(bool enabled);  // Switch between single-object and multi-object mode

    // Cleanup
    void dispose();

private:
    struct Impl;
    std::unique_ptr<Impl> impl;

    // Internal pipeline build helpers
    void buildPipeline();
    void buildGeometryAccelerationStructure();
    void buildTriangleMeshGAS();  // Build GAS for triangle mesh
    void buildIAS();              // Build Instance Acceleration Structure for multi-object scenes
};

#endif // OPTIX_WRAPPER_H
