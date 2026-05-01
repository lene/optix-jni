#include "include/OptiXWrapper.h"
#include "include/SceneParameters.h"
#include "include/RenderConfig.h"
#include "include/PipelineManager.h"
#include "include/BufferManager.h"
#include "include/CausticsRenderer.h"
#include "include/OptiXContext.h"
#include "include/OptiXConstants.h"
#include "include/OptiXErrorChecking.h"
#include "include/VectorMath.h"
#include <iostream>
#include <cstring>
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cfloat>
#include <vector>
#include <map>
#include <optix.h>
#include <optix_function_table_definition.h>
#include <optix_stubs.h>
#include "Project4D.h"

/**
 * OptiXWrapper implementation using composition.
 * Coordinates SceneParameters, RenderConfig, PipelineManager, BufferManager, and CausticsRenderer.
 *
 * This refactored version reduces OptiXWrapper from 1040 lines to ~250 lines by delegating
 * responsibilities to focused component classes.
 */
struct OptiXWrapper::Impl {
    OptiXContext optix_context;
    SceneParameters scene;
    RenderConfig config;
    PipelineManager pipeline_manager;
    BufferManager buffer_manager;
    CausticsRenderer caustics_renderer;

    OptixTraversableHandle gas_handle = 0;
    bool pipeline_built = false;
    bool initialized = false;

    // Triangle mesh GPU state
    struct TriangleMeshGPU {
        CUdeviceptr d_vertices = 0;           // GPU vertex buffer
        CUdeviceptr d_indices = 0;            // GPU index buffer
        OptixTraversableHandle gas_handle = 0; // Triangle GAS
        CUdeviceptr d_gas_output_buffer = 0;  // GAS memory
        bool gas_built = false;               // True if GAS is ready
        unsigned int num_vertices = 0;
        unsigned int num_triangles = 0;
        unsigned int vertex_stride = 8;

        // GPU 4D projection sub-struct (Sprint 18.3). Populated only when
        // setTriangleMesh4DQuads uploaded this mesh. CPU-uploaded meshes
        // leave face_count == 0 and are unaffected by the 4D code paths.
        struct Projection4D {
            CUdeviceptr  d_quads_4d = 0;       // length 4*face_count, float4 per corner
            CUdeviceptr  d_uvs = 0;            // length 4*face_count float2, or 0
            unsigned int face_count = 0;       // 0 = not a 4D-projected mesh
            unsigned int verts_per_face = 4;   // Sprint 19 will set 0 for n-gon path
            Projection4DParams params{};       // current rotation+projection state
        } projection4d;
    };
    std::vector<TriangleMeshGPU> triangle_meshes;

    // Triangle mesh AABB (computed in setTriangleMesh for caustic target)
    float3 mesh_aabb_min = {0, 0, 0};
    float3 mesh_aabb_max = {0, 0, 0};

    // Instance Acceleration Structure (IAS) state for multi-object scenes
    struct ObjectInstance {
        GeometryType geometry_type;           // Sphere or Triangle mesh
        OptixTraversableHandle gas_handle;    // GAS for this geometry type
        float transform[12];                  // 4x3 row-major transform matrix
        float color[4];                       // RGBA material color
        float ior;                            // Index of refraction
        float roughness;                      // 0=mirror, 1=diffuse (default: 0.5)
        float metallic;                       // 0=dielectric, 1=metal (default: 0.0)
        float specular;                       // Specular intensity (default: 0.5)
        float emission;                       // Emission intensity (default: 0.0)
        float film_thickness;                 // Thin-film thickness in nm (0 = none)
        int texture_index;                    // Index into textures array (-1 = no texture)
        bool active;                          // True if instance is enabled
        size_t mesh_index;                    // Index into triangle_meshes (SIZE_MAX = not a triangle)
    };

    std::vector<ObjectInstance> instances;    // All object instances
    OptixTraversableHandle ias_handle = 0;    // Top-level IAS handle
    CUdeviceptr d_ias_output_buffer = 0;      // IAS memory
    CUdeviceptr d_instances_buffer = 0;       // OptixInstance array on GPU
    CUdeviceptr d_instance_materials = 0;     // InstanceMaterial array on GPU
    bool ias_dirty = false;                   // True if IAS needs rebuild
    bool use_ias = false;                     // True = multi-object mode
    unsigned int max_instances = 64;          // Configurable instance limit
    bool max_instances_warning_shown = false; // Suppress repeated warnings

    // GAS registry: geometry type -> GAS data
    // Allows sharing GAS between instances of the same type
    struct GASData {
        OptixTraversableHandle handle;
        CUdeviceptr gas_buffer;
        CUdeviceptr aabb_buffer;  // For custom primitives (0 for triangles)
    };
    std::map<GeometryType, GASData> gas_registry;

    // Texture management
    std::vector<TextureData> textures;
    std::map<std::string, int> texture_name_to_index;
    CUdeviceptr d_texture_objects = 0;        // Device array of cudaTextureObject_t for Params

    // Cylinder geometry data (host-side storage, uploaded to GPU before render)
    std::vector<CylinderData> cylinder_data;
    CUdeviceptr d_cylinder_data = 0;          // Device array of CylinderData for Params

    // Track cylinder GAS buffers for proper cleanup (each cylinder has its own GAS)
    std::vector<GASData> cylinder_gas_buffers;

    // Sub-IAS lifetime storage for recursive-IAS Menger sponges (Sprint 18.4).
    // Each entry owns one nested IAS layer's instance buffer + IAS output buffer.
    struct SubIASData {
        OptixTraversableHandle handle = 0;
        CUdeviceptr d_output_buffer = 0;
        CUdeviceptr d_instances_buffer = 0;
    };
    std::vector<SubIASData> sub_ias_buffers;

    Impl()
        : pipeline_manager(optix_context)
        , buffer_manager(optix_context)
        , caustics_renderer(optix_context, pipeline_manager, buffer_manager)
    {
    }
};

OptiXWrapper::OptiXWrapper() : impl(std::make_unique<Impl>()) {
}

OptiXWrapper::~OptiXWrapper() {
    dispose();
}

bool OptiXWrapper::initialize(unsigned int maxInstances) {
    try {
        // Store configurable limit
        impl->max_instances = maxInstances;
        impl->instances.reserve(maxInstances);

        bool success = impl->optix_context.initialize();
        if (!success) {
            std::cerr << "[OptiX] OptiXContext initialization failed" << std::endl;
            return false;
        }

        impl->initialized = true;
        return true;

    } catch (const std::exception& e) {
        std::cerr << "[OptiX] Initialization failed: " << e.what() << std::endl;
        return false;
    }
}

void OptiXWrapper::setSphere(float x, float y, float z, float radius) {
    impl->scene.setSphere(x, y, z, radius);
}

void OptiXWrapper::setSphereColor(float r, float g, float b, float a) {
    impl->scene.setSphereColor(r, g, b, a);
}

void OptiXWrapper::setIOR(float ior) {
    impl->scene.setIOR(ior);
}

void OptiXWrapper::setScale(float scale) {
    impl->scene.setScale(scale);
}

void OptiXWrapper::setCamera(const float* eye, const float* lookAt, const float* up, float fov) {
    impl->scene.setCamera(
        eye, lookAt, up, fov,
        impl->config.getImageWidth(),
        impl->config.getImageHeight()
    );
}

void OptiXWrapper::updateImageDimensions(int width, int height) {
    impl->config.setImageDimensions(width, height);
}

void OptiXWrapper::setLight(const float* direction, float intensity) {
    // Backward compatible: convert to single directional light
    Light light;
    light.type = LightType::DIRECTIONAL;

    float normalized_dir[3];
    std::memcpy(normalized_dir, direction, 3 * sizeof(float));
    VectorMath::normalize3f(normalized_dir);
    std::memcpy(light.direction, normalized_dir, 3 * sizeof(float));

    light.position[0] = 0.0f;
    light.position[1] = 0.0f;
    light.position[2] = 0.0f;

    light.color[0] = 1.0f;
    light.color[1] = 1.0f;
    light.color[2] = 1.0f;

    light.intensity = intensity;

    setLights(&light, 1);
}

void OptiXWrapper::setLights(const Light* lights, int count) {
    if (count < 0 || count > RayTracingConstants::MAX_LIGHTS) {
        throw std::invalid_argument(
            "Light count " + std::to_string(count) +
            " out of range [0, " + std::to_string(RayTracingConstants::MAX_LIGHTS) + "]"
        );
    }

    if (lights == nullptr && count > 0) {
        throw std::invalid_argument("Light array is null but count is " + std::to_string(count));
    }

    impl->scene.setLights(lights, count);
}

void OptiXWrapper::setShadows(bool enabled) {
    impl->config.setShadows(enabled);
}

void OptiXWrapper::setTransparentShadows(bool enabled) {
    impl->config.setTransparentShadows(enabled);
}

void OptiXWrapper::setBackgroundColor(float r, float g, float b) {
    impl->config.setBackgroundColor(r, g, b);
}

void OptiXWrapper::clearPlanes() {
    impl->config.clearPlanes();
}

void OptiXWrapper::addPlane(int axis, bool positive, float value) {
    impl->config.addPlane(axis, positive, value);
}

void OptiXWrapper::addPlaneSolidColor(int axis, bool positive, float value, float r, float g, float b) {
    impl->config.addPlaneSolidColor(axis, positive, value, r, g, b);
}

void OptiXWrapper::addPlaneCheckerColors(int axis, bool positive, float value,
                                         float r1, float g1, float b1,
                                         float r2, float g2, float b2) {
    impl->config.addPlaneCheckerColors(axis, positive, value, r1, g1, b1, r2, g2, b2);
}

void OptiXWrapper::addPlaneSolidColorWithMaterial(
    int axis, bool positive, float value,
    float r, float g, float b,
    float roughness, float metallic, float specular, float emission,
    int texture_index
) {
    impl->config.addPlaneSolidColorWithMaterial(
        axis, positive, value, r, g, b,
        roughness, metallic, specular, emission, texture_index);
}

void OptiXWrapper::addPlaneCheckerColorsWithMaterial(
    int axis, bool positive, float value,
    float r1, float g1, float b1, float r2, float g2, float b2,
    float roughness, float metallic, float specular, float emission,
    int texture_index
) {
    impl->config.addPlaneCheckerColorsWithMaterial(
        axis, positive, value, r1, g1, b1, r2, g2, b2,
        roughness, metallic, specular, emission, texture_index);
}

void OptiXWrapper::setAntialiasing(bool enabled, int maxDepth, float threshold) {
    impl->config.setAntialiasing(enabled, maxDepth, threshold);
}

void OptiXWrapper::setMaxRayDepth(int depth) {
    impl->config.setMaxRayDepth(depth);
}

