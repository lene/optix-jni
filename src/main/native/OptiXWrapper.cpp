#include "include/OptiXWrapper.h"
#include "include/OptiXConstants.h"
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

// OptiX error checking macro
#define OPTIX_CHECK(call)                                                     \
    do {                                                                      \
        OptixResult res = call;                                               \
        if (res != OPTIX_SUCCESS) {                                           \
            std::ostringstream ss;                                            \
            ss << "OptiX call '" << #call << "' failed: "                     \
               << optixGetErrorName(res) << " (" << res << ")";               \
            throw std::runtime_error(ss.str());                               \
        }                                                                     \
    } while(0)

// CUDA error checking macro
#define CUDA_CHECK(call)                                                      \
    do {                                                                      \
        cudaError_t err = call;                                               \
        if (err != cudaSuccess) {                                             \
            std::ostringstream ss;                                            \
            ss << "CUDA call '" << #call << "' failed: "                      \
               << cudaGetErrorString(err) << " (" << err << ")";              \
            if (err == 718) {                                                 \
                ss << "\n\n"                                                  \
                   << "ERROR 718 (invalid program counter) indicates OptiX " \
                   << "SDK/driver version mismatch.\n"                        \
                   << "To diagnose:\n"                                        \
                   << "  1. Check driver's OptiX version:\n"                 \
                   << "     strings /usr/lib/x86_64-linux-gnu/libnvoptix.so.* | grep 'OptiX Version'\n" \
                   << "  2. Check SDK version used to build:\n"              \
                   << "     grep 'OptiX SDK:' optix-jni/target/native/x86_64-linux/build/CMakeCache.txt\n" \
                   << "  3. Install matching OptiX SDK from https://developer.nvidia.com/optix\n" \
                   << "  4. Rebuild: rm -rf optix-jni/target/native && sbt 'project optixJni' compile\n"; \
            }                                                                 \
            throw std::runtime_error(ss.str());                               \
        }                                                                     \
    } while(0)

// OptiX log callback
static void optixLogCallback(unsigned int level, const char* tag, const char* message, void* /*cbdata*/) {
    std::cerr << "[OptiX][" << level << "][" << tag << "]: " << message << std::endl;
}

// Implementation structure
struct OptiXWrapper::Impl {
    OptixDeviceContext context = nullptr;

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
    OptixShaderBindingTable sbt = {};
    OptixTraversableHandle gas_handle = 0;

    bool pipeline_built = false;
    bool initialized = false;
};

OptiXWrapper::OptiXWrapper() : impl(std::make_unique<Impl>()) {
}

OptiXWrapper::~OptiXWrapper() {
    dispose();
}

