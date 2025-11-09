#include "include/OptiXWrapper.h"
#include "include/OptiXContext.h"
#include "include/OptiXConstants.h"
#include "include/OptiXErrorChecking.h"
#include <iostream>
#include <cstring>
#include <sstream>
#include <cmath>
#include <fstream>
#include <vector>
#include <cerrno>
#include <unistd.h>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// Vector math helper functions
namespace VectorMath {
    // Normalize a 3D vector in place
    inline void normalize3f(float v[3]) {
        const float len = std::sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
        v[0] /= len;
        v[1] /= len;
        v[2] /= len;
    }

    // Cross product: result = a × b
    inline void cross3f(float result[3], const float a[3], const float b[3]) {
        result[0] = a[1]*b[2] - a[2]*b[1];
        result[1] = a[2]*b[0] - a[0]*b[2];
        result[2] = a[0]*b[1] - a[1]*b[0];
    }
}
#include <cuda_runtime.h>
#include <optix_function_table_definition.h>
#include <optix_stubs.h>
#include <optix_stack_size.h>

// OptiX log callback
static void optixLogCallback(unsigned int level, const char* tag, const char* message, void* /*cbdata*/) {
    std::cerr << "[OptiX][" << level << "][" << tag << "]: " << message << std::endl;
}

// Helper to create default pipeline compile options
static OptixPipelineCompileOptions getDefaultPipelineCompileOptions() {
    OptixPipelineCompileOptions options = {};
    options.usesMotionBlur = false;
    options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;
    options.numPayloadValues = 4;  // RGB color + depth (optixSetPayload_0/1/2/3)
    options.numAttributeValues = 4;  // Normal x, y, z + radius from SDK intersection
    options.exceptionFlags = OPTIX_EXCEPTION_FLAG_NONE;
    options.pipelineLaunchParamsVariableName = "params";
    options.usesPrimitiveTypeFlags = OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM;
    return options;
}

// Implementation structure
struct OptiXWrapper::Impl {
    OptiXContext optix_context;  // Low-level OptiX context wrapper

    // Camera parameters
    float camera_eye[3] = {0.0f, 0.0f, 3.0f};
    float camera_u[3] = {1.0f, 0.0f, 0.0f};
    float camera_v[3] = {0.0f, 1.0f, 0.0f};
    float camera_w[3] = {0.0f, 0.0f, -1.0f};
    float fov = 60.0f;

    // Image dimensions (for aspect ratio calculation)
    unsigned int image_width = 800;
    unsigned int image_height = 600;

    // Sphere parameters
    float sphere_center[3] = {0.0f, 0.0f, 0.0f};
    float sphere_radius = 1.5f;
    float sphere_color[4] = {1.0f, 1.0f, 1.0f, 1.0f};  // Default: white, fully opaque
    float sphere_ior = 1.0f;  // Default: IOR of air/vacuum (no refraction)
    float sphere_scale = 1.0f;  // Default: 1.0 = meters

    // Light parameters
    float light_direction[3] = {0.5f, 0.5f, -0.5f};
    float light_intensity = 1.0f;

    // Plane parameters (default: y=-2 with +Y normal)
    int plane_axis = 1;          // 0=X, 1=Y, 2=Z
    bool plane_positive = true;  // true=positive normal, false=negative normal
    float plane_value = -2.0f;   // Plane position along axis

    // OptiX pipeline resources (created once, reused)
    OptixPipeline pipeline = nullptr;
    OptixModule module = nullptr;
    OptixProgramGroup raygen_prog_group = nullptr;
    OptixProgramGroup miss_prog_group = nullptr;
    OptixProgramGroup hitgroup_prog_group = nullptr;

    // GPU buffers (created once, reused)
    CUdeviceptr d_gas_output_buffer = 0;     // Geometry acceleration structure
    CUdeviceptr d_params = 0;                 // Launch parameters
    CUdeviceptr d_vertex_buffer = 0;          // Sphere center position
    CUdeviceptr d_radius_buffer = 0;          // Sphere radius
    CUdeviceptr d_image = 0;                  // Cached image buffer (reused across renders)
    size_t cached_image_size = 0;             // Size of cached image buffer in bytes
    OptixShaderBindingTable sbt = {};
    OptixTraversableHandle gas_handle = 0;