void OptiXWrapper::setCaustics(bool enabled, int photonsPerIter, int iterations, float initialRadius, float alpha) {
    impl->config.setCaustics(enabled, photonsPerIter, iterations, initialRadius, alpha);
}

void OptiXWrapper::setTriangleMesh(
    const float* vertices,
    unsigned int num_vertices,
    const unsigned int* indices,
    unsigned int num_triangles,
    unsigned int vertex_stride
) {
    Impl::TriangleMeshGPU mesh_entry;

    // Allocate and copy vertex buffer
    size_t vertex_size =
        num_vertices * vertex_stride * sizeof(float);
    cudaMalloc(
        reinterpret_cast<void**>(&mesh_entry.d_vertices),
        vertex_size
    );
    cudaMemcpy(
        reinterpret_cast<void*>(mesh_entry.d_vertices),
        vertices, vertex_size, cudaMemcpyHostToDevice
    );

    // Allocate and copy index buffer (3 indices per triangle)
    size_t index_size =
        num_triangles * 3 * sizeof(unsigned int);
    cudaMalloc(
        reinterpret_cast<void**>(&mesh_entry.d_indices),
        index_size
    );
    cudaMemcpy(
        reinterpret_cast<void*>(mesh_entry.d_indices),
        indices, index_size, cudaMemcpyHostToDevice
    );

    // Store mesh metadata for GAS building
    mesh_entry.num_vertices = num_vertices;
    mesh_entry.num_triangles = num_triangles;
    mesh_entry.vertex_stride = vertex_stride;
    mesh_entry.gas_built = false;

    impl->triangle_meshes.push_back(mesh_entry);

    // Compute mesh AABB from vertex positions (for caustic target)
    impl->mesh_aabb_min = {FLT_MAX, FLT_MAX, FLT_MAX};
    impl->mesh_aabb_max = {-FLT_MAX, -FLT_MAX, -FLT_MAX};
    for (unsigned int i = 0; i < num_vertices; ++i) {
        const float* v = vertices + i * vertex_stride;
        impl->mesh_aabb_min.x =
            fminf(impl->mesh_aabb_min.x, v[0]);
        impl->mesh_aabb_min.y =
            fminf(impl->mesh_aabb_min.y, v[1]);
        impl->mesh_aabb_min.z =
            fminf(impl->mesh_aabb_min.z, v[2]);
        impl->mesh_aabb_max.x =
            fmaxf(impl->mesh_aabb_max.x, v[0]);
        impl->mesh_aabb_max.y =
            fmaxf(impl->mesh_aabb_max.y, v[1]);
        impl->mesh_aabb_max.z =
            fmaxf(impl->mesh_aabb_max.z, v[2]);
    }

    // Update scene parameters (latest mesh metadata)
    impl->scene.setTriangleMeshMeta(
        num_vertices, num_triangles
    );
    auto& mesh_params =
        impl->scene.getTriangleMeshMutable();
    mesh_params.d_vertices = mesh_entry.d_vertices;
    mesh_params.d_indices = mesh_entry.d_indices;
    mesh_params.vertex_stride = vertex_stride;
}

// Compose the 4D rotation matrix on the host as R_xw * R_yw * R_zw,
// matching Rotation.scala line 41. Result is row-major float[16].
namespace {

inline void rotation_plane(float out[16], int row, int col, float deg) {
    float rad = deg * 0.017453292519943295f;
    float c = std::cos(rad);
    float s = std::sin(rad);
    for (int i = 0; i < 16; ++i) out[i] = 0.0f;
    for (int i = 0; i < 4; ++i) out[i * 4 + i] = 1.0f;
    out[row * 4 + row] = c;
    out[row * 4 + col] = s;
    out[col * 4 + row] = -s;
    out[col * 4 + col] = c;
}

inline void mat4_mul(float out[16], const float a[16], const float b[16]) {
    float r[16];
    for (int i = 0; i < 4; ++i) {
        for (int j = 0; j < 4; ++j) {
            float s = 0.0f;
            for (int k = 0; k < 4; ++k) {
                s += a[i * 4 + k] * b[k * 4 + j];
            }
            r[i * 4 + j] = s;
        }
    }
    for (int i = 0; i < 16; ++i) out[i] = r[i];
}

inline void compose_rotation_xw_yw_zw(
    float out[16], float deg_xw, float deg_yw, float deg_zw
) {
    float rxw[16], ryw[16], rzw[16], tmp[16];
    rotation_plane(rxw, 0, 3, deg_xw);
    rotation_plane(ryw, 1, 3, deg_yw);
    rotation_plane(rzw, 2, 3, deg_zw);
    mat4_mul(tmp, ryw, rzw);
    mat4_mul(out, rxw, tmp);
}

}  // namespace

int OptiXWrapper::setTriangleMesh4DQuads(
    const float* quads4d,
    int num_quads,
    const float* uvs_or_null,
    float eyeW, float screenW,
    float rotXW_deg, float rotYW_deg, float rotZW_deg,
    float center_x, float center_y, float center_z
) {
    if (num_quads <= 0) {
        std::cerr
            << "[OptiX] setTriangleMesh4DQuads: num_quads must be > 0"
            << std::endl;
        return -1;
    }

    Impl::TriangleMeshGPU mesh_entry;
    cudaError_t err;

    // Allocate and upload 4D quad buffer (kept resident for Cut F).
    size_t quads_4d_bytes =
        static_cast<size_t>(num_quads) * 4 * 4 * sizeof(float);
    err = cudaMalloc(
        reinterpret_cast<void**>(&mesh_entry.projection4d.d_quads_4d),
        quads_4d_bytes
    );
    if (err != cudaSuccess) {
        std::cerr << "[OptiX] cudaMalloc failed for quads_4d: "
                  << cudaGetErrorString(err) << std::endl;
        return -1;
    }
    err = cudaMemcpy(
        reinterpret_cast<void*>(mesh_entry.projection4d.d_quads_4d),
        quads4d, quads_4d_bytes, cudaMemcpyHostToDevice
    );
    if (err != cudaSuccess) {
        std::cerr << "[OptiX] cudaMemcpy failed for quads_4d: "
                  << cudaGetErrorString(err) << std::endl;
        cudaFree(reinterpret_cast<void*>(mesh_entry.projection4d.d_quads_4d));
        return -1;
    }

    // Optional UV buffer.
    if (uvs_or_null != nullptr) {
        size_t uv_bytes =
            static_cast<size_t>(num_quads) * 4 * 2 * sizeof(float);
        err = cudaMalloc(
            reinterpret_cast<void**>(&mesh_entry.projection4d.d_uvs),
            uv_bytes
        );
        if (err != cudaSuccess) {
            std::cerr << "[OptiX] cudaMalloc failed for uvs: "
                      << cudaGetErrorString(err) << std::endl;
            cudaFree(reinterpret_cast<void*>(mesh_entry.projection4d.d_quads_4d));
            return -1;
        }
        err = cudaMemcpy(
            reinterpret_cast<void*>(mesh_entry.projection4d.d_uvs),
            uvs_or_null, uv_bytes, cudaMemcpyHostToDevice
        );
        if (err != cudaSuccess) {
            std::cerr << "[OptiX] cudaMemcpy failed for uvs: "
                      << cudaGetErrorString(err) << std::endl;
            cudaFree(reinterpret_cast<void*>(mesh_entry.projection4d.d_uvs));
            cudaFree(reinterpret_cast<void*>(mesh_entry.projection4d.d_quads_4d));
            return -1;
        }
    }

    // Allocate output 3D vertex + index buffers (these are what the GAS
    // consumes; setTriangleMesh's slot in triangle_meshes[]).
    unsigned int num_vertices =
        static_cast<unsigned int>(num_quads) * 4;
    unsigned int num_triangles =
        static_cast<unsigned int>(num_quads) * 2;
    unsigned int vertex_stride = 8;
    size_t vertex_bytes =
        static_cast<size_t>(num_vertices) * vertex_stride * sizeof(float);
    size_t index_bytes =
        static_cast<size_t>(num_triangles) * 3 * sizeof(unsigned int);
    err = cudaMalloc(
        reinterpret_cast<void**>(&mesh_entry.d_vertices), vertex_bytes
    );
    if (err != cudaSuccess) {
        std::cerr << "[OptiX] cudaMalloc failed for d_vertices: "
                  << cudaGetErrorString(err) << std::endl;
        if (mesh_entry.projection4d.d_uvs)
            cudaFree(reinterpret_cast<void*>(mesh_entry.projection4d.d_uvs));
        cudaFree(reinterpret_cast<void*>(mesh_entry.projection4d.d_quads_4d));
        return -1;
    }
    err = cudaMalloc(
        reinterpret_cast<void**>(&mesh_entry.d_indices), index_bytes
    );
    if (err != cudaSuccess) {
        std::cerr << "[OptiX] cudaMalloc failed for d_indices: "
                  << cudaGetErrorString(err) << std::endl;
        cudaFree(reinterpret_cast<void*>(mesh_entry.d_vertices));
        if (mesh_entry.projection4d.d_uvs)
            cudaFree(reinterpret_cast<void*>(mesh_entry.projection4d.d_uvs));
        cudaFree(reinterpret_cast<void*>(mesh_entry.projection4d.d_quads_4d));
        return -1;
    }

    // Populate the projection params and launch the kernel.
    mesh_entry.projection4d.face_count =
        static_cast<unsigned int>(num_quads);
    mesh_entry.projection4d.verts_per_face = 4;
    Projection4DParams& params = mesh_entry.projection4d.params;
    compose_rotation_xw_yw_zw(
        params.rotation, rotXW_deg, rotYW_deg, rotZW_deg
    );
    params.eye_w = eyeW;
    params.screen_w = screenW;
    params.center_x = center_x;
    params.center_y = center_y;
    params.center_z = center_z;

    err = launchProject4DQuadsKernel(
        reinterpret_cast<const void*>(mesh_entry.projection4d.d_quads_4d),
        mesh_entry.projection4d.d_uvs
            ? reinterpret_cast<const void*>(mesh_entry.projection4d.d_uvs)
            : nullptr,
        num_quads,
        &params,
        reinterpret_cast<void*>(mesh_entry.d_vertices),
        reinterpret_cast<void*>(mesh_entry.d_indices),
        /*stream=*/0
    );
    if (err != cudaSuccess) {
        std::cerr
            << "[OptiX] project4d kernel launch failed: "
            << cudaGetErrorString(err) << std::endl;
        cudaFree(reinterpret_cast<void*>(mesh_entry.d_vertices));
        cudaFree(reinterpret_cast<void*>(mesh_entry.d_indices));
        cudaFree(reinterpret_cast<void*>(mesh_entry.projection4d.d_quads_4d));
        if (mesh_entry.projection4d.d_uvs)
            cudaFree(reinterpret_cast<void*>(mesh_entry.projection4d.d_uvs));
        return -1;
    }
    cudaDeviceSynchronize();

    mesh_entry.num_vertices = num_vertices;
    mesh_entry.num_triangles = num_triangles;
    mesh_entry.vertex_stride = vertex_stride;
    mesh_entry.gas_built = false;

    // Compute mesh AABB by reading back projected vertices. One-time cost
    // at upload; Cut F will refresh on update if caustics are enabled.
    std::vector<float> projected(num_vertices * vertex_stride);
    cudaMemcpy(
        projected.data(),
        reinterpret_cast<void*>(mesh_entry.d_vertices),
        vertex_bytes, cudaMemcpyDeviceToHost
    );
    impl->mesh_aabb_min = {FLT_MAX, FLT_MAX, FLT_MAX};
    impl->mesh_aabb_max = {-FLT_MAX, -FLT_MAX, -FLT_MAX};
    for (unsigned int i = 0; i < num_vertices; ++i) {
        const float* v = projected.data() + i * vertex_stride;
        impl->mesh_aabb_min.x = fminf(impl->mesh_aabb_min.x, v[0]);
        impl->mesh_aabb_min.y = fminf(impl->mesh_aabb_min.y, v[1]);
        impl->mesh_aabb_min.z = fminf(impl->mesh_aabb_min.z, v[2]);
        impl->mesh_aabb_max.x = fmaxf(impl->mesh_aabb_max.x, v[0]);
        impl->mesh_aabb_max.y = fmaxf(impl->mesh_aabb_max.y, v[1]);
        impl->mesh_aabb_max.z = fmaxf(impl->mesh_aabb_max.z, v[2]);
    }

    int mesh_index = static_cast<int>(impl->triangle_meshes.size());
    impl->triangle_meshes.push_back(mesh_entry);

    impl->scene.setTriangleMeshMeta(num_vertices, num_triangles);
    auto& mesh_params = impl->scene.getTriangleMeshMutable();
    mesh_params.d_vertices = mesh_entry.d_vertices;
    mesh_params.d_indices = mesh_entry.d_indices;
    mesh_params.vertex_stride = vertex_stride;

    return mesh_index;
}