bool OptiXWrapper::initialize() {
    try {
        // Initialize CUDA runtime
        CUDA_CHECK(cudaFree(0));

        // Initialize OptiX
        OPTIX_CHECK(optixInit());

        // Create OptiX device context
        CUcontext cuCtx = 0;  // 0 = use current CUDA context
        OptixDeviceContextOptions options = {};
        options.logCallbackFunction = &optixLogCallback;
        options.logCallbackLevel = OptiXConstants::OPTIX_LOG_LEVEL_INFO;

        OPTIX_CHECK(optixDeviceContextCreate(cuCtx, &options, &impl->context));

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
}

void OptiXWrapper::setSphereColor(float r, float g, float b, float a) {
    impl->sphere_color[0] = r;
    impl->sphere_color[1] = g;
    impl->sphere_color[2] = b;
    impl->sphere_color[3] = a;
}

void OptiXWrapper::setIOR(float ior) {
    impl->sphere_ior = ior;
}

void OptiXWrapper::setScale(float scale) {
    impl->sphere_scale = scale;
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

    // Allocate and copy AABB to GPU
    CUdeviceptr d_aabb;
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_aabb), sizeof(OptixAabb)));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(d_aabb),
        &aabb,
        sizeof(OptixAabb),
        cudaMemcpyHostToDevice
    ));

    // Set up custom primitive build input
    OptixBuildInput sphere_input = {};
    sphere_input.type = OPTIX_BUILD_INPUT_TYPE_CUSTOM_PRIMITIVES;

    uint32_t sphere_input_flags[1] = {OPTIX_GEOMETRY_FLAG_NONE};

    sphere_input.customPrimitiveArray.aabbBuffers = &d_aabb;
    sphere_input.customPrimitiveArray.numPrimitives = 1;
    sphere_input.customPrimitiveArray.flags = sphere_input_flags;
    sphere_input.customPrimitiveArray.numSbtRecords = 1;
    sphere_input.customPrimitiveArray.sbtIndexOffsetBuffer = 0;
    sphere_input.customPrimitiveArray.sbtIndexOffsetSizeInBytes = 0;
    sphere_input.customPrimitiveArray.sbtIndexOffsetStrideInBytes = 0;

    // Configure acceleration structure build
    OptixAccelBuildOptions accel_options = {};
    accel_options.buildFlags = OPTIX_BUILD_FLAG_ALLOW_COMPACTION;
    accel_options.operation = OPTIX_BUILD_OPERATION_BUILD;

    // Query memory requirements
    OptixAccelBufferSizes gas_buffer_sizes;
    OPTIX_CHECK(optixAccelComputeMemoryUsage(
        impl->context,
        &accel_options,
        &sphere_input,
        1,
        &gas_buffer_sizes
    ));

    // Allocate temporary buffer
    CUdeviceptr d_temp_buffer;
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&d_temp_buffer),
        gas_buffer_sizes.tempSizeInBytes
    ));

    // Allocate output buffer
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&impl->d_gas_output_buffer),
        gas_buffer_sizes.outputSizeInBytes
    ));

    // Build acceleration structure
    OPTIX_CHECK(optixAccelBuild(
        impl->context,
        0, // CUDA stream
        &accel_options,
        &sphere_input,
        1,
        d_temp_buffer,
        gas_buffer_sizes.tempSizeInBytes,
        impl->d_gas_output_buffer,
        gas_buffer_sizes.outputSizeInBytes,
        &impl->gas_handle,
        nullptr, // emitted property list
        0        // num emitted properties
    ));

    // Clean up temporary buffers
    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(d_temp_buffer)));
    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(d_aabb)));
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

    OptixPipelineCompileOptions pipeline_compile_options = {};
    pipeline_compile_options.usesMotionBlur = false;
    pipeline_compile_options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;
    pipeline_compile_options.numPayloadValues = 4; // RGB color + depth (optixSetPayload_0/1/2/3)
    pipeline_compile_options.numAttributeValues = 3; // Normal x, y, z from custom intersection
    pipeline_compile_options.exceptionFlags = OPTIX_EXCEPTION_FLAG_NONE;
    pipeline_compile_options.pipelineLaunchParamsVariableName = "params";
    pipeline_compile_options.usesPrimitiveTypeFlags = OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OPTIX_CHECK(optixModuleCreate(
        impl->context,
        &module_compile_options,
        &pipeline_compile_options,
        ptx_content.c_str(),
        ptx_content.size(),
        log,
        &log_size,
        &impl->module
    ));

    // Custom intersection is in impl->module, no need for built-in sphere module
    return impl->module;
}

