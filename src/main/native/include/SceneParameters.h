#ifndef SCENE_PARAMETERS_H
#define SCENE_PARAMETERS_H

// This header is only included by .cpp files, so it's safe to include OptiXData.h
// (OptiXData.h has CUDA-specific syntax that won't work if transitively included in other headers)
#if !defined(__CUDACC__) && !defined(__align__)
#define __align__(n)  alignas(n)
#endif

#include "OptiXData.h"
#include "OptiXConstants.h"
#include <cstring>

/**
 * Encapsulates all scene geometry and material parameters.
 * Manages camera, sphere, plane, and lighting configuration.
 *
 * Responsibilities:
 * - Store scene parameter values
 * - Track dirty flags for pipeline rebuild
 * - Provide access to scene data for rendering
 */
class SceneParameters {
public:
    struct CameraParams {
        float eye[3] = {0.0f, 0.0f, RayTracingConstants::DEFAULT_CAMERA_Z_DISTANCE};
        float u[3] = {1.0f, 0.0f, 0.0f};
        float v[3] = {0.0f, 1.0f, 0.0f};
        float w[3] = {0.0f, 0.0f, -1.0f};
        float fov = RayTracingConstants::DEFAULT_FOV_DEGREES;
        bool dirty = false;
    };

    struct SphereParams {
        float center[3] = {0.0f, 0.0f, 0.0f};
        float radius = RayTracingConstants::DEFAULT_SPHERE_RADIUS;
        float color[4] = {1.0f, 1.0f, 1.0f, 1.0f};  // white, fully opaque
        float ior = MaterialConstants::IOR_VACUUM;
        float scale = 1.0f;  // 1.0 = meters
        bool dirty = false;
    };

    struct PlaneParams {
        int axis = 1;          // 0=X, 1=Y, 2=Z
        bool positive = true;  // true=positive normal, false=negative normal
        float value = RayTracingConstants::DEFAULT_FLOOR_PLANE_Y;
        bool dirty = false;
    };

    struct TriangleMeshParams {
        float color[4] = {0.8f, 0.8f, 0.8f, 1.0f};  // Default gray, opaque
        float ior = MaterialConstants::IOR_VACUUM;   // No refraction by default
        unsigned int num_vertices = 0;
        unsigned int num_triangles = 0;
        bool has_mesh = false;                       // True if mesh data is set
        bool dirty = false;
    };

    SceneParameters();

    // Camera configuration
    void setCamera(const float* eye, const float* lookAt, const float* up, float fov, int imageWidth, int imageHeight);
    const CameraParams& getCamera() const { return camera; }
    CameraParams& getCameraMutable() { return camera; }

    // Sphere configuration
    void setSphere(float x, float y, float z, float radius);
    void setSphereColor(float r, float g, float b, float a);
    void setIOR(float ior);
    void setScale(float scale);
    const SphereParams& getSphere() const { return sphere; }
    SphereParams& getSphereMutable() { return sphere; }

    // Plane configuration
    void setPlane(int axis, bool positive, float value);
    const PlaneParams& getPlane() const { return plane; }
    PlaneParams& getPlaneMutable() { return plane; }

    // Triangle mesh configuration
    void setTriangleMeshMeta(unsigned int numVertices, unsigned int numTriangles);
    void setTriangleMeshColor(float r, float g, float b, float a);
    void setTriangleMeshIOR(float ior);
    void clearTriangleMesh();
    const TriangleMeshParams& getTriangleMesh() const { return triangle_mesh; }
    TriangleMeshParams& getTriangleMeshMutable() { return triangle_mesh; }
    bool hasTriangleMesh() const { return triangle_mesh.has_mesh; }

    // Light configuration
    void setLights(const Light* lights, int count);
    const Light* getLights() const { return lights; }
    int getNumLights() const { return num_lights; }

    // Dirty flag management
    bool isAnyDirty() const;
    void clearDirtyFlags();

private:
    CameraParams camera;
    SphereParams sphere;
    PlaneParams plane;
    TriangleMeshParams triangle_mesh;
    Light lights[RayTracingConstants::MAX_LIGHTS];
    int num_lights = 1;  // Start with one default directional light

    void initializeDefaultLight();
};

#endif // SCENE_PARAMETERS_H