int OptiXWrapper::updateMesh4DProjection(
    int mesh_index,
    float eyeW, float screenW,
    float rotXW_deg, float rotYW_deg, float rotZW_deg,
    float center_x, float center_y, float center_z
) {
    if (mesh_index < 0
        || static_cast<size_t>(mesh_index) >= impl->triangle_meshes.size()) {
        std::cerr
            << "[OptiX] updateMesh4DProjection: mesh_index "
            << mesh_index << " out of range (have "
            << impl->triangle_meshes.size() << " meshes)" << std::endl;
        return -1;
    }
    auto& mesh = impl->triangle_meshes[mesh_index];
    if (mesh.projection4d.face_count == 0) {
        std::cerr
            << "[OptiX] updateMesh4DProjection: mesh_index "
            << mesh_index
            << " was not uploaded via setTriangleMesh4DQuads" << std::endl;
        return -2;
    }

    // Refresh stored projection params, then re-launch the kernel against the
    // resident 4D buffer + projected output buffer (same allocations as Cut A).
    Projection4DParams& params = mesh.projection4d.params;
    compose_rotation_xw_yw_zw(
        params.rotation, rotXW_deg, rotYW_deg, rotZW_deg
    );
    params.eye_w = eyeW;
    params.screen_w = screenW;
    params.center_x = center_x;
    params.center_y = center_y;
    params.center_z = center_z;

    cudaError_t err = launchProject4DQuadsKernel(
        reinterpret_cast<const void*>(mesh.projection4d.d_quads_4d),
        mesh.projection4d.d_uvs
            ? reinterpret_cast<const void*>(mesh.projection4d.d_uvs)
            : nullptr,
        static_cast<int>(mesh.projection4d.face_count),
        &params,
        reinterpret_cast<void*>(mesh.d_vertices),
        reinterpret_cast<void*>(mesh.d_indices),
        /*stream=*/0
    );
    if (err != cudaSuccess) {
        std::cerr
            << "[OptiX] project4d kernel relaunch failed: "
            << cudaGetErrorString(err) << std::endl;
        return -3;
    }
    cudaDeviceSynchronize();

    // GAS refit. Cut A flagged this mesh's GAS with ALLOW_UPDATE so we can
    // refit in place rather than rebuild.
    if (!mesh.gas_built || mesh.d_gas_output_buffer == 0) {
        // No GAS yet — fall back to full build (e.g. update called before
        // the first render).
        buildTriangleMeshGAS(static_cast<size_t>(mesh_index));
    } else {
        OptixBuildInput triangle_input = {};
        triangle_input.type = OPTIX_BUILD_INPUT_TYPE_TRIANGLES;
        triangle_input.triangleArray.vertexBuffers = &mesh.d_vertices;
        triangle_input.triangleArray.numVertices = mesh.num_vertices;
        triangle_input.triangleArray.vertexFormat =
            OPTIX_VERTEX_FORMAT_FLOAT3;
        triangle_input.triangleArray.vertexStrideInBytes =
            mesh.vertex_stride * sizeof(float);
        triangle_input.triangleArray.indexBuffer = mesh.d_indices;
        triangle_input.triangleArray.numIndexTriplets = mesh.num_triangles;
        triangle_input.triangleArray.indexFormat =
            OPTIX_INDICES_FORMAT_UNSIGNED_INT3;
        triangle_input.triangleArray.indexStrideInBytes =
            3 * sizeof(unsigned int);
        uint32_t triangle_flags[1] = {OPTIX_GEOMETRY_FLAG_NONE};
        triangle_input.triangleArray.flags = triangle_flags;
        triangle_input.triangleArray.numSbtRecords = 1;

        OptixAccelBuildOptions update_options = {};
        update_options.buildFlags =
            OPTIX_BUILD_FLAG_PREFER_FAST_TRACE
            | OPTIX_BUILD_FLAG_ALLOW_COMPACTION
            | OPTIX_BUILD_FLAG_ALLOW_UPDATE;
        update_options.operation = OPTIX_BUILD_OPERATION_UPDATE;

        OptixAccelBufferSizes gas_sizes;
        OPTIX_CHECK(optixAccelComputeMemoryUsage(
            impl->optix_context.getContext(),
            &update_options, &triangle_input, 1, &gas_sizes
        ));
        CUdeviceptr d_temp = 0;
        CUDA_CHECK(cudaMalloc(
            reinterpret_cast<void**>(&d_temp),
            gas_sizes.tempUpdateSizeInBytes
        ));
        OPTIX_CHECK(optixAccelBuild(
            impl->optix_context.getContext(),
            0,
            &update_options, &triangle_input, 1,
            d_temp, gas_sizes.tempUpdateSizeInBytes,
            mesh.d_gas_output_buffer,
            gas_sizes.outputSizeInBytes,
            &mesh.gas_handle,
            nullptr, 0
        ));
        CUDA_CHECK(cudaFree(reinterpret_cast<void*>(d_temp)));
    }

    // IAS refit. The IAS already had ALLOW_UPDATE in its build flags
    // (see buildIAS), and instance handles do not change since the mesh's
    // GAS handle is stable across UPDATE — only AABBs need to be
    // recomputed by OptiX.
    if (impl->use_ias && impl->ias_handle && impl->d_ias_output_buffer
        && impl->d_instances_buffer) {
        OptixBuildInput ias_input = {};
        ias_input.type = OPTIX_BUILD_INPUT_TYPE_INSTANCES;
        ias_input.instanceArray.instances = impl->d_instances_buffer;
        // Count active instances — must match the count used at build time.
        unsigned int active_count = 0;
        for (const auto& inst : impl->instances) {
            if (inst.active) ++active_count;
        }
        ias_input.instanceArray.numInstances = active_count;

        OptixAccelBuildOptions ias_update_opts = {};
        ias_update_opts.buildFlags =
            OPTIX_BUILD_FLAG_ALLOW_UPDATE
            | OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;
        ias_update_opts.operation = OPTIX_BUILD_OPERATION_UPDATE;

        OptixAccelBufferSizes ias_sizes;
        OPTIX_CHECK(optixAccelComputeMemoryUsage(
            impl->optix_context.getContext(),
            &ias_update_opts, &ias_input, 1, &ias_sizes
        ));
        CUdeviceptr d_ias_temp = 0;
        CUDA_CHECK(cudaMalloc(
            reinterpret_cast<void**>(&d_ias_temp),
            ias_sizes.tempUpdateSizeInBytes
        ));
        OPTIX_CHECK(optixAccelBuild(
            impl->optix_context.getContext(),
            0,
            &ias_update_opts, &ias_input, 1,
            d_ias_temp, ias_sizes.tempUpdateSizeInBytes,
            impl->d_ias_output_buffer,
            ias_sizes.outputSizeInBytes,
            &impl->ias_handle,
            nullptr, 0
        ));
        CUDA_CHECK(cudaFree(reinterpret_cast<void*>(d_ias_temp)));
        CUDA_CHECK(cudaDeviceSynchronize());
    }

    return 0;
}

int OptiXWrapper::updateCpuTriangleMesh(
    int mesh_index,
    const float* vertices,
    unsigned int num_vertices,
    const unsigned int* indices,
    unsigned int num_triangles,
    unsigned int vertex_stride
) {
    if (mesh_index < 0
        || static_cast<size_t>(mesh_index) >= impl->triangle_meshes.size()) {
        std::cerr
            << "[OptiX] updateCpuTriangleMesh: mesh_index "
            << mesh_index << " out of range (have "
            << impl->triangle_meshes.size() << " meshes)" << std::endl;
        return -1;
    }
    auto& mesh = impl->triangle_meshes[mesh_index];
    if (mesh.projection4d.face_count > 0) {
        std::cerr
            << "[OptiX] updateCpuTriangleMesh: mesh_index "
            << mesh_index
            << " is a GPU-projected 4D mesh; use updateMesh4DProjection instead"
            << std::endl;
        return -1;
    }

    // Free existing GPU vertex and index buffers, then re-upload.
    if (mesh.d_vertices) {
        cudaFree(reinterpret_cast<void*>(mesh.d_vertices));
        mesh.d_vertices = 0;
    }
    if (mesh.d_indices) {
        cudaFree(reinterpret_cast<void*>(mesh.d_indices));
        mesh.d_indices = 0;
    }

    size_t vertex_size = num_vertices * vertex_stride * sizeof(float);
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&mesh.d_vertices), vertex_size
    ));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(mesh.d_vertices),
        vertices, vertex_size, cudaMemcpyHostToDevice
    ));

    size_t index_size = num_triangles * 3 * sizeof(unsigned int);
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&mesh.d_indices), index_size
    ));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(mesh.d_indices),
        indices, index_size, cudaMemcpyHostToDevice
    ));

    mesh.num_vertices = num_vertices;
    mesh.num_triangles = num_triangles;
    mesh.vertex_stride = vertex_stride;
    mesh.gas_built = false;

    // Rebuild the GAS for this mesh slot only.
    buildTriangleMeshGAS(static_cast<size_t>(mesh_index));

    // Update the scene parameters so the IAS material pointer stays valid.
    impl->scene.getTriangleMeshMutable().d_vertices = mesh.d_vertices;
    impl->scene.getTriangleMeshMutable().d_indices  = mesh.d_indices;
    impl->scene.getTriangleMeshMutable().vertex_stride = vertex_stride;

    // Re-link the instance's gas_handle to the freshly built GAS.
    for (auto& inst : impl->instances) {
        if (inst.active
            && inst.geometry_type == GEOMETRY_TYPE_TRIANGLE
            && static_cast<int>(inst.mesh_index) == mesh_index) {
            inst.gas_handle = mesh.gas_handle;
        }
    }

    // IAS must be rebuilt on the next render() to pick up the new GAS handle.
    impl->ias_dirty = true;

    return 0;
}