// Create program groups (raygen, miss, hit group)
void OptiXWrapper::createProgramGroups(OptixModule sphere_module) {
    OptixProgramGroupOptions program_group_options = {};
    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size;

    // Ray generation program group
    {
        OptixProgramGroupDesc raygen_desc = {};
        raygen_desc.kind = OPTIX_PROGRAM_GROUP_KIND_RAYGEN;
        raygen_desc.raygen.module = impl->module;
        raygen_desc.raygen.entryFunctionName = "__raygen__rg";

        log_size = sizeof(log);
        OPTIX_CHECK(optixProgramGroupCreate(
            impl->context,
            &raygen_desc,
            1,
            &program_group_options,
            log,
            &log_size,
            &impl->raygen_prog_group
        ));
    }

    // Miss program group
    {
        OptixProgramGroupDesc miss_desc = {};
        miss_desc.kind = OPTIX_PROGRAM_GROUP_KIND_MISS;
        miss_desc.miss.module = impl->module;
        miss_desc.miss.entryFunctionName = "__miss__ms";

        log_size = sizeof(log);
        OPTIX_CHECK(optixProgramGroupCreate(
            impl->context,
            &miss_desc,
            1,
            &program_group_options,
            log,
            &log_size,
            &impl->miss_prog_group
        ));
    }

    // Hit group program group
    {
        OptixProgramGroupDesc hitgroup_desc = {};
        hitgroup_desc.kind = OPTIX_PROGRAM_GROUP_KIND_HITGROUP;
        hitgroup_desc.hitgroup.moduleCH = impl->module;
        hitgroup_desc.hitgroup.entryFunctionNameCH = "__closesthit__ch";
        hitgroup_desc.hitgroup.moduleAH = nullptr;
        hitgroup_desc.hitgroup.entryFunctionNameAH = nullptr;
        hitgroup_desc.hitgroup.moduleIS = impl->module;
        hitgroup_desc.hitgroup.entryFunctionNameIS = "__intersection__sphere";

        log_size = sizeof(log);
        OPTIX_CHECK(optixProgramGroupCreate(
            impl->context,
            &hitgroup_desc,
            1,
            &program_group_options,
            log,
            &log_size,
            &impl->hitgroup_prog_group
        ));
    }

}

// Create OptiX pipeline and configure stack sizes
void OptiXWrapper::createPipeline() {
    OptixProgramGroup program_groups[] = {
        impl->raygen_prog_group,
        impl->miss_prog_group,
        impl->hitgroup_prog_group
    };

    // TODO: Refactor to avoid duplicating pipeline_compile_options (also in loadPTXModules())
    OptixPipelineCompileOptions pipeline_compile_options = {};
    pipeline_compile_options.usesMotionBlur = false;
    pipeline_compile_options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;
    pipeline_compile_options.numPayloadValues = 4; // RGB color + depth (optixSetPayload_0/1/2/3)
    pipeline_compile_options.numAttributeValues = 3; // Normal x, y, z from custom intersection
    pipeline_compile_options.exceptionFlags = OPTIX_EXCEPTION_FLAG_NONE;
    pipeline_compile_options.pipelineLaunchParamsVariableName = "params";
    pipeline_compile_options.usesPrimitiveTypeFlags = OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM;

    OptixPipelineLinkOptions pipeline_link_options = {};
    pipeline_link_options.maxTraceDepth = OptiXConstants::MAX_TRACE_DEPTH;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);
    OPTIX_CHECK(optixPipelineCreate(
        impl->context,
        &pipeline_compile_options,
        &pipeline_link_options,
        program_groups,
        3,
        log,
        &log_size,
        &impl->pipeline
    ));

    // Set stack sizes
    OptixStackSizes stack_sizes = {};
    for (auto& prog_group : program_groups) {
        OPTIX_CHECK(optixUtilAccumulateStackSizes(prog_group, &stack_sizes, impl->pipeline));
    }

    uint32_t direct_callable_stack_size_from_traversal;
    uint32_t direct_callable_stack_size_from_state;
    uint32_t continuation_stack_size;
    OPTIX_CHECK(optixUtilComputeStackSizes(
        &stack_sizes,
        OptiXConstants::MAX_TRACE_DEPTH,
        0, // maxCCDepth
        0, // maxDCDepth
        &direct_callable_stack_size_from_traversal,
        &direct_callable_stack_size_from_state,
        &continuation_stack_size
    ));

    OPTIX_CHECK(optixPipelineSetStackSize(
        impl->pipeline,
        direct_callable_stack_size_from_traversal,
        direct_callable_stack_size_from_state,
        continuation_stack_size,
        1 // maxTraversableDepth
    ));
}