    bool pipeline_built = false;
    bool plane_params_dirty = false;  // Flag to track when plane params change
    bool sphere_params_dirty = false; // Flag to track when sphere params change
    bool initialized = false;
};

OptiXWrapper::OptiXWrapper() : impl(std::make_unique<Impl>()) {
}

OptiXWrapper::~OptiXWrapper() {
    dispose();
}

bool OptiXWrapper::initialize() {
    try {
        // Initialize OptiX using low-level context
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
    impl->sphere_center[0] = x;
    impl->sphere_center[1] = y;
    impl->sphere_center[2] = z;
    impl->sphere_radius = radius;
    impl->sphere_params_dirty = true;
}

void OptiXWrapper::setSphereColor(float r, float g, float b, float a) {
    impl->sphere_color[0] = r;
    impl->sphere_color[1] = g;
    impl->sphere_color[2] = b;
    impl->sphere_color[3] = a;
    impl->sphere_params_dirty = true;
}

void OptiXWrapper::setIOR(float ior) {
    impl->sphere_ior = ior;
    impl->sphere_params_dirty = true;
}

void OptiXWrapper::setScale(float scale) {
    impl->sphere_scale = scale;
    impl->sphere_params_dirty = true;
}

void OptiXWrapper::setCamera(const float* eye, const float* lookAt, const float* up, float fov) {
    // Store eye position and FOV
    std::memcpy(impl->camera_eye, eye, 3 * sizeof(float));
    impl->fov = fov;

    // Calculate W (view direction)
    impl->camera_w[0] = lookAt[0] - eye[0];
    impl->camera_w[1] = lookAt[1] - eye[1];
    impl->camera_w[2] = lookAt[2] - eye[2];
    VectorMath::normalize3f(impl->camera_w);

    // Calculate U (right = up × W)
    float u[3];
    VectorMath::cross3f(u, up, impl->camera_w);
    VectorMath::normalize3f(u);

    // Calculate V (W × U)
    float v[3];
    VectorMath::cross3f(v, impl->camera_w, u);

    // Scale by FOV and aspect ratio
    float aspect_ratio = static_cast<float>(impl->image_width) / static_cast<float>(impl->image_height);
    float ulen = std::tan(fov * 0.5f * M_PI / 180.0f);
    float vlen = ulen / aspect_ratio;

    impl->camera_u[0] = u[0] * ulen;
    impl->camera_u[1] = u[1] * ulen;
    impl->camera_u[2] = u[2] * ulen;

    impl->camera_v[0] = v[0] * vlen;
    impl->camera_v[1] = v[1] * vlen;
    impl->camera_v[2] = v[2] * vlen;
}

// Helper function to read PTX file
static std::string readPTXFile(const std::string& filename) {
    std::ifstream file(filename, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        // Try to provide helpful error information
        std::ostringstream ss;
        ss << "Failed to open PTX file: " << filename;
        ss << " (errno: " << errno << " - " << std::strerror(errno) << ")";
        throw std::runtime_error(ss.str());
    }

    std::streamsize size = file.tellg();
    file.seekg(0, std::ios::beg);

    std::string content(size, '\0');
    if (!file.read(&content[0], size)) {
        throw std::runtime_error("Failed to read PTX file: " + filename);
    }

    return content;
}

// Build geometry acceleration structure for the sphere
void OptiXWrapper::buildGeometryAccelerationStructure() {
    // Create AABB (Axis-Aligned Bounding Box) for custom sphere primitive
    OptixAabb aabb;
    aabb.minX = impl->sphere_center[0] - impl->sphere_radius;
    aabb.minY = impl->sphere_center[1] - impl->sphere_radius;
    aabb.minZ = impl->sphere_center[2] - impl->sphere_radius;
    aabb.maxX = impl->sphere_center[0] + impl->sphere_radius;
    aabb.maxY = impl->sphere_center[1] + impl->sphere_radius;
    aabb.maxZ = impl->sphere_center[2] + impl->sphere_radius;

    // Configure acceleration structure build
    OptixAccelBuildOptions accel_options = {};
    accel_options.buildFlags = OPTIX_BUILD_FLAG_ALLOW_COMPACTION;
    accel_options.operation = OPTIX_BUILD_OPERATION_BUILD;

    // Use OptiXContext to build GAS
    OptiXContext::GASBuildResult result = impl->optix_context.buildCustomPrimitiveGAS(
        aabb,
        accel_options
    );

    impl->d_gas_output_buffer = result.gas_buffer;
    impl->gas_handle = result.handle;
}

// Load and compile PTX modules, return the sphere intersection module
OptixModule OptiXWrapper::loadPTXModules() {
    // Load combined PTX module (all three shaders in one file)
    // PTX file is copied to target/native/x86_64-linux/bin/ for runtime access
    std::string ptx_path = "target/native/x86_64-linux/bin/sphere_combined.ptx";
    std::string ptx_content = readPTXFile(ptx_path);

    OptixModuleCompileOptions module_compile_options = {};
    module_compile_options.maxRegisterCount = OPTIX_COMPILE_DEFAULT_MAX_REGISTER_COUNT;
    module_compile_options.optLevel = OPTIX_COMPILE_OPTIMIZATION_DEFAULT;
    module_compile_options.debugLevel = OPTIX_COMPILE_DEBUG_LEVEL_MINIMAL;

    OptixPipelineCompileOptions pipeline_compile_options = getDefaultPipelineCompileOptions();

    // Use OptiXContext to create module
    impl->module = impl->optix_context.createModuleFromPTX(
        ptx_content,
        module_compile_options,
        pipeline_compile_options
    );

    // Custom intersection is in impl->module, no need for built-in sphere module
    return impl->module;
}

// Create program groups (raygen, miss, hit group)
void OptiXWrapper::createProgramGroups(OptixModule sphere_module) {
    // Use OptiXContext to create program groups
    impl->raygen_prog_group = impl->optix_context.createRaygenProgramGroup(
        impl->module, "__raygen__rg"
    );

    impl->miss_prog_group = impl->optix_context.createMissProgramGroup(
        impl->module, "__miss__ms"
    );

    impl->hitgroup_prog_group = impl->optix_context.createHitgroupProgramGroup(
        impl->module, "__closesthit__ch",
        impl->module, "__intersection__sphere"
    );
}

// Create OptiX pipeline and configure stack sizes
void OptiXWrapper::createPipeline() {
    OptixProgramGroup program_groups[] = {
        impl->raygen_prog_group,
        impl->miss_prog_group,
        impl->hitgroup_prog_group
    };

    OptixPipelineCompileOptions pipeline_compile_options = getDefaultPipelineCompileOptions();

    OptixPipelineLinkOptions pipeline_link_options = {};
    pipeline_link_options.maxTraceDepth = MAX_TRACE_DEPTH;  // Defined in OptiXData.h

    // Use OptiXContext to create pipeline
    impl->pipeline = impl->optix_context.createPipeline(
        pipeline_compile_options,
        pipeline_link_options,
        program_groups,
        3
    );
}

// Set up Shader Binding Table (SBT)
void OptiXWrapper::setupShaderBindingTable() {
    // Clean up old SBT records if they exist (for pipeline rebuild)
    if (impl->sbt.raygenRecord) {
        impl->optix_context.freeSBTRecord(impl->sbt.raygenRecord);
        impl->sbt.raygenRecord = 0;
    }
    if (impl->sbt.missRecordBase) {
        impl->optix_context.freeSBTRecord(impl->sbt.missRecordBase);
        impl->sbt.missRecordBase = 0;
    }
    if (impl->sbt.hitgroupRecordBase) {
        impl->optix_context.freeSBTRecord(impl->sbt.hitgroupRecordBase);
        impl->sbt.hitgroupRecordBase = 0;
    }

    // Ray generation record data
    RayGenData rg_data;
    std::memcpy(rg_data.cam_eye, impl->camera_eye, sizeof(float) * 3);
    std::memcpy(rg_data.camera_u, impl->camera_u, sizeof(float) * 3);
    std::memcpy(rg_data.camera_v, impl->camera_v, sizeof(float) * 3);
    std::memcpy(rg_data.camera_w, impl->camera_w, sizeof(float) * 3);

    impl->sbt.raygenRecord = impl->optix_context.createRaygenSBTRecord(
        impl->raygen_prog_group,
        rg_data
    );

    // Miss record data (plane parameters moved to Params for performance)
    MissData ms_data;
    ms_data.r = OptiXConstants::DEFAULT_BG_R;
    ms_data.g = OptiXConstants::DEFAULT_BG_G;
    ms_data.b = OptiXConstants::DEFAULT_BG_B;

    impl->sbt.missRecordBase = impl->optix_context.createMissSBTRecord(
        impl->miss_prog_group,
        ms_data
    );
    impl->sbt.missRecordStrideInBytes = sizeof(MissSbtRecord);
    impl->sbt.missRecordCount = 1;

    // Hit group record data (material properties moved to Params for performance)
    HitGroupData hg_data;
    std::memcpy(hg_data.sphere_center, impl->sphere_center, sizeof(float) * 3);
    hg_data.sphere_radius = impl->sphere_radius;

    impl->sbt.hitgroupRecordBase = impl->optix_context.createHitgroupSBTRecord(
        impl->hitgroup_prog_group,
        hg_data
    );
    impl->sbt.hitgroupRecordStrideInBytes = sizeof(HitGroupSbtRecord);
    impl->sbt.hitgroupRecordCount = 1;
}

// Build OptiX pipeline (orchestrates all pipeline build steps)
void OptiXWrapper::buildPipeline() {
    // Clean up old pipeline resources if they exist (for pipeline rebuild)
    if (impl->pipeline) {
        impl->optix_context.destroyPipeline(impl->pipeline);
        impl->pipeline = nullptr;
    }
    if (impl->raygen_prog_group) {
        impl->optix_context.destroyProgramGroup(impl->raygen_prog_group);
        impl->raygen_prog_group = nullptr;
    }
    if (impl->miss_prog_group) {
        impl->optix_context.destroyProgramGroup(impl->miss_prog_group);
        impl->miss_prog_group = nullptr;
    }
    if (impl->hitgroup_prog_group) {
        impl->optix_context.destroyProgramGroup(impl->hitgroup_prog_group);
        impl->hitgroup_prog_group = nullptr;
    }
    if (impl->module) {
        impl->optix_context.destroyModule(impl->module);
        impl->module = nullptr;
    }

    buildGeometryAccelerationStructure();
    OptixModule sphere_module = loadPTXModules();
    createProgramGroups(sphere_module);
    createPipeline();
    setupShaderBindingTable();

    // Allocate params buffer (only on first build)
    if (!impl->d_params) {
        CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_params), sizeof(Params)));
    }
}