void OptiXWrapper::setTriangleMeshColor(float r, float g, float b, float a) {
    impl->scene.setTriangleMeshColor(r, g, b, a);
}

void OptiXWrapper::setTriangleMeshIOR(float ior) {
    impl->scene.setTriangleMeshIOR(ior);
}

void OptiXWrapper::clearTriangleMesh() {
    // Free GPU buffers for all mesh entries
    for (auto& mesh : impl->triangle_meshes) {
        if (mesh.d_vertices) {
            cudaFree(
                reinterpret_cast<void*>(mesh.d_vertices)
            );
        }
        if (mesh.d_indices) {
            cudaFree(
                reinterpret_cast<void*>(mesh.d_indices)
            );
        }
        if (mesh.d_gas_output_buffer) {
            cudaFree(
                reinterpret_cast<void*>(
                    mesh.d_gas_output_buffer
                )
            );
        }
        // 4D-projection buffers (Sprint 18.3): kept resident for the mesh
        // lifetime to support Cut F's updateMesh4DProjection.
        if (mesh.projection4d.d_quads_4d) {
            cudaFree(
                reinterpret_cast<void*>(mesh.projection4d.d_quads_4d)
            );
        }
        if (mesh.projection4d.d_uvs) {
            cudaFree(
                reinterpret_cast<void*>(mesh.projection4d.d_uvs)
            );
        }
    }
    impl->triangle_meshes.clear();

    impl->scene.clearTriangleMesh();
}

bool OptiXWrapper::hasTriangleMesh() const {
    return impl->scene.hasTriangleMesh();
}

void OptiXWrapper::buildGeometryAccelerationStructure() {
    const auto& sphere = impl->scene.getSphere();

    // Create AABB (Axis-Aligned Bounding Box) for custom sphere primitive
    OptixAabb aabb;
    aabb.minX = sphere.center[0] - sphere.radius;
    aabb.minY = sphere.center[1] - sphere.radius;
    aabb.minZ = sphere.center[2] - sphere.radius;
    aabb.maxX = sphere.center[0] + sphere.radius;
    aabb.maxY = sphere.center[1] + sphere.radius;
    aabb.maxZ = sphere.center[2] + sphere.radius;

    // Configure acceleration structure build
    // PREFER_FAST_TRACE: Optimizes BVH for ray traversal speed (better for interactive rendering)
    // ALLOW_COMPACTION: Enables memory compaction for smaller footprint
    OptixAccelBuildOptions accel_options = {};
    accel_options.buildFlags = OPTIX_BUILD_FLAG_PREFER_FAST_TRACE | OPTIX_BUILD_FLAG_ALLOW_COMPACTION;
    accel_options.operation = OPTIX_BUILD_OPERATION_BUILD;

    // Use OptiXContext to build GAS
    OptiXContext::GASBuildResult result = impl->optix_context.buildCustomPrimitiveGAS(
        aabb,
        accel_options
    );

    impl->buffer_manager.ensureGASBuffer(result);
    impl->gas_handle = result.handle;
}

void OptiXWrapper::buildTriangleMeshGAS(size_t mesh_index) {
    if (mesh_index >= impl->triangle_meshes.size()) {
        std::cerr
            << "[OptiX] buildTriangleMeshGAS: invalid index "
            << mesh_index << " (have "
            << impl->triangle_meshes.size() << " meshes)"
            << std::endl;
        return;
    }

    auto& mesh = impl->triangle_meshes[mesh_index];

    // Free existing GAS if any
    if (mesh.d_gas_output_buffer) {
        cudaFree(
            reinterpret_cast<void*>(
                mesh.d_gas_output_buffer
            )
        );
        mesh.d_gas_output_buffer = 0;
    }

    if (mesh.num_triangles == 0) {
        mesh.gas_handle = 0;
        mesh.gas_built = false;
        return;
    }

    // Configure acceleration structure build
    // PREFER_FAST_TRACE: Optimizes BVH for ray traversal speed
    // ALLOW_COMPACTION: Enables memory compaction
    OptixAccelBuildOptions accel_options = {};
    accel_options.buildFlags =
        OPTIX_BUILD_FLAG_PREFER_FAST_TRACE
        | OPTIX_BUILD_FLAG_ALLOW_COMPACTION;
    // 4D-projected meshes (Sprint 18.3) need ALLOW_UPDATE so Cut F's
    // updateMesh4DProjection can refit the GAS via OPERATION_UPDATE
    // after re-launching the projection kernel into the same buffer.
    if (mesh.projection4d.face_count > 0) {
        accel_options.buildFlags |= OPTIX_BUILD_FLAG_ALLOW_UPDATE;
    }
    accel_options.operation = OPTIX_BUILD_OPERATION_BUILD;

    OptiXContext::GASBuildResult result =
        impl->optix_context.buildTriangleGAS(
            mesh.d_vertices,
            mesh.num_vertices,
            mesh.d_indices,
            mesh.num_triangles,
            accel_options,
            mesh.vertex_stride
        );

    mesh.d_gas_output_buffer = result.gas_buffer;
    mesh.gas_handle = result.handle;
    mesh.gas_built = true;
}

void OptiXWrapper::buildIAS() {
    // Skip if no instances
    if (impl->instances.empty()) {
        impl->use_ias = false;
        return;
    }

    // Free existing IAS buffers
    if (impl->d_ias_output_buffer) {
        cudaFree(reinterpret_cast<void*>(impl->d_ias_output_buffer));
        impl->d_ias_output_buffer = 0;
    }
    if (impl->d_instances_buffer) {
        cudaFree(reinterpret_cast<void*>(impl->d_instances_buffer));
        impl->d_instances_buffer = 0;
    }
    if (impl->d_instance_materials) {
        cudaFree(reinterpret_cast<void*>(impl->d_instance_materials));
        impl->d_instance_materials = 0;
    }

    // Build OptixInstance and InstanceMaterial arrays from active instances
    std::vector<OptixInstance> optix_instances;
    std::vector<InstanceMaterial> materials;

    for (size_t i = 0; i < impl->instances.size(); ++i) {
        const auto& inst = impl->instances[i];
        if (!inst.active) continue;

        OptixInstance oi = {};

        // Copy 4x3 row-major transform
        std::memcpy(oi.transform, inst.transform, 12 * sizeof(float));

        // Instance ID = index into materials array
        oi.instanceId = static_cast<unsigned int>(optix_instances.size());

        // SBT offset: 2 ray types (primary + shadow) per geometry type
        oi.sbtOffset = inst.geometry_type * SBTConstants::STRIDE_RAY_TYPES;

        oi.visibilityMask = 255;
        oi.flags = OPTIX_INSTANCE_FLAG_NONE;
        oi.traversableHandle = inst.gas_handle;

        optix_instances.push_back(oi);

        // Build material entry with PBR properties
        InstanceMaterial mat = {};
        std::memcpy(mat.color, inst.color, 4 * sizeof(float));
        mat.ior = inst.ior;
        mat.roughness = inst.roughness;
        mat.metallic = inst.metallic;
        mat.specular = inst.specular;
        mat.emission = inst.emission;
        mat.geometry_type = inst.geometry_type;
        mat.texture_index = inst.texture_index;
        mat.film_thickness = inst.film_thickness;
        // Per-mesh triangle buffer pointers for IAS mode
        if (inst.geometry_type == GEOMETRY_TYPE_TRIANGLE
            && inst.mesh_index < impl->triangle_meshes.size()) {
            const auto& mesh =
                impl->triangle_meshes[inst.mesh_index];
            mat.vertices =
                reinterpret_cast<float*>(mesh.d_vertices);
            mat.indices =
                reinterpret_cast<unsigned int*>(
                    mesh.d_indices);
            mat.vertex_stride = mesh.vertex_stride;
        } else {
            mat.vertices = nullptr;
            mat.indices = nullptr;
            mat.vertex_stride = 0;
        }
        materials.push_back(mat);
    }

    if (optix_instances.empty()) {
        impl->use_ias = false;
        return;
    }

    // Upload instances to GPU
    size_t instances_size = optix_instances.size() * sizeof(OptixInstance);
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_instances_buffer), instances_size));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(impl->d_instances_buffer),
        optix_instances.data(),
        instances_size,
        cudaMemcpyHostToDevice
    ));

    // Upload materials to GPU
    size_t materials_size = materials.size() * sizeof(InstanceMaterial);
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_instance_materials), materials_size));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(impl->d_instance_materials),
        materials.data(),
        materials_size,
        cudaMemcpyHostToDevice
    ));

    // Build IAS input
    OptixBuildInput ias_input = {};
    ias_input.type = OPTIX_BUILD_INPUT_TYPE_INSTANCES;
    ias_input.instanceArray.instances = impl->d_instances_buffer;
    ias_input.instanceArray.numInstances = static_cast<unsigned int>(optix_instances.size());

    OptixAccelBuildOptions accel_options = {};
    accel_options.buildFlags = OPTIX_BUILD_FLAG_ALLOW_UPDATE | OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;
    accel_options.operation = OPTIX_BUILD_OPERATION_BUILD;

    // Query memory requirements
    OptixAccelBufferSizes ias_buffer_sizes;
    OPTIX_CHECK(optixAccelComputeMemoryUsage(
        impl->optix_context.getContext(),
        &accel_options,
        &ias_input,
        1,
        &ias_buffer_sizes
    ));

    // Allocate temp and output buffers
    CUdeviceptr d_temp_buffer;
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_temp_buffer), ias_buffer_sizes.tempSizeInBytes));
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_ias_output_buffer), ias_buffer_sizes.outputSizeInBytes));

    // Build IAS
    OPTIX_CHECK(optixAccelBuild(
        impl->optix_context.getContext(),
        0,  // CUDA stream
        &accel_options,
        &ias_input,
        1,
        d_temp_buffer,
        ias_buffer_sizes.tempSizeInBytes,
        impl->d_ias_output_buffer,
        ias_buffer_sizes.outputSizeInBytes,
        &impl->ias_handle,
        nullptr,
        0
    ));

    // Free temp buffer
    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(d_temp_buffer)));

    // Synchronize to ensure IAS build is complete before rendering
    CUDA_CHECK(cudaDeviceSynchronize());

    impl->use_ias = true;
    impl->ias_dirty = false;
}

