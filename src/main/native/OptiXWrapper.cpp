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

bool OptiXWrapper::initialize() {
    try {
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
    unsigned int num_triangles
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

    // Allocate and copy vertex buffer (6 floats per vertex: pos + normal)
    size_t vertex_size = num_vertices * 6 * sizeof(float);
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
    // Store device pointers in scene for SBT setup
    auto& mesh_params = impl->scene.getTriangleMeshMutable();
    mesh_params.d_vertices = impl->triangle_mesh_gpu.d_vertices;
    mesh_params.d_indices = impl->triangle_mesh_gpu.d_indices;
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
    OptixAccelBuildOptions accel_options = {};
    accel_options.buildFlags = OPTIX_BUILD_FLAG_ALLOW_COMPACTION;
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
    OptixAccelBuildOptions accel_options = {};
    accel_options.buildFlags = OPTIX_BUILD_FLAG_ALLOW_COMPACTION;
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

void OptiXWrapper::buildPipeline() {
    // Build GAS for whichever geometry type is active
    if (impl->scene.hasTriangleMesh()) {
        buildTriangleMeshGAS();
        impl->gas_handle = impl->triangle_mesh_gpu.gas_handle;
    } else {
        buildGeometryAccelerationStructure();
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
        params.handle = impl->gas_handle;

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

void OptiXWrapper::dispose() {
    if (impl->initialized) {
        try {
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
