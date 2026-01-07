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
#include <vector>
#include <map>
#include <optix.h>
#include <optix_function_table_definition.h>
#include <optix_stubs.h>

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
    } triangle_mesh_gpu;

    // Instance Acceleration Structure (IAS) state for multi-object scenes
    struct ObjectInstance {
        GeometryType geometry_type;           // Sphere or Triangle mesh
        OptixTraversableHandle gas_handle;    // GAS for this geometry type
        float transform[12];                  // 4x3 row-major transform matrix
        float color[4];                       // RGBA material color
        float ior;                            // Index of refraction
        int texture_index;                    // Index into textures array (-1 = no texture)
        bool active;                          // True if instance is enabled
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

void OptiXWrapper::setPlaneSolidColor(float r, float g, float b) {
    impl->config.setPlaneSolidColor(r, g, b);
}

void OptiXWrapper::setPlaneCheckerColors(float r1, float g1, float b1, float r2, float g2, float b2) {
    impl->config.setPlaneCheckerColors(r1, g1, b1, r2, g2, b2);
}

void OptiXWrapper::setAntialiasing(bool enabled, int maxDepth, float threshold) {
    impl->config.setAntialiasing(enabled, maxDepth, threshold);
}

void OptiXWrapper::setCaustics(bool enabled, int photonsPerIter, int iterations, float initialRadius, float alpha) {
    impl->config.setCaustics(enabled, photonsPerIter, iterations, initialRadius, alpha);
}

void OptiXWrapper::setPlane(int axis, bool positive, float value) {
    impl->scene.setPlane(axis, positive, value);
}

void OptiXWrapper::setTriangleMesh(
    const float* vertices,
    unsigned int num_vertices,
    const unsigned int* indices,
    unsigned int num_triangles,
    unsigned int vertex_stride
) {
    // Free existing GPU buffers if any
    if (impl->triangle_mesh_gpu.d_vertices) {
        cudaFree(reinterpret_cast<void*>(impl->triangle_mesh_gpu.d_vertices));
        impl->triangle_mesh_gpu.d_vertices = 0;
    }
    if (impl->triangle_mesh_gpu.d_indices) {
        cudaFree(reinterpret_cast<void*>(impl->triangle_mesh_gpu.d_indices));
        impl->triangle_mesh_gpu.d_indices = 0;
    }

    // Allocate and copy vertex buffer (vertex_stride floats per vertex)
    size_t vertex_size = num_vertices * vertex_stride * sizeof(float);
    cudaMalloc(reinterpret_cast<void**>(&impl->triangle_mesh_gpu.d_vertices), vertex_size);
    cudaMemcpy(
        reinterpret_cast<void*>(impl->triangle_mesh_gpu.d_vertices),
        vertices,
        vertex_size,
        cudaMemcpyHostToDevice
    );

    // Allocate and copy index buffer (3 indices per triangle)
    size_t index_size = num_triangles * 3 * sizeof(unsigned int);
    cudaMalloc(reinterpret_cast<void**>(&impl->triangle_mesh_gpu.d_indices), index_size);
    cudaMemcpy(
        reinterpret_cast<void*>(impl->triangle_mesh_gpu.d_indices),
        indices,
        index_size,
        cudaMemcpyHostToDevice
    );

    // Update scene parameters
    impl->scene.setTriangleMeshMeta(num_vertices, num_triangles);
    // Store device pointers and stride in scene for SBT setup
    auto& mesh_params = impl->scene.getTriangleMeshMutable();
    mesh_params.d_vertices = impl->triangle_mesh_gpu.d_vertices;
    mesh_params.d_indices = impl->triangle_mesh_gpu.d_indices;
    mesh_params.vertex_stride = vertex_stride;
    impl->triangle_mesh_gpu.gas_built = false;  // Need to rebuild GAS
}

void OptiXWrapper::setTriangleMeshColor(float r, float g, float b, float a) {
    impl->scene.setTriangleMeshColor(r, g, b, a);
}

void OptiXWrapper::setTriangleMeshIOR(float ior) {
    impl->scene.setTriangleMeshIOR(ior);
}

void OptiXWrapper::clearTriangleMesh() {
    // Free GPU buffers
    if (impl->triangle_mesh_gpu.d_vertices) {
        cudaFree(reinterpret_cast<void*>(impl->triangle_mesh_gpu.d_vertices));
        impl->triangle_mesh_gpu.d_vertices = 0;
    }
    if (impl->triangle_mesh_gpu.d_indices) {
        cudaFree(reinterpret_cast<void*>(impl->triangle_mesh_gpu.d_indices));
        impl->triangle_mesh_gpu.d_indices = 0;
    }
    if (impl->triangle_mesh_gpu.d_gas_output_buffer) {
        cudaFree(reinterpret_cast<void*>(impl->triangle_mesh_gpu.d_gas_output_buffer));
        impl->triangle_mesh_gpu.d_gas_output_buffer = 0;
    }

    impl->triangle_mesh_gpu.gas_handle = 0;
    impl->triangle_mesh_gpu.gas_built = false;

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

void OptiXWrapper::buildTriangleMeshGAS() {
    // Free existing GAS if any
    if (impl->triangle_mesh_gpu.d_gas_output_buffer) {
        cudaFree(reinterpret_cast<void*>(impl->triangle_mesh_gpu.d_gas_output_buffer));
        impl->triangle_mesh_gpu.d_gas_output_buffer = 0;
    }

    if (!impl->scene.hasTriangleMesh()) {
        impl->triangle_mesh_gpu.gas_handle = 0;
        impl->triangle_mesh_gpu.gas_built = false;
        return;
    }

    const auto& mesh_params = impl->scene.getTriangleMesh();

    // Configure acceleration structure build
    // PREFER_FAST_TRACE: Optimizes BVH for ray traversal speed (critical for complex sponge meshes)
    // ALLOW_COMPACTION: Enables memory compaction for smaller footprint
    OptixAccelBuildOptions accel_options = {};
    accel_options.buildFlags = OPTIX_BUILD_FLAG_PREFER_FAST_TRACE | OPTIX_BUILD_FLAG_ALLOW_COMPACTION;
    accel_options.operation = OPTIX_BUILD_OPERATION_BUILD;

    // Use OptiXContext to build triangle GAS
    OptiXContext::GASBuildResult result = impl->optix_context.buildTriangleGAS(
        impl->triangle_mesh_gpu.d_vertices,
        mesh_params.num_vertices,
        impl->triangle_mesh_gpu.d_indices,
        mesh_params.num_triangles,
        accel_options
    );

    impl->triangle_mesh_gpu.d_gas_output_buffer = result.gas_buffer;
    impl->triangle_mesh_gpu.gas_handle = result.handle;
    impl->triangle_mesh_gpu.gas_built = true;
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
        oi.sbtOffset = inst.geometry_type * 2;

        oi.visibilityMask = 255;
        oi.flags = OPTIX_INSTANCE_FLAG_NONE;
        oi.traversableHandle = inst.gas_handle;

        optix_instances.push_back(oi);

        // Build material entry
        InstanceMaterial mat = {};
        std::memcpy(mat.color, inst.color, 4 * sizeof(float));
        mat.ior = inst.ior;
        mat.geometry_type = inst.geometry_type;
        mat.texture_index = inst.texture_index;
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

    impl->use_ias = true;
    impl->ias_dirty = false;
}

void OptiXWrapper::buildPipeline() {
    // In IAS mode, skip single-object GAS building - instances have their own GAS handles
    // SBT still needs scene data for default values (used when building unified SBT)
    if (!impl->use_ias) {
        // Single-object mode: build GAS for whichever geometry type is active
        if (impl->scene.hasTriangleMesh()) {
            buildTriangleMeshGAS();
            impl->gas_handle = impl->triangle_mesh_gpu.gas_handle;
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
        // Build OptiX pipeline on first render call or when params change
        if (!impl->pipeline_built || impl->scene.isAnyDirty()) {
            buildPipeline();
            impl->pipeline_built = true;
            impl->scene.clearDirtyFlags();
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
            params.handle = impl->ias_handle;
            params.use_ias = true;
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
        } else {
            params.handle = impl->gas_handle;
            params.use_ias = false;
            params.instance_materials = nullptr;
            params.num_instances = 0;
            params.textures = nullptr;
            params.num_textures = 0;
        }

        // Dynamic scene data
        const auto& sphere = impl->scene.getSphere();
        std::memcpy(params.sphere_color, sphere.color, sizeof(float) * 4);
        params.sphere_ior = sphere.ior;
        params.sphere_scale = sphere.scale;

        // Copy lights array
        params.num_lights = impl->scene.getNumLights();
        for (int i = 0; i < impl->scene.getNumLights(); ++i) {
            params.lights[i] = impl->scene.getLights()[i];
        }

        // Rendering configuration
        params.shadows_enabled = impl->config.getShadowsEnabled();

        const auto& plane = impl->scene.getPlane();
        params.plane_axis = plane.axis;
        params.plane_positive = plane.positive;
        params.plane_value = plane.value;
        params.plane_solid_color = impl->config.isPlaneSolidColor();
        for (int i = 0; i < 3; ++i) {
            params.plane_color1[i] = impl->config.getPlaneColor1()[i];
            params.plane_color2[i] = impl->config.getPlaneColor2()[i];
        }

        // Adaptive antialiasing parameters
        params.aa_enabled = impl->config.getAAEnabled();
        params.aa_max_depth = impl->config.getAAMaxDepth();
        params.aa_threshold = impl->config.getAAThreshold();

        // Caustics (Progressive Photon Mapping) parameters
        params.caustics.enabled = impl->config.getCausticsEnabled();
        params.caustics.photons_per_iteration = impl->config.getCausticsPhotonsPerIter();
        params.caustics.iterations = impl->config.getCausticsIterations();
        params.caustics.initial_radius = impl->config.getCausticsInitialRadius();
        params.caustics.alpha = impl->config.getCausticsAlpha();
        params.caustics.current_iteration = 0;
        std::memcpy(params.caustics.sphere_center, sphere.center, sizeof(float) * 3);
        params.caustics.sphere_radius = sphere.radius;
        params.caustics.hit_points = reinterpret_cast<HitPoint*>(impl->buffer_manager.getHitPointsBuffer());
        params.caustics.num_hit_points = reinterpret_cast<unsigned int*>(impl->buffer_manager.getNumHitPointsBuffer());
        params.caustics.grid = reinterpret_cast<unsigned int*>(impl->buffer_manager.getCausticsGridBuffer());
        params.caustics.grid_counts = reinterpret_cast<unsigned int*>(impl->buffer_manager.getCausticsGridCountsBuffer());
        params.caustics.grid_offsets = reinterpret_cast<unsigned int*>(impl->buffer_manager.getCausticsGridOffsetsBuffer());
        params.caustics.grid_resolution = RayTracingConstants::CAUSTICS_GRID_RESOLUTION;
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

int OptiXWrapper::addSphereInstance(const float* transform, float r, float g, float b, float a, float ior) {
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
    inst.texture_index = -1;  // Spheres don't support textures
    inst.active = true;

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
    const float* transform, float r, float g, float b, float a, float ior, int textureIndex
) {
    if (impl->instances.size() >= impl->max_instances) {
        if (!impl->max_instances_warning_shown) {
            std::cerr << "[OptiX][TriangleMesh] Maximum instances (" << impl->max_instances << ") reached" << std::endl;
            impl->max_instances_warning_shown = true;
        }
        return -1;
    }

    // Check if mesh data exists
    if (!impl->scene.hasTriangleMesh()) {
        std::cerr << "[OptiX] Cannot add triangle mesh instance: no mesh set (call setTriangleMesh first)" << std::endl;
        return -1;
    }

    // Auto-build triangle GAS if mesh data exists but GAS isn't built yet
    if (!impl->triangle_mesh_gpu.gas_built) {
        buildTriangleMeshGAS();
    }

    // Register triangle GAS if not already in registry
    if (impl->gas_registry.find(GEOMETRY_TYPE_TRIANGLE) == impl->gas_registry.end()) {
        Impl::GASData gas_data;
        gas_data.handle = impl->triangle_mesh_gpu.gas_handle;
        gas_data.gas_buffer = impl->triangle_mesh_gpu.d_gas_output_buffer;
        gas_data.aabb_buffer = 0;  // Triangle meshes don't use custom AABBs
        impl->gas_registry[GEOMETRY_TYPE_TRIANGLE] = gas_data;
    }

    Impl::ObjectInstance inst;
    inst.geometry_type = GEOMETRY_TYPE_TRIANGLE;
    inst.gas_handle = impl->gas_registry[GEOMETRY_TYPE_TRIANGLE].handle;
    std::memcpy(inst.transform, transform, 12 * sizeof(float));
    inst.color[0] = r;
    inst.color[1] = g;
    inst.color[2] = b;
    inst.color[3] = a;
    inst.ior = ior;
    inst.texture_index = textureIndex;
    inst.active = true;

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

        } catch (const std::exception& e) {
            std::cerr << "[OptiX] Cleanup error: " << e.what() << std::endl;
        }
        impl->initialized = false;
    }
}