void OptiXWrapper::buildPipeline() {
    // In IAS mode, skip single-object GAS building - instances have their own GAS handles
    // SBT still needs scene data for default values (used when building unified SBT)
    if (!impl->use_ias) {
        // Single-object mode: build GAS for whichever geometry type is active
        if (impl->scene.hasTriangleMesh()
            && !impl->triangle_meshes.empty()) {
            size_t idx =
                impl->triangle_meshes.size() - 1;
            buildTriangleMeshGAS(idx);
            impl->gas_handle =
                impl->triangle_meshes[idx].gas_handle;
        } else {
            buildGeometryAccelerationStructure();
        }
    } else {
        // For IAS mode, we use the IAS handle (set later in render())
        impl->gas_handle = 0;  // Not used in IAS mode
    }
    impl->pipeline_manager.buildPipeline(impl->scene, impl->gas_handle);
}

void OptiXWrapper::render(int width, int height, unsigned char* output, RayStats* stats) {
    if (!impl->initialized) {
        throw std::runtime_error("[OptiX] render() called before initialize()");
    }

    try {
        // Build pipeline on first render or when geometry changes (expensive)
        if (!impl->pipeline_built || impl->scene.isGeometryDirty()) {
            buildPipeline();
            impl->pipeline_built = true;
            impl->scene.clearDirtyFlags();  // Clear all flags after full rebuild
        }
        // Camera-only change: lightweight SBT update (no pipeline rebuild)
        else if (impl->scene.isCameraDirty()) {
            impl->pipeline_manager.updateCameraInSBT(impl->scene);
            impl->scene.clearCameraDirty();
        }

        // Rebuild IAS if in IAS mode and dirty
        if (impl->use_ias && impl->ias_dirty) {
            buildIAS();
        }

        // Ensure buffers are allocated
        impl->buffer_manager.ensureImageBuffer(width, height);
        impl->buffer_manager.ensureParamsBuffer();
        impl->buffer_manager.ensureStatsBuffer();

        // Allocate caustics buffers if enabled
        if (impl->config.getCausticsEnabled()) {
            impl->buffer_manager.ensureCausticsBuffers();
        }

        // Zero out stats buffer before render
        impl->buffer_manager.zeroStatsBuffer();

        // Zero out caustics buffers if enabled
        if (impl->config.getCausticsEnabled()) {
            impl->buffer_manager.zeroCausticsBuffers();
        }

        // Set up launch parameters
        Params params;
        params.image = reinterpret_cast<unsigned char*>(impl->buffer_manager.getImageBuffer());
        params.image_width = width;
        params.image_height = height;

        // Use IAS handle in multi-object mode, GAS handle in single-object mode
        if (impl->use_ias) {
            if (impl->ias_handle == 0) {
                throw std::runtime_error("[OptiX] IAS handle is null but use_ias is true");
            }
            if (!impl->d_instance_materials) {
                throw std::runtime_error("[OptiX] Instance materials not uploaded to GPU");
            }

            params.handle = impl->ias_handle;
            params.use_ias = true;
            params.sbt_base_offset = 0;  // IAS instances carry their own sbtOffset
            params.instance_materials = reinterpret_cast<InstanceMaterial*>(impl->d_instance_materials);
            params.num_instances = getInstanceCount();

            // Upload texture objects array for IAS mode
            if (!impl->textures.empty()) {
                // Build array of texture objects
                std::vector<cudaTextureObject_t> tex_objs;
                tex_objs.reserve(impl->textures.size());
                for (const auto& tex : impl->textures) {
                    tex_objs.push_back(tex.texture_obj);
                }

                // Reallocate GPU buffer if size changed
                size_t tex_size = tex_objs.size() * sizeof(cudaTextureObject_t);
                if (impl->d_texture_objects) {
                    cudaFree(reinterpret_cast<void*>(impl->d_texture_objects));
                }
                CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_texture_objects), tex_size));
                CUDA_CHECK(cudaMemcpy(
                    reinterpret_cast<void*>(impl->d_texture_objects),
                    tex_objs.data(),
                    tex_size,
                    cudaMemcpyHostToDevice
                ));

                params.textures = reinterpret_cast<cudaTextureObject_t*>(impl->d_texture_objects);
                params.num_textures = static_cast<unsigned int>(tex_objs.size());
            } else {
                params.textures = nullptr;
                params.num_textures = 0;
            }

            // Upload cylinder data if any cylinders exist
            if (!impl->cylinder_data.empty()) {
                size_t cyl_size = impl->cylinder_data.size() * sizeof(CylinderData);

                // Reallocate GPU buffer if needed
                if (impl->d_cylinder_data) {
                    cudaFree(reinterpret_cast<void*>(impl->d_cylinder_data));
                }
                CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_cylinder_data), cyl_size));
                CUDA_CHECK(cudaMemcpy(
                    reinterpret_cast<void*>(impl->d_cylinder_data),
                    impl->cylinder_data.data(),
                    cyl_size,
                    cudaMemcpyHostToDevice
                ));

                params.cylinder_data = reinterpret_cast<CylinderData*>(impl->d_cylinder_data);
                params.num_cylinders = static_cast<unsigned int>(impl->cylinder_data.size());
            } else {
                params.cylinder_data = nullptr;
                params.num_cylinders = 0;
            }
        } else {
            params.handle = impl->gas_handle;
            params.use_ias = false;
            // Route SBT to correct geometry type's hitgroup records
            params.sbt_base_offset = impl->scene.hasTriangleMesh()
                ? GEOMETRY_TYPE_TRIANGLE * SBTConstants::STRIDE_RAY_TYPES
                : GEOMETRY_TYPE_SPHERE * SBTConstants::STRIDE_RAY_TYPES;
            params.instance_materials = nullptr;
            params.num_instances = 0;
            params.textures = nullptr;
            params.num_textures = 0;
            params.cylinder_data = nullptr;
            params.num_cylinders = 0;
        }

        // Dynamic scene data
        const auto& sphere = impl->scene.getSphere();
        std::memcpy(params.sphere_color, sphere.color, sizeof(float) * 4);
        params.sphere_ior = sphere.ior;
        params.sphere_scale = sphere.scale;

        // Multi-object scenes store IOR per-instance, not in legacy sphere params.
        // Override sphere_ior from first sphere instance so caustics shader gets correct IOR.
        for (const auto& inst : impl->instances) {
            if (inst.geometry_type == GEOMETRY_TYPE_SPHERE) {
                params.sphere_ior = inst.ior;
                break;
            }
        }

        // Copy lights array
        params.num_lights = impl->scene.getNumLights();
        for (int i = 0; i < impl->scene.getNumLights(); ++i) {
            params.lights[i] = impl->scene.getLights()[i];
        }

        // Rendering configuration
        params.shadows_enabled = impl->config.getShadowsEnabled();
        params.transparent_shadows_enabled = impl->config.getTransparentShadowsEnabled();
        params.bg_r = impl->config.getBackgroundR();
        params.bg_g = impl->config.getBackgroundG();
        params.bg_b = impl->config.getBackgroundB();

        params.num_planes = impl->config.getNumPlanes();
        // Copy all MAX_PLANES slots (zero-initialized past num_planes) to avoid
        // conditional logic; shader gates on num_planes.
        std::memcpy(params.planes, impl->config.getPlanes(), sizeof(PlaneParams) * RayTracingConstants::MAX_PLANES);

        // Adaptive antialiasing parameters
        params.aa_enabled = impl->config.getAAEnabled();
        params.aa_max_depth = impl->config.getAAMaxDepth();
        params.aa_threshold = impl->config.getAAThreshold();

        // Ray depth
        params.max_ray_depth = impl->config.getMaxRayDepth();

        // Caustics (Progressive Photon Mapping) parameters
        params.caustics.enabled = impl->config.getCausticsEnabled();
        params.caustics.photons_per_iteration = impl->config.getCausticsPhotonsPerIter();
        params.caustics.iterations = impl->config.getCausticsIterations();
        params.caustics.initial_radius = impl->config.getCausticsInitialRadius();
        params.caustics.alpha = impl->config.getCausticsAlpha();
        params.caustics.current_iteration = 0;
        // Compute caustic target from AABB of all refractive instances
        {
            float3 cmin = {FLT_MAX, FLT_MAX, FLT_MAX};
            float3 cmax = {-FLT_MAX, -FLT_MAX, -FLT_MAX};
            bool found = false;

            for (const auto& inst : impl->instances) {
                if (!inst.active || inst.ior <= 1.05f) continue;
                found = true;

                if (inst.geometry_type == GEOMETRY_TYPE_SPHERE) {
                    const float cx = inst.transform[3];
                    const float cy = inst.transform[7];
                    const float cz = inst.transform[11];
                    const float s = sqrtf(inst.transform[0]*inst.transform[0] +
                                          inst.transform[1]*inst.transform[1] +
                                          inst.transform[2]*inst.transform[2]);
                    const float r = sphere.radius * s;
                    cmin.x = fminf(cmin.x, cx - r); cmin.y = fminf(cmin.y, cy - r); cmin.z = fminf(cmin.z, cz - r);
                    cmax.x = fmaxf(cmax.x, cx + r); cmax.y = fmaxf(cmax.y, cy + r); cmax.z = fmaxf(cmax.z, cz + r);
                } else if (inst.geometry_type == GEOMETRY_TYPE_TRIANGLE) {
                    const float3 lo = impl->mesh_aabb_min;
                    const float3 hi = impl->mesh_aabb_max;
                    const float corners[8][3] = {
                        {lo.x,lo.y,lo.z},{hi.x,lo.y,lo.z},{lo.x,hi.y,lo.z},{hi.x,hi.y,lo.z},
                        {lo.x,lo.y,hi.z},{hi.x,lo.y,hi.z},{lo.x,hi.y,hi.z},{hi.x,hi.y,hi.z}
                    };
                    for (int c = 0; c < 8; ++c) {
                        const float wx = inst.transform[0]*corners[c][0] + inst.transform[1]*corners[c][1] + inst.transform[2]*corners[c][2] + inst.transform[3];
                        const float wy = inst.transform[4]*corners[c][0] + inst.transform[5]*corners[c][1] + inst.transform[6]*corners[c][2] + inst.transform[7];
                        const float wz = inst.transform[8]*corners[c][0] + inst.transform[9]*corners[c][1] + inst.transform[10]*corners[c][2] + inst.transform[11];
                        cmin.x = fminf(cmin.x, wx); cmin.y = fminf(cmin.y, wy); cmin.z = fminf(cmin.z, wz);
                        cmax.x = fmaxf(cmax.x, wx); cmax.y = fmaxf(cmax.y, wy); cmax.z = fmaxf(cmax.z, wz);
                    }
                }
                // Cylinder: fall through (not a typical caustic material)
            }

            if (found) {
                params.caustics.caustic_target_center[0] = (cmin.x + cmax.x) * 0.5f;
                params.caustics.caustic_target_center[1] = (cmin.y + cmax.y) * 0.5f;
                params.caustics.caustic_target_center[2] = (cmin.z + cmax.z) * 0.5f;
                const float dx = cmax.x - cmin.x;
                const float dy = cmax.y - cmin.y;
                const float dz = cmax.z - cmin.z;
                params.caustics.caustic_target_radius = 0.5f * sqrtf(dx*dx + dy*dy + dz*dz);
            } else {
                // Fallback: use scene sphere (backward compat with non-IAS mode)
                std::memcpy(params.caustics.caustic_target_center, sphere.center, sizeof(float) * 3);
                params.caustics.caustic_target_radius = sphere.radius;
            }
        }
        params.caustics.hit_points = reinterpret_cast<HitPoint*>(impl->buffer_manager.getHitPointsBuffer());
        params.caustics.num_hit_points = reinterpret_cast<unsigned int*>(impl->buffer_manager.getNumHitPointsBuffer());
        params.caustics.grid = reinterpret_cast<unsigned int*>(impl->buffer_manager.getCausticsGridBuffer());
        params.caustics.grid_counts = reinterpret_cast<unsigned int*>(impl->buffer_manager.getCausticsGridCountsBuffer());
        params.caustics.grid_offsets = reinterpret_cast<unsigned int*>(impl->buffer_manager.getCausticsGridOffsetsBuffer());

        // Spatial grid: fixed bounds covering canonical scene geometry
        params.caustics.grid_min[0] = -3.0f;
        params.caustics.grid_min[1] = -3.0f;
        params.caustics.grid_min[2] = -3.0f;
        params.caustics.grid_max[0] = 3.0f;
        params.caustics.grid_max[1] = 3.0f;
        params.caustics.grid_max[2] = 3.0f;
        // Cell size = initial radius so 3x3x3 neighborhood covers gather radius
        params.caustics.cell_size = params.caustics.initial_radius;
        const float grid_extent = params.caustics.grid_max[0] - params.caustics.grid_min[0];
        const unsigned int computed_res = static_cast<unsigned int>(
            std::ceil(grid_extent / params.caustics.cell_size)
        );
        params.caustics.grid_resolution = std::min(
            computed_res,
            static_cast<unsigned int>(RayTracingConstants::CAUSTICS_GRID_RESOLUTION)
        );
        params.caustics.total_photons_traced = 0;
        params.caustics.stats = reinterpret_cast<CausticsStats*>(impl->buffer_manager.getCausticsStatsBuffer());

        params.stats = reinterpret_cast<RayStats*>(impl->buffer_manager.getStatsBuffer());

        // Upload params to GPU
        impl->buffer_manager.uploadParams(params);

        // Launch OptiX rendering
        if (impl->config.getCausticsEnabled()) {
            // Multi-pass Progressive Photon Mapping rendering
            impl->caustics_renderer.renderWithCaustics(width, height, impl->config, impl->scene, params);
        } else {
            // Standard single-pass rendering
            impl->optix_context.launch(
                impl->pipeline_manager.getPipeline(),
                impl->pipeline_manager.getSBT(),
                impl->buffer_manager.getParamsBuffer(),
                width,
                height
            );
        }

        // Synchronize to flush printf output
        CUDA_CHECK(cudaDeviceSynchronize());

        // Download results
        impl->buffer_manager.downloadImage(output, width, height);

        // Copy stats back if requested
        if (stats) {
            impl->buffer_manager.downloadStats(stats);
        }

    } catch (const std::exception& e) {
        std::cerr << "[OptiX] Render failed: " << e.what() << std::endl;
        // Fill with error color (red)
        for (int i = 0; i < width * height; i++) {
            output[i * 4 + 0] = 255;  // R
            output[i * 4 + 1] = 0;    // G
            output[i * 4 + 2] = 0;    // B
            output[i * 4 + 3] = 255;  // A
        }
    }
}