// Set up Shader Binding Table (SBT)
void OptiXWrapper::setupShaderBindingTable() {
    // Ray generation record
    {
        RayGenSbtRecord rg_sbt;
        // Copy camera data
        std::memcpy(rg_sbt.data.cam_eye, impl->camera_eye, sizeof(float) * 3);
        std::memcpy(rg_sbt.data.camera_u, impl->camera_u, sizeof(float) * 3);
        std::memcpy(rg_sbt.data.camera_v, impl->camera_v, sizeof(float) * 3);
        std::memcpy(rg_sbt.data.camera_w, impl->camera_w, sizeof(float) * 3);

        OPTIX_CHECK(optixSbtRecordPackHeader(impl->raygen_prog_group, &rg_sbt));

        CUdeviceptr d_raygen_record;
        CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_raygen_record), sizeof(RayGenSbtRecord)));
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(d_raygen_record),
            &rg_sbt,
            sizeof(RayGenSbtRecord),
            cudaMemcpyHostToDevice
        ));

        impl->sbt.raygenRecord = d_raygen_record;
    }

    // Miss record
    {
        MissSbtRecord ms_sbt;
        ms_sbt.data.r = OptiXConstants::DEFAULT_BG_R;
        ms_sbt.data.g = OptiXConstants::DEFAULT_BG_G;
        ms_sbt.data.b = OptiXConstants::DEFAULT_BG_B;

        OPTIX_CHECK(optixSbtRecordPackHeader(impl->miss_prog_group, &ms_sbt));

        CUdeviceptr d_miss_record;
        CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_miss_record), sizeof(MissSbtRecord)));
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(d_miss_record),
            &ms_sbt,
            sizeof(MissSbtRecord),
            cudaMemcpyHostToDevice
        ));

        impl->sbt.missRecordBase = d_miss_record;
        impl->sbt.missRecordStrideInBytes = sizeof(MissSbtRecord);
        impl->sbt.missRecordCount = 1;
    }

    // Hit group record
    {
        HitGroupSbtRecord hg_sbt;
        std::memcpy(hg_sbt.data.sphere_center, impl->sphere_center, sizeof(float) * 3);
        hg_sbt.data.sphere_radius = impl->sphere_radius;
        std::memcpy(hg_sbt.data.sphere_color, impl->sphere_color, sizeof(float) * 4);
        std::memcpy(hg_sbt.data.light_dir, impl->light_direction, sizeof(float) * 3);
        hg_sbt.data.light_intensity = impl->light_intensity;
        hg_sbt.data.ior = impl->sphere_ior;
        hg_sbt.data.scale = impl->sphere_scale;

        OPTIX_CHECK(optixSbtRecordPackHeader(impl->hitgroup_prog_group, &hg_sbt));

        CUdeviceptr d_hitgroup_record;
        CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_hitgroup_record), sizeof(HitGroupSbtRecord)));
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(d_hitgroup_record),
            &hg_sbt,
            sizeof(HitGroupSbtRecord),
            cudaMemcpyHostToDevice
        ));

        impl->sbt.hitgroupRecordBase = d_hitgroup_record;
        impl->sbt.hitgroupRecordStrideInBytes = sizeof(HitGroupSbtRecord);
        impl->sbt.hitgroupRecordCount = 1;
    }
}

// Build OptiX pipeline (orchestrates all pipeline build steps)
void OptiXWrapper::buildPipeline() {
    buildGeometryAccelerationStructure();
    OptixModule sphere_module = loadPTXModules();
    createProgramGroups(sphere_module);
    createPipeline();
    setupShaderBindingTable();

    // Allocate params buffer
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_params), sizeof(Params)));
}

void OptiXWrapper::setLight(const float* direction, float intensity) {
    // Store and normalize light direction
    std::memcpy(impl->light_direction, direction, 3 * sizeof(float));
    VectorMath::normalize3f(impl->light_direction);
    impl->light_intensity = intensity;
}

