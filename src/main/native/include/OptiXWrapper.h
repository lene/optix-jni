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
        const float* vertices,        // Interleaved pos+normal+uv, stride floats per vertex
        unsigned int num_vertices,
        const unsigned int* indices,  // 3 indices per triangle
        unsigned int num_triangles,
        unsigned int vertex_stride    // 6 (no UV) or 8 (with UV) floats per vertex
    );
    void setTriangleMeshColor(float r, float g, float b, float a);
    void setTriangleMeshIOR(float ior);
    void clearTriangleMesh();         // Remove mesh (render sphere instead)
    bool hasTriangleMesh() const;     // Check if mesh is set

    // 4D quad mesh upload with GPU-side projection (Sprint 18.3 Cut A).
    // - quads4d: N quads × 4 corners × (x,y,z,w) — 16*num_quads floats.
    // - uvs_or_null: optional N quads × 4 corners × (u,v) — 8*num_quads floats,
    //   or nullptr to use default unit-square UVs.
    // - rotXW_deg / rotYW_deg / rotZW_deg: 4D rotation in degrees, composed
    //   on the host as R_xw * R_yw * R_zw (matches Rotation.scala).
    // - center_{x,y,z}: 3D translation applied after projection.
    // Returns the mesh index (slot in triangle_meshes[]).
    // The 4D and projected buffers stay resident on the device for the
    // mesh's lifetime so Cut F's updateMesh4DProjection can re-launch the
    // kernel without re-uploading.
    int setTriangleMesh4DQuads(
        const float* quads4d,
        int num_quads,
        const float* uvs_or_null,
        float eyeW, float screenW,
        float rotXW_deg, float rotYW_deg, float rotZW_deg,
        float center_x, float center_y, float center_z
    );

    // Sprint 18.3 Cut F: per-frame update of 4D rotation + projection.
    // Re-launches the projection kernel against the resident 4D buffers,
    // refits the mesh's GAS via OPERATION_UPDATE, and refits the IAS if
    // `use_ias` is true. Returns 0 on success; negative on error
    // (mesh_index out of range, or mesh was not uploaded as 4D-projected).
    int updateMesh4DProjection(
        int mesh_index,
        float eyeW, float screenW,
        float rotXW_deg, float rotYW_deg, float rotZW_deg,
        float center_x, float center_y, float center_z
    );

    // In-place CPU mesh update for the interactive 4D-rotation fast path.
    // Re-uploads vertex and index data to the existing slot `mesh_index`,
    // rebuilds that mesh's GAS, and marks the IAS dirty so it is refitted
    // on the next render() call — without calling clearAllInstances().
    // Returns 0 on success; -1 if mesh_index is out of range or the mesh
    // was uploaded via setTriangleMesh4DQuads (GPU path only).
    int updateCpuTriangleMesh(
        int mesh_index,
        const float* vertices,
        unsigned int num_vertices,
        const unsigned int* indices,
        unsigned int num_triangles,
        unsigned int vertex_stride
    );

    // Camera configuration
    void setCamera(const float* eye, const float* lookAt, const float* up, float fov);
    void updateImageDimensions(int width, int height);

    // Light configuration
    void setLight(const float* direction, float intensity);  // Backward compatible (converts to single light)
    void setLights(const Light* lights, int count);  // Multiple lights (up to MAX_LIGHTS)

    // Shadow configuration
    void setShadows(bool enabled);
    void setTransparentShadows(bool enabled);  // Sprint 13.2

    // Antialiasing configuration
    void setAntialiasing(bool enabled, int maxDepth, float threshold);

    // Ray depth configuration
    void setMaxRayDepth(int depth);

    // Caustics (Progressive Photon Mapping) configuration
    void setCaustics(bool enabled, int photonsPerIter, int iterations, float initialRadius, float alpha);

    // Background color configuration
    void setBackgroundColor(float r, float g, float b);

    // Plane configuration
    void clearPlanes();
    void addPlane(int axis, bool positive, float value);  // default gray checker
    void addPlaneSolidColor(int axis, bool positive, float value, float r, float g, float b);
    void addPlaneCheckerColors(int axis, bool positive, float value,
                               float r1, float g1, float b1,
                               float r2, float g2, float b2);
    // Material-aware plane methods (Sprint 13.1)
    void addPlaneSolidColorWithMaterial(
        int axis, bool positive, float value,
        float r, float g, float b,
        float roughness, float metallic, float specular, float emission,
        int texture_index);
    void addPlaneCheckerColorsWithMaterial(
        int axis, bool positive, float value,
        float r1, float g1, float b1, float r2, float g2, float b2,
        float roughness, float metallic, float specular, float emission,
        int texture_index);

    // Texture management
    int uploadTexture(
        const char* name,
        const unsigned char* image_data,
        unsigned int width,
        unsigned int height
    );  // Returns texture index, or -1 on error
    void releaseTextures();  // Free all uploaded textures

    // Rendering
    void render(int width, int height, unsigned char* output, RayStats* stats = nullptr);

    // Statistics
    bool getCausticsStats(CausticsStats* stats);  // Get PPM validation statistics

    // Multi-object instance management (IAS mode)
    // Transform is 4x3 row-major matrix: [m00 m01 m02 m03; m10 m11 m12 m13; m20 m21 m22 m23]
    // where m03, m13, m23 are translation components
    int addSphereInstance(
        const float* transform, float r, float g, float b, float a, float ior,
        float roughness = 0.5f, float metallic = 0.0f, float specular = 0.5f, float emission = 0.0f,
        float film_thickness = 0.0f
    );
    int addTriangleMeshInstance(
        const float* transform, float r, float g, float b, float a, float ior,
        float roughness = 0.5f, float metallic = 0.0f, float specular = 0.5f, float emission = 0.0f,
        int textureIndex = -1, float film_thickness = 0.0f
    );
    int addCylinderInstance(
        float p0_x, float p0_y, float p0_z,
        float p1_x, float p1_y, float p1_z,
        float radius,
        float r, float g, float b, float a, float ior,
        float roughness = 0.5f, float metallic = 0.0f, float specular = 0.5f, float emission = 0.0f,
        float film_thickness = 0.0f
    );

    // Recursive-IAS Menger sponge (Sprint 18.4).
    // Wraps the most-recently-uploaded triangle mesh (call setTriangleMesh first with a
    // unit cube) in `level` nested IAS layers using the 20 Menger generator transforms.
    // VRAM grows O(level * 20) instead of O(20^level). The scaling factor halves the
    // sponge per recursion (each generator scales by 1/3 and translates by ±1/3 along
    // the Menger keep-positions). Constraint: do not deactivate any instance between
    // this call and render(); the leaf-IAS instances embed the predicted instanceId.
    int addRecursiveIASSpongeInstance(
        int level,
        const float* transform, float r, float g, float b, float a, float ior,
        float roughness = 0.5f, float metallic = 0.0f, float specular = 0.5f, float emission = 0.0f,
        int textureIndex = -1, float film_thickness = 0.0f
    );

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
    void buildTriangleMeshGAS(size_t mesh_index);
    void buildIAS();              // Build Instance Acceleration Structure for multi-object scenes

    // Build a sub-IAS owning `num_transforms` instances, all pointing to `child_handle`,
    // each tagged with `inherited_instance_id` (so the leaf hit shader resolves to the
    // outer recursive-sponge material slot). Caller retains lifetime ownership; the
    // returned handle is valid until clearAllInstances() / dispose().
    OptixTraversableHandle buildSubIAS(
        OptixTraversableHandle child_handle,
        const float (*transforms)[12],
        unsigned int num_transforms,
        unsigned int inherited_instance_id);
};

#endif // OPTIX_WRAPPER_H