bool OptiXWrapper::getCausticsStats(CausticsStats* stats) {
    if (!impl->initialized || !impl->config.getCausticsEnabled() || !stats) {
        return false;
    }

    // Return the cached stats from the last render
    *stats = impl->caustics_renderer.getLastStats();
    return true;
}

// Multi-object instance management (IAS mode)

int OptiXWrapper::addSphereInstance(
    const float* transform, float r, float g, float b, float a, float ior,
    float roughness, float metallic, float specular, float emission,
    float film_thickness
) {
    if (impl->instances.size() >= impl->max_instances) {
        if (!impl->max_instances_warning_shown) {
            std::cerr << "[OptiX][Sphere] Maximum instances (" << impl->max_instances << ") reached" << std::endl;
            impl->max_instances_warning_shown = true;
        }
        return -1;
    }

    // Ensure sphere GAS exists in registry
    // For IAS mode, we need a unit sphere at origin - transform matrix handles position/scale
    if (impl->gas_registry.find(GEOMETRY_TYPE_SPHERE) == impl->gas_registry.end()) {
        // Build unit sphere GAS at origin for instancing
        OptixAabb aabb;
        aabb.minX = -1.0f;
        aabb.minY = -1.0f;
        aabb.minZ = -1.0f;
        aabb.maxX = 1.0f;
        aabb.maxY = 1.0f;
        aabb.maxZ = 1.0f;

        OptixAccelBuildOptions accel_options = {};
        accel_options.buildFlags = OPTIX_BUILD_FLAG_ALLOW_COMPACTION;
        accel_options.operation = OPTIX_BUILD_OPERATION_BUILD;

        OptiXContext::GASBuildResult result = impl->optix_context.buildCustomPrimitiveGAS(
            aabb,
            accel_options
        );

        // NOTE: Do NOT call ensureGASBuffer() here! That would store the buffer in BufferManager,
        // which gets freed when buildPipeline() builds a new GAS. IAS sphere GAS is managed
        // separately in gas_registry and freed in dispose() and clearAllInstances().
        Impl::GASData gas_data;
        gas_data.handle = result.handle;
        gas_data.gas_buffer = result.gas_buffer;
        gas_data.aabb_buffer = result.aabb_buffer;
        impl->gas_registry[GEOMETRY_TYPE_SPHERE] = gas_data;
    }

    Impl::ObjectInstance inst;
    inst.geometry_type = GEOMETRY_TYPE_SPHERE;
    inst.gas_handle = impl->gas_registry[GEOMETRY_TYPE_SPHERE].handle;
    std::memcpy(inst.transform, transform, 12 * sizeof(float));
    inst.color[0] = r;
    inst.color[1] = g;
    inst.color[2] = b;
    inst.color[3] = a;
    inst.ior = ior;
    inst.roughness = roughness;
    inst.metallic = metallic;
    inst.specular = specular;
    inst.emission = emission;
    inst.film_thickness = film_thickness;
    inst.texture_index = -1;  // Spheres don't support textures
    inst.active = true;
    inst.mesh_index = SIZE_MAX;  // Not a triangle mesh instance

    int instanceId = static_cast<int>(impl->instances.size());
    impl->instances.push_back(inst);
    impl->ias_dirty = true;

    // Force pipeline rebuild when entering IAS mode for first time
    // Pipeline SBT needs to include both sphere and triangle hit programs for IAS
    if (!impl->use_ias) {
        impl->pipeline_built = false;
    }
    impl->use_ias = true;

    return instanceId;
}

int OptiXWrapper::addTriangleMeshInstance(
    const float* transform, float r, float g, float b, float a, float ior,
    float roughness, float metallic, float specular, float emission, int textureIndex,
    float film_thickness
) {
    if (impl->instances.size() >= impl->max_instances) {
        if (!impl->max_instances_warning_shown) {
            std::cerr
                << "[OptiX][TriangleMesh] Maximum instances ("
                << impl->max_instances << ") reached"
                << std::endl;
            impl->max_instances_warning_shown = true;
        }
        return -1;
    }

    if (impl->triangle_meshes.empty()) {
        std::cerr
            << "[OptiX] Cannot add triangle mesh instance:"
            << " no mesh set"
            << " (call setTriangleMesh first)" << std::endl;
        return -1;
    }

    // Use the latest mesh entry
    size_t mesh_index =
        impl->triangle_meshes.size() - 1;
    auto& mesh = impl->triangle_meshes[mesh_index];

    // Auto-build triangle GAS if not built yet
    if (!mesh.gas_built) {
        buildTriangleMeshGAS(mesh_index);
    }

    Impl::ObjectInstance inst;
    inst.geometry_type = GEOMETRY_TYPE_TRIANGLE;
    inst.gas_handle = mesh.gas_handle;
    std::memcpy(
        inst.transform, transform, 12 * sizeof(float)
    );
    inst.color[0] = r;
    inst.color[1] = g;
    inst.color[2] = b;
    inst.color[3] = a;
    inst.ior = ior;
    inst.roughness = roughness;
    inst.metallic = metallic;
    inst.specular = specular;
    inst.emission = emission;
    inst.film_thickness = film_thickness;
    inst.texture_index = textureIndex;
    inst.active = true;
    inst.mesh_index = mesh_index;

    int instanceId =
        static_cast<int>(impl->instances.size());
    impl->instances.push_back(inst);
    impl->ias_dirty = true;

    if (!impl->use_ias) {
        impl->pipeline_built = false;
    }
    impl->use_ias = true;

    return instanceId;
}

// ============================================================================
// Recursive-IAS Menger sponge (Sprint 18.4)
// ============================================================================

