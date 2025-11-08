#include "include/OptiXContext.h"
#include "include/OptiXConstants.h"
#include "include/OptiXData.h"

#include <iostream>
#include <sstream>
#include <fstream>
#include <cerrno>
#include <cstring>

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

OptiXContext::OptiXContext() : context_(nullptr), initialized_(false) {
}

OptiXContext::~OptiXContext() {
    destroy();
}

bool OptiXContext::initialize() {
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

        OPTIX_CHECK(optixDeviceContextCreate(cuCtx, &options, &context_));

        initialized_ = true;
        return true;

    } catch (const std::exception& e) {
        std::cerr << "[OptiXContext] Initialization failed: " << e.what() << std::endl;
        return false;
    }
}

void OptiXContext::destroy() {
    if (initialized_ && context_) {
        try {
            optixDeviceContextDestroy(context_);
            context_ = nullptr;
            initialized_ = false;
        } catch (const std::exception& e) {
            std::cerr << "[OptiXContext] Cleanup error: " << e.what() << std::endl;
        }
    }
}

OptixModule OptiXContext::createModuleFromPTX(
    const std::string& ptx_content,
    const OptixModuleCompileOptions& module_options,
    const OptixPipelineCompileOptions& pipeline_options)
{
    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixModule module = nullptr;
    OPTIX_CHECK(optixModuleCreate(
        context_,
        &module_options,
        &pipeline_options,
        ptx_content.c_str(),
        ptx_content.size(),
        log,
        &log_size,
        &module
    ));

    return module;
}

void OptiXContext::destroyModule(OptixModule module) {
    if (module) {
        optixModuleDestroy(module);
    }
}

OptixProgramGroup OptiXContext::createRaygenProgramGroup(
    OptixModule module,
    const char* entry_function_name)
{
    OptixProgramGroupOptions program_group_options = {};
    OptixProgramGroupDesc raygen_desc = {};
    raygen_desc.kind = OPTIX_PROGRAM_GROUP_KIND_RAYGEN;
    raygen_desc.raygen.module = module;
    raygen_desc.raygen.entryFunctionName = entry_function_name;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixProgramGroup program_group = nullptr;
    OPTIX_CHECK(optixProgramGroupCreate(
        context_,
        &raygen_desc,
        1,
        &program_group_options,
        log,
        &log_size,
        &program_group
    ));

    return program_group;
}

OptixProgramGroup OptiXContext::createMissProgramGroup(
    OptixModule module,
    const char* entry_function_name)
{
    OptixProgramGroupOptions program_group_options = {};
    OptixProgramGroupDesc miss_desc = {};
    miss_desc.kind = OPTIX_PROGRAM_GROUP_KIND_MISS;
    miss_desc.miss.module = module;
    miss_desc.miss.entryFunctionName = entry_function_name;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixProgramGroup program_group = nullptr;
    OPTIX_CHECK(optixProgramGroupCreate(
        context_,
        &miss_desc,
        1,
        &program_group_options,
        log,
        &log_size,
        &program_group
    ));

    return program_group;
}

OptixProgramGroup OptiXContext::createHitgroupProgramGroup(
    OptixModule module_ch,
    const char* entry_ch,
    OptixModule module_is,
    const char* entry_is)
{
    OptixProgramGroupOptions program_group_options = {};
    OptixProgramGroupDesc hitgroup_desc = {};
    hitgroup_desc.kind = OPTIX_PROGRAM_GROUP_KIND_HITGROUP;
    hitgroup_desc.hitgroup.moduleCH = module_ch;
    hitgroup_desc.hitgroup.entryFunctionNameCH = entry_ch;
    hitgroup_desc.hitgroup.moduleAH = nullptr;
    hitgroup_desc.hitgroup.entryFunctionNameAH = nullptr;
    hitgroup_desc.hitgroup.moduleIS = module_is;
    hitgroup_desc.hitgroup.entryFunctionNameIS = entry_is;

    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixProgramGroup program_group = nullptr;
    OPTIX_CHECK(optixProgramGroupCreate(
        context_,
        &hitgroup_desc,
        1,
        &program_group_options,
        log,
        &log_size,
        &program_group
    ));

    return program_group;
}

void OptiXContext::destroyProgramGroup(OptixProgramGroup program_group) {
    if (program_group) {
        optixProgramGroupDestroy(program_group);
    }
}