void OptiXWrapper::setLight(const float* direction, float intensity) {
    // Store and normalize light direction
    std::memcpy(impl->light_direction, direction, 3 * sizeof(float));
    VectorMath::normalize3f(impl->light_direction);
    impl->light_intensity = intensity;
}

void OptiXWrapper::setPlane(int axis, bool positive, float value) {
    impl->plane_axis = axis;
    impl->plane_positive = positive;
    impl->plane_value = value;
    impl->plane_params_dirty = true;  // Mark pipeline as needing rebuild
}

void OptiXWrapper::render(int width, int height, unsigned char* output) {
    if (!impl->initialized) {
        throw std::runtime_error("[OptiX] render() called before initialize()");
    }

    try {
        // Update image dimensions for aspect ratio calculations
        impl->image_width = width;
        impl->image_height = height;

        // Build OptiX pipeline on first render call or when params change
        if (!impl->pipeline_built || impl->plane_params_dirty || impl->sphere_params_dirty) {
            // Debug: Pipeline rebuild due to parameter change
            buildPipeline();
            impl->pipeline_built = true;
            impl->plane_params_dirty = false;
            impl->sphere_params_dirty = false;
        }

        // Reuse GPU image buffer (allocate only if size changed)
        const size_t required_size = width * height * 4; // RGBA
        if (impl->cached_image_size != required_size) {
            // Free old buffer if it exists
            if (impl->d_image) {
                CUDA_CHECK(cudaFree(reinterpret_cast<void*>(impl->d_image)));
                impl->d_image = 0;
                impl->cached_image_size = 0;
            }
            // Allocate new buffer with exact required size
            CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_image), required_size));
            impl->cached_image_size = required_size;
        }

        // Set up launch parameters (use cached buffer)
        Params params;
        params.image = reinterpret_cast<unsigned char*>(impl->d_image);
        params.image_width = width;
        params.image_height = height;
        params.handle = impl->gas_handle;

        // Dynamic scene data (moved from SBT for performance)
        std::memcpy(params.sphere_color, impl->sphere_color, sizeof(float) * 4);
        params.sphere_ior = impl->sphere_ior;
        params.sphere_scale = impl->sphere_scale;
        std::memcpy(params.light_dir, impl->light_direction, sizeof(float) * 3);
        params.light_intensity = impl->light_intensity;
        params.plane_axis = impl->plane_axis;
        params.plane_positive = impl->plane_positive;
        params.plane_value = impl->plane_value;

        // Copy params to GPU
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(impl->d_params),
            &params,
            sizeof(Params),
            cudaMemcpyHostToDevice
        ));

        // Launch OptiX using OptiXContext
        impl->optix_context.launch(
            impl->pipeline,
            impl->sbt,
            impl->d_params,
            width,
            height
        );

        // Copy result back to CPU
        CUDA_CHECK(cudaMemcpy(
            output,
            reinterpret_cast<void*>(impl->d_image),
            required_size,
            cudaMemcpyDeviceToHost
        ));

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