void OptiXWrapper::render(int width, int height, unsigned char* output) {
    if (!impl->initialized) {
        throw std::runtime_error("[OptiX] render() called before initialize()");
    }

    try {
        // Update image dimensions for aspect ratio calculations
        impl->image_width = width;
        impl->image_height = height;

        // Build OptiX pipeline on first render call
        if (!impl->pipeline_built) {
            buildPipeline();
            impl->pipeline_built = true;
        }

        // Allocate GPU image buffer
        CUdeviceptr d_image;
        const size_t image_size = width * height * 4; // RGBA
        CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_image), image_size));

        // Set up launch parameters
        Params params;
        params.image = reinterpret_cast<unsigned char*>(d_image);
        params.image_width = width;
        params.image_height = height;
        params.handle = impl->gas_handle;

        // Copy params to GPU
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(impl->d_params),
            &params,
            sizeof(Params),
            cudaMemcpyHostToDevice
        ));

        // Launch OptiX
        OPTIX_CHECK(optixLaunch(
            impl->pipeline,
            0, // CUDA stream
            impl->d_params,
            sizeof(Params),
            &impl->sbt,
            width,
            height,
            1 // depth
        ));

        // Wait for GPU to finish
        CUDA_CHECK(cudaDeviceSynchronize());

        // Copy result back to CPU
        CUDA_CHECK(cudaMemcpy(
            output,
            reinterpret_cast<void*>(d_image),
            image_size,
            cudaMemcpyDeviceToHost
        ));

        // Clean up GPU image buffer
        CUDA_CHECK(cudaFree(reinterpret_cast<void*>(d_image)));

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
            // Clean up OptiX pipeline resources
            if (impl->pipeline) {
                optixPipelineDestroy(impl->pipeline);
                impl->pipeline = nullptr;
            }

            if (impl->raygen_prog_group) {
                optixProgramGroupDestroy(impl->raygen_prog_group);
                impl->raygen_prog_group = nullptr;
            }

            if (impl->miss_prog_group) {
                optixProgramGroupDestroy(impl->miss_prog_group);
                impl->miss_prog_group = nullptr;
            }

            if (impl->hitgroup_prog_group) {
                optixProgramGroupDestroy(impl->hitgroup_prog_group);
                impl->hitgroup_prog_group = nullptr;
            }

            if (impl->module) {
                optixModuleDestroy(impl->module);
                impl->module = nullptr;
            }

            // Clean up GPU buffers
            if (impl->d_gas_output_buffer) {
                cudaFree(reinterpret_cast<void*>(impl->d_gas_output_buffer));
                impl->d_gas_output_buffer = 0;
            }

            if (impl->d_params) {
                cudaFree(reinterpret_cast<void*>(impl->d_params));
                impl->d_params = 0;
            }

            if (impl->d_vertex_buffer) {
                cudaFree(reinterpret_cast<void*>(impl->d_vertex_buffer));
                impl->d_vertex_buffer = 0;
            }

            if (impl->d_radius_buffer) {
                cudaFree(reinterpret_cast<void*>(impl->d_radius_buffer));
                impl->d_radius_buffer = 0;
            }

            // Clean up SBT buffers
            if (impl->sbt.raygenRecord) {
                cudaFree(reinterpret_cast<void*>(impl->sbt.raygenRecord));
                impl->sbt.raygenRecord = 0;
            }

            if (impl->sbt.missRecordBase) {
                cudaFree(reinterpret_cast<void*>(impl->sbt.missRecordBase));
                impl->sbt.missRecordBase = 0;
            }

            if (impl->sbt.hitgroupRecordBase) {
                cudaFree(reinterpret_cast<void*>(impl->sbt.hitgroupRecordBase));
                impl->sbt.hitgroupRecordBase = 0;
            }

            // Clean up OptiX context
            if (impl->context) {
                optixDeviceContextDestroy(impl->context);
                impl->context = nullptr;
            }

            impl->pipeline_built = false;

        } catch (const std::exception& e) {
            std::cerr << "[OptiX] Cleanup error: " << e.what() << std::endl;
        }
        impl->initialized = false;
    }
}