OptixPipeline OptiXContext::createPipeline(
    const OptixPipelineCompileOptions& pipeline_options,
    const OptixPipelineLinkOptions& link_options,
    OptixProgramGroup* program_groups,
    unsigned int num_program_groups)
{
    char log[OptiXConstants::LOG_BUFFER_SIZE];
    size_t log_size = sizeof(log);

    OptixPipeline pipeline = nullptr;
    OPTIX_CHECK(optixPipelineCreate(
        context_,
        &pipeline_options,
        &link_options,
        program_groups,
        num_program_groups,
        log,
        &log_size,
        &pipeline
    ));

    // Configure stack sizes
    OptixStackSizes stack_sizes = {};
    for (unsigned int i = 0; i < num_program_groups; ++i) {
        OPTIX_CHECK(optixUtilAccumulateStackSizes(program_groups[i], &stack_sizes, nullptr));
    }

    uint32_t max_trace_depth = 2;  // Primary ray + shadow/refraction ray
    uint32_t max_cc_depth = 0;
    uint32_t max_dc_depth = 0;
    uint32_t direct_callable_stack_size_from_traversal;
    uint32_t direct_callable_stack_size_from_state;
    uint32_t continuation_stack_size;

    OPTIX_CHECK(optixUtilComputeStackSizes(
        &stack_sizes,
        max_trace_depth,
        max_cc_depth,
        max_dc_depth,
        &direct_callable_stack_size_from_traversal,
        &direct_callable_stack_size_from_state,
        &continuation_stack_size
    ));

    OPTIX_CHECK(optixPipelineSetStackSize(
        pipeline,
        direct_callable_stack_size_from_traversal,
        direct_callable_stack_size_from_state,
        continuation_stack_size,
        max_trace_depth
    ));

    return pipeline;
}

void OptiXContext::destroyPipeline(OptixPipeline pipeline) {
    if (pipeline) {
        optixPipelineDestroy(pipeline);
    }
}

OptiXContext::GASBuildResult OptiXContext::buildCustomPrimitiveGAS(
    const OptixAabb& aabb,
    const OptixAccelBuildOptions& build_options)
{
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

    // Query memory requirements
    OptixAccelBufferSizes gas_buffer_sizes;
    OPTIX_CHECK(optixAccelComputeMemoryUsage(
        context_,
        &build_options,
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
    CUdeviceptr d_gas_output_buffer;
    CUDA_CHECK(cudaMalloc(
        reinterpret_cast<void**>(&d_gas_output_buffer),
        gas_buffer_sizes.outputSizeInBytes
    ));

    // Build acceleration structure
    OptixTraversableHandle gas_handle;
    OPTIX_CHECK(optixAccelBuild(
        context_,
        0, // CUDA stream
        &build_options,
        &sphere_input,
        1,
        d_temp_buffer,
        gas_buffer_sizes.tempSizeInBytes,
        d_gas_output_buffer,
        gas_buffer_sizes.outputSizeInBytes,
        &gas_handle,
        nullptr, // emitted property list
        0        // num emitted properties
    ));

    // Clean up temporary buffers
    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(d_temp_buffer)));
    CUDA_CHECK(cudaFree(reinterpret_cast<void*>(d_aabb)));

    GASBuildResult result;
    result.gas_buffer = d_gas_output_buffer;
    result.handle = gas_handle;
    return result;
}

void OptiXContext::destroyGAS(CUdeviceptr gas_buffer) {
    if (gas_buffer) {
        cudaFree(reinterpret_cast<void*>(gas_buffer));
    }
}

// Helper template for SBT record creation
template<typename T>
static CUdeviceptr createSBTRecordHelper(OptixProgramGroup program_group, const T& data) {
    using SbtRecordType = SbtRecord<T>;
    SbtRecordType sbt_record;
    sbt_record.data = data;

    OPTIX_CHECK(optixSbtRecordPackHeader(program_group, &sbt_record));

    CUdeviceptr d_record;
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_record), sizeof(SbtRecordType)));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(d_record),
        &sbt_record,
        sizeof(SbtRecordType),
        cudaMemcpyHostToDevice
    ));

    return d_record;
}

CUdeviceptr OptiXContext::createRaygenSBTRecord(OptixProgramGroup program_group, const RayGenData& data) {
    return createSBTRecordHelper(program_group, data);
}

CUdeviceptr OptiXContext::createMissSBTRecord(OptixProgramGroup program_group, const MissData& data) {
    return createSBTRecordHelper(program_group, data);
}

CUdeviceptr OptiXContext::createHitgroupSBTRecord(OptixProgramGroup program_group, const HitGroupData& data) {
    return createSBTRecordHelper(program_group, data);
}

void OptiXContext::freeSBTRecord(CUdeviceptr record) {
    if (record) {
        cudaFree(reinterpret_cast<void*>(record));
    }
}

void OptiXContext::launch(
    OptixPipeline pipeline,
    const OptixShaderBindingTable& sbt,
    CUdeviceptr params_buffer,
    unsigned int width,
    unsigned int height)
{
    OPTIX_CHECK(optixLaunch(
        pipeline,
        0, // CUDA stream
        params_buffer,
        sizeof(Params),
        &sbt,
        width,
        height,
        1 // depth
    ));

    // Wait for GPU to finish
    CUDA_CHECK(cudaDeviceSynchronize());
}