namespace {

// 20 Menger generator transforms relative to a unit-extent parent.
// Each scales by 1/3 and translates to one of the 20 sub-cube keep-positions
// (the cells where |xx|+|yy|+|zz| > 1 in the 3x3x3 subdivision).
// Layout: 20 instances of a 4x3 row-major matrix (3 rows of 4 floats).
const float (*getMengerGenerators())[12] {
    static float generators[20][12];
    static bool initialized = false;
    if (initialized) return generators;
    constexpr float s = 1.0f / 3.0f;
    int idx = 0;
    for (int xx = -1; xx <= 1; ++xx) {
        for (int yy = -1; yy <= 1; ++yy) {
            for (int zz = -1; zz <= 1; ++zz) {
                if (std::abs(xx) + std::abs(yy) + std::abs(zz) <= 1) continue;
                float* t = generators[idx];
                t[0]  = s; t[1]  = 0; t[2]  = 0; t[3]  = xx * s;
                t[4]  = 0; t[5]  = s; t[6]  = 0; t[7]  = yy * s;
                t[8]  = 0; t[9]  = 0; t[10] = s; t[11] = zz * s;
                ++idx;
            }
        }
    }
    initialized = true;
    return generators;
}

constexpr int MENGER_GENERATOR_COUNT = 20;
constexpr int MAX_RECURSIVE_IAS_LEVEL = 14;  // matches MAX_TRAVERSABLE_GRAPH_DEPTH = 16

}  // namespace

OptixTraversableHandle OptiXWrapper::buildSubIAS(
    OptixTraversableHandle child_handle,
    const float (*transforms)[12],
    unsigned int num_transforms,
    unsigned int inherited_instance_id) {

    if (child_handle == 0 || num_transforms == 0) {
        std::cerr << "[OptiX] buildSubIAS: invalid input (child=" << child_handle
                  << ", num=" << num_transforms << ")" << std::endl;
        return 0;
    }

    // Build host-side OptixInstance array. All sub-IAS instances share the same
    // child handle and the same inherited instanceId so the leaf hit shader
    // resolves to the outer recursive-sponge material slot. sbtOffset is 0
    // because the outer (top-level main-IAS) instance already carries the
    // geometry-type offset; sbtOffsets accumulate along the traversal path.
    std::vector<OptixInstance> instances(num_transforms);
    for (unsigned int i = 0; i < num_transforms; ++i) {
        OptixInstance& oi = instances[i];
        std::memset(&oi, 0, sizeof(OptixInstance));
        std::memcpy(oi.transform, transforms[i], 12 * sizeof(float));
        oi.instanceId = inherited_instance_id;
        oi.sbtOffset = 0;
        oi.visibilityMask = 255;
        oi.flags = OPTIX_INSTANCE_FLAG_NONE;
        oi.traversableHandle = child_handle;
    }

    Impl::SubIASData sub;

    size_t inst_size = num_transforms * sizeof(OptixInstance);
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&sub.d_instances_buffer), inst_size));
    CUDA_CHECK(cudaMemcpy(reinterpret_cast<void*>(sub.d_instances_buffer),
                          instances.data(), inst_size, cudaMemcpyHostToDevice));

    OptixBuildInput build_input = {};
    build_input.type = OPTIX_BUILD_INPUT_TYPE_INSTANCES;
    build_input.instanceArray.instances = sub.d_instances_buffer;
    build_input.instanceArray.numInstances = num_transforms;

    OptixAccelBuildOptions accel_options = {};
    accel_options.buildFlags = OPTIX_BUILD_FLAG_PREFER_FAST_TRACE;
    accel_options.operation = OPTIX_BUILD_OPERATION_BUILD;

    OptixAccelBufferSizes sizes;
    OPTIX_CHECK(optixAccelComputeMemoryUsage(
        impl->optix_context.getContext(), &accel_options, &build_input, 1, &sizes));

    CUdeviceptr d_temp = 0;
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_temp), sizes.tempSizeInBytes));
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&sub.d_output_buffer), sizes.outputSizeInBytes));

    OPTIX_CHECK(optixAccelBuild(
        impl->optix_context.getContext(), 0,
        &accel_options, &build_input, 1,
        d_temp, sizes.tempSizeInBytes,
        sub.d_output_buffer, sizes.outputSizeInBytes,
        &sub.handle, nullptr, 0));

    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(d_temp)));
    CUDA_CHECK(cudaDeviceSynchronize());

    impl->sub_ias_buffers.push_back(sub);
    return sub.handle;
}

int OptiXWrapper::addRecursiveIASSpongeInstance(
    int level,
    const float* transform, float r, float g, float b, float a, float ior,
    float roughness, float metallic, float specular, float emission,
    int textureIndex, float film_thickness) {

    if (level < 1) {
        std::cerr << "[OptiX][RecSponge] level must be >= 1 (got " << level << ")" << std::endl;
        return -1;
    }
    if (level > MAX_RECURSIVE_IAS_LEVEL) {
        std::cerr << "[OptiX][RecSponge] level " << level << " exceeds max "
                  << MAX_RECURSIVE_IAS_LEVEL << " (constrained by MAX_TRAVERSABLE_GRAPH_DEPTH)"
                  << std::endl;
        return -1;
    }
    if (impl->instances.size() >= impl->max_instances) {
        std::cerr << "[OptiX][RecSponge] Maximum instances ("
                  << impl->max_instances << ") reached" << std::endl;
        return -1;
    }
    if (impl->triangle_meshes.empty()) {
        std::cerr << "[OptiX][RecSponge] No leaf mesh — call setTriangleMesh(unit cube) first"
                  << std::endl;
        return -1;
    }

    // Predict the instanceId that buildIAS will assign to this entry. The compaction
    // step in buildIAS skips inactive instances, so the predicted id equals the count
    // of currently-active instances. This is correct only if no instance is
    // deactivated between this call and render() — documented in the header.
    unsigned int predicted_instance_id = 0;
    for (const auto& other : impl->instances) {
        if (other.active) ++predicted_instance_id;
    }

    // Use the most-recent triangle mesh as the leaf cube. Caller is responsible
    // for uploading a unit cube via setTriangleMesh before this call.
    size_t mesh_index = impl->triangle_meshes.size() - 1;
    auto& mesh = impl->triangle_meshes[mesh_index];
    if (!mesh.gas_built) {
        buildTriangleMeshGAS(mesh_index);
    }
    if (mesh.gas_handle == 0) {
        std::cerr << "[OptiX][RecSponge] Leaf mesh GAS build failed" << std::endl;
        return -1;
    }

    // Chain `level` sub-IASes around the leaf GAS.
    OptixTraversableHandle handle = mesh.gas_handle;
    const float (*generators)[12] = getMengerGenerators();
    for (int i = 0; i < level; ++i) {
        handle = buildSubIAS(handle, generators, MENGER_GENERATOR_COUNT, predicted_instance_id);
        if (handle == 0) {
            std::cerr << "[OptiX][RecSponge] buildSubIAS failed at recursion " << (i + 1)
                      << std::endl;
            return -1;
        }
    }

    Impl::ObjectInstance inst;
    inst.geometry_type = GEOMETRY_TYPE_TRIANGLE;
    inst.gas_handle = handle;
    std::memcpy(inst.transform, transform, 12 * sizeof(float));
    inst.color[0] = r; inst.color[1] = g; inst.color[2] = b; inst.color[3] = a;
    inst.ior = ior;
    inst.roughness = roughness;
    inst.metallic = metallic;
    inst.specular = specular;
    inst.emission = emission;
    inst.film_thickness = film_thickness;
    inst.texture_index = textureIndex;
    inst.active = true;
    inst.mesh_index = mesh_index;

    int instanceId = static_cast<int>(impl->instances.size());
    impl->instances.push_back(inst);
    impl->ias_dirty = true;

    if (!impl->use_ias) {
        impl->pipeline_built = false;
    }
    impl->use_ias = true;

    return instanceId;
}

int OptiXWrapper::addCylinderInstance(
    float p0_x, float p0_y, float p0_z,
    float p1_x, float p1_y, float p1_z,
    float radius,
    float r, float g, float b, float a, float ior,
    float roughness, float metallic, float specular, float emission,
    float film_thickness
) {
    if (impl->instances.size() >= impl->max_instances) {
        if (!impl->max_instances_warning_shown) {
            std::cerr << "[OptiX][Cylinder] Maximum instances (" << impl->max_instances << ") reached" << std::endl;
            impl->max_instances_warning_shown = true;
        }
        return -1;
    }

    // Each cylinder has unique geometry, so create a unique GAS
    // Calculate AABB that bounds the cylinder
    float min_x = fminf(p0_x, p1_x) - radius;
    float min_y = fminf(p0_y, p1_y) - radius;
    float min_z = fminf(p0_z, p1_z) - radius;
    float max_x = fmaxf(p0_x, p1_x) + radius;
    float max_y = fmaxf(p0_y, p1_y) + radius;
    float max_z = fmaxf(p0_z, p1_z) + radius;

    OptixAabb aabb;
    aabb.minX = min_x;
    aabb.minY = min_y;
    aabb.minZ = min_z;
    aabb.maxX = max_x;
    aabb.maxY = max_y;
    aabb.maxZ = max_z;

    // Validate AABB
    if (!std::isfinite(aabb.minX) || !std::isfinite(aabb.minY) || !std::isfinite(aabb.minZ) ||
        !std::isfinite(aabb.maxX) || !std::isfinite(aabb.maxY) || !std::isfinite(aabb.maxZ) ||
        aabb.minX > aabb.maxX || aabb.minY > aabb.maxY || aabb.minZ > aabb.maxZ) {
        std::cerr << "[OptiX][Cylinder] Invalid AABB: "
                  << "min=(" << aabb.minX << "," << aabb.minY << "," << aabb.minZ << "), "
                  << "max=(" << aabb.maxX << "," << aabb.maxY << "," << aabb.maxZ << ")" << std::endl;
        return -1;
    }

    OptixAccelBuildOptions accel_options = {};
    accel_options.buildFlags = OPTIX_BUILD_FLAG_ALLOW_COMPACTION;
    accel_options.operation = OPTIX_BUILD_OPERATION_BUILD;

    OptiXContext::GASBuildResult result = impl->optix_context.buildCustomPrimitiveGAS(
        aabb,
        accel_options
    );

    // Validate cylinder parameters
    if (!std::isfinite(p0_x) || !std::isfinite(p0_y) || !std::isfinite(p0_z) ||
        !std::isfinite(p1_x) || !std::isfinite(p1_y) || !std::isfinite(p1_z) ||
        !std::isfinite(radius) || radius <= 0.0f) {
        std::cerr << "[OptiX][Cylinder] Invalid cylinder parameters: "
                  << "p0=(" << p0_x << "," << p0_y << "," << p0_z << "), "
                  << "p1=(" << p1_x << "," << p1_y << "," << p1_z << "), "
                  << "radius=" << radius << std::endl;
        return -1;
    }

    // Store CylinderData in host-side vector (will be uploaded to GPU before render)
    CylinderData cyl_data;
    cyl_data.p0[0] = p0_x;
    cyl_data.p0[1] = p0_y;
    cyl_data.p0[2] = p0_z;
    cyl_data.radius = radius;
    cyl_data.p1[0] = p1_x;
    cyl_data.p1[1] = p1_y;
    cyl_data.p1[2] = p1_z;
    cyl_data.padding = 0.0f;

    int cylinder_index = static_cast<int>(impl->cylinder_data.size());
    impl->cylinder_data.push_back(cyl_data);

    // Store GAS data in tracking vector for proper cleanup
    Impl::GASData gas_data;
    gas_data.handle = result.handle;
    gas_data.gas_buffer = result.gas_buffer;
    gas_data.aabb_buffer = result.aabb_buffer;  // Must free the AABB buffer allocated by buildCustomPrimitiveGAS
    impl->cylinder_gas_buffers.push_back(gas_data);

    // Create instance
    Impl::ObjectInstance inst;
    inst.geometry_type = GEOMETRY_TYPE_CYLINDER;
    inst.gas_handle = gas_data.handle;

    // Cylinders use identity transform since geometry is already positioned via p0/p1
    float identity_transform[12] = {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f
    };
    std::memcpy(inst.transform, identity_transform, 12 * sizeof(float));

    inst.color[0] = r;
    inst.color[1] = g;
    inst.color[2] = b;
    inst.color[3] = a;
    inst.ior = ior;
    inst.roughness = roughness;
    inst.metallic = metallic;
    inst.specular = specular;
    inst.emission = emission;
    inst.film_thickness = film_thickness;
    inst.texture_index = cylinder_index;  // For cylinders: index into cylinder_data array
    inst.active = true;
    inst.mesh_index = SIZE_MAX;  // Not a triangle mesh instance

    int instanceId = static_cast<int>(impl->instances.size());
    impl->instances.push_back(inst);

    // Store GAS data with unique key (use negative instance ID to distinguish from shared GAS)
    impl->gas_registry[static_cast<GeometryType>(-(instanceId + 1))] = gas_data;

    impl->ias_dirty = true;

    // Force pipeline rebuild when entering IAS mode for first time
    if (!impl->use_ias) {
        impl->pipeline_built = false;
    }
    impl->use_ias = true;

    return instanceId;
}