void OptiXWrapper::dispose() {
    if (impl->initialized) {
        try {
            // Clean up OptiX pipeline resources using OptiXContext
            if (impl->pipeline) {
                impl->optix_context.destroyPipeline(impl->pipeline);
                impl->pipeline = nullptr;
            }

            if (impl->raygen_prog_group) {
                impl->optix_context.destroyProgramGroup(impl->raygen_prog_group);
                impl->raygen_prog_group = nullptr;
            }

            if (impl->miss_prog_group) {
                impl->optix_context.destroyProgramGroup(impl->miss_prog_group);
                impl->miss_prog_group = nullptr;
            }

            if (impl->hitgroup_prog_group) {
                impl->optix_context.destroyProgramGroup(impl->hitgroup_prog_group);
                impl->hitgroup_prog_group = nullptr;
            }

            if (impl->module) {
                impl->optix_context.destroyModule(impl->module);
                impl->module = nullptr;
            }

            // Clean up GPU buffers
            if (impl->d_gas_output_buffer) {
                impl->optix_context.destroyGAS(impl->d_gas_output_buffer);
                impl->d_gas_output_buffer = 0;
            }

            if (impl->d_params) {
                cudaFree(reinterpret_cast<void*>(impl->d_params));
                impl->d_params = 0;
            }

            // Clean up cached image buffer
            if (impl->d_image) {
                cudaFree(reinterpret_cast<void*>(impl->d_image));
                impl->d_image = 0;
                impl->cached_image_size = 0;
            }

            // Clean up SBT buffers using OptiXContext
            if (impl->sbt.raygenRecord) {
                impl->optix_context.freeSBTRecord(impl->sbt.raygenRecord);
                impl->sbt.raygenRecord = 0;
            }

            if (impl->sbt.missRecordBase) {
                impl->optix_context.freeSBTRecord(impl->sbt.missRecordBase);
                impl->sbt.missRecordBase = 0;
            }

            if (impl->sbt.hitgroupRecordBase) {
                impl->optix_context.freeSBTRecord(impl->sbt.hitgroupRecordBase);
                impl->sbt.hitgroupRecordBase = 0;
            }

            // Clean up OptiX context
            impl->optix_context.destroy();

            impl->pipeline_built = false;

        } catch (const std::exception& e) {
            std::cerr << "[OptiX] Cleanup error: " << e.what() << std::endl;
        }
        impl->initialized = false;
    }
}