void OptiXWrapper::removeInstance(int instanceId) {
    if (instanceId < 0 || instanceId >= static_cast<int>(impl->instances.size())) {
        std::cerr << "[OptiX] Invalid instance ID: " << instanceId << std::endl;
        return;
    }

    impl->instances[instanceId].active = false;
    impl->ias_dirty = true;
}

void OptiXWrapper::clearAllInstances() {
    impl->instances.clear();
    impl->ias_dirty = true;
    impl->use_ias = false;
    impl->max_instances_warning_shown = false;

    // Free IAS buffers
    if (impl->d_ias_output_buffer) {
        cudaFree(reinterpret_cast<void*>(impl->d_ias_output_buffer));
        impl->d_ias_output_buffer = 0;
    }
    if (impl->d_instances_buffer) {
        cudaFree(reinterpret_cast<void*>(impl->d_instances_buffer));
        impl->d_instances_buffer = 0;
    }
    if (impl->d_instance_materials) {
        cudaFree(reinterpret_cast<void*>(impl->d_instance_materials));
        impl->d_instance_materials = 0;
    }
    impl->ias_handle = 0;

    // Clear cylinder data
    impl->cylinder_data.clear();
    if (impl->d_cylinder_data) {
        cudaFree(reinterpret_cast<void*>(impl->d_cylinder_data));
        impl->d_cylinder_data = 0;
    }

    // CRITICAL: Synchronize CUDA before freeing GAS buffers
    // The IAS may still have pending GPU operations referencing these buffers
    cudaError_t sync_err = cudaDeviceSynchronize();
    if (sync_err != cudaSuccess) {
        std::cerr << "[OptiXWrapper::clearAllInstances] CUDA sync error before GAS cleanup: " << cudaGetErrorString(sync_err) << std::endl;
    }

    // Free cylinder GAS buffers
    for (const auto& gas : impl->cylinder_gas_buffers) {
        if (gas.gas_buffer) {
            cudaFree(reinterpret_cast<void*>(gas.gas_buffer));
        }
        if (gas.aabb_buffer) {
            cudaFree(reinterpret_cast<void*>(gas.aabb_buffer));
        }
    }
    impl->cylinder_gas_buffers.clear();

    // Free recursive-IAS sponge sub-IAS buffers (Sprint 18.4)
    for (const auto& sub : impl->sub_ias_buffers) {
        if (sub.d_output_buffer) {
            cudaFree(reinterpret_cast<void*>(sub.d_output_buffer));
        }
        if (sub.d_instances_buffer) {
            cudaFree(reinterpret_cast<void*>(sub.d_instances_buffer));
        }
    }
    impl->sub_ias_buffers.clear();

    // Free triangle mesh GPU resources
    for (auto& mesh : impl->triangle_meshes) {
        if (mesh.d_vertices) {
            cudaFree(
                reinterpret_cast<void*>(mesh.d_vertices)
            );
        }
        if (mesh.d_indices) {
            cudaFree(
                reinterpret_cast<void*>(mesh.d_indices)
            );
        }
        if (mesh.d_gas_output_buffer) {
            cudaFree(
                reinterpret_cast<void*>(
                    mesh.d_gas_output_buffer
                )
            );
        }
    }
    impl->triangle_meshes.clear();

    // CRITICAL: Clear gas_registry to remove stale GAS handles
    // The registry maps geometry types to GAS data, and after freeing the GAS buffers above,
    // these handles are invalid. Not clearing this causes illegal memory access on rebuild.
    impl->gas_registry.clear();

    // Check CUDA error state after freeing
    cudaError_t err = cudaGetLastError();
    if (err != cudaSuccess) {
        std::cerr << "[OptiXWrapper::clearAllInstances] CUDA error after cleanup: " << cudaGetErrorString(err) << std::endl;
    }
}

int OptiXWrapper::getInstanceCount() const {
    int count = 0;
    for (const auto& inst : impl->instances) {
        if (inst.active) ++count;
    }
    return count;
}

bool OptiXWrapper::isIASMode() const {
    return impl->use_ias;
}

void OptiXWrapper::setIASMode(bool enabled) {
    if (enabled != impl->use_ias) {
        impl->use_ias = enabled;
        impl->ias_dirty = enabled;  // Need to rebuild IAS if enabling
        impl->pipeline_built = false;  // Need to rebuild pipeline
    }
}

int OptiXWrapper::uploadTexture(
    const char* name,
    const unsigned char* image_data,
    unsigned int width,
    unsigned int height
) {
    // Check if texture already exists
    auto it = impl->texture_name_to_index.find(name);
    if (it != impl->texture_name_to_index.end()) {
        return it->second;  // Return existing index
    }

    if (impl->textures.size() >= MAX_TEXTURES) {
        std::cerr << "[OptiX] Maximum textures (" << MAX_TEXTURES << ") reached" << std::endl;
        return -1;
    }

    try {
        // Create CUDA array for texture storage
        cudaChannelFormatDesc channel_desc = cudaCreateChannelDesc<uchar4>();
        cudaArray_t cuda_array;
        CUDA_CHECK(cudaMallocArray(&cuda_array, &channel_desc, width, height));

        // Copy image data to CUDA array
        CUDA_CHECK(cudaMemcpy2DToArray(
            cuda_array, 0, 0,
            image_data,
            width * 4,  // Source pitch (bytes per row)
            width * 4,  // Width in bytes
            height,
            cudaMemcpyHostToDevice
        ));

        // Create texture object with linear filtering and wrap addressing
        cudaResourceDesc res_desc = {};
        res_desc.resType = cudaResourceTypeArray;
        res_desc.res.array.array = cuda_array;

        cudaTextureDesc tex_desc = {};
        tex_desc.addressMode[0] = cudaAddressModeWrap;
        tex_desc.addressMode[1] = cudaAddressModeWrap;
        tex_desc.filterMode = cudaFilterModeLinear;
        tex_desc.readMode = cudaReadModeNormalizedFloat;
        tex_desc.normalizedCoords = 1;

        cudaTextureObject_t texture_obj;
        CUDA_CHECK(cudaCreateTextureObject(&texture_obj, &res_desc, &tex_desc, nullptr));

        // Store texture data
        int index = static_cast<int>(impl->textures.size());
        impl->textures.push_back({cuda_array, texture_obj, width, height});
        impl->texture_name_to_index[name] = index;

        return index;

    } catch (const std::exception& e) {
        std::cerr << "[OptiX] Texture upload failed: " << e.what() << std::endl;
        return -1;
    }
}

void OptiXWrapper::releaseTextures() {
    for (auto& tex : impl->textures) {
        if (tex.texture_obj) {
            cudaDestroyTextureObject(tex.texture_obj);
        }
        if (tex.cuda_array) {
            cudaFreeArray(tex.cuda_array);
        }
    }
    impl->textures.clear();
    impl->texture_name_to_index.clear();

    // Free device texture objects array
    if (impl->d_texture_objects) {
        cudaFree(reinterpret_cast<void*>(impl->d_texture_objects));
        impl->d_texture_objects = 0;
    }
}

void OptiXWrapper::dispose() {
    if (impl->initialized) {
        try {
            // Release textures
            releaseTextures();

            // Clean up IAS resources
            clearAllInstances();

            // Free GAS buffers in registry before clearing
            for (auto& entry : impl->gas_registry) {
                if (entry.second.gas_buffer) {
                    cudaFree(reinterpret_cast<void*>(entry.second.gas_buffer));
                }
                if (entry.second.aabb_buffer) {
                    cudaFree(reinterpret_cast<void*>(entry.second.aabb_buffer));
                }
            }
            impl->gas_registry.clear();

            // Clean up pipeline resources (including caustics)
            impl->pipeline_manager.cleanup(true);

            // BufferManager automatically cleans up buffers via RAII
            // Just need to reset state
            impl->pipeline_built = false;

            // Clean up OptiX context
            impl->optix_context.destroy();

            // Synchronize CUDA device to clear any pending errors
            cudaError_t err = cudaDeviceSynchronize();
            if (err != cudaSuccess) {
                std::cerr << "[OptiX] CUDA synchronization warning during dispose: "
                          << cudaGetErrorString(err) << std::endl;
                // Clear the error
                cudaGetLastError();
            }

        } catch (const std::exception& e) {
            std::cerr << "[OptiX] Cleanup error: " << e.what() << std::endl;
        }
        impl->initialized = false;
    }
}
