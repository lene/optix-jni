#include "include/PipelineManager.h"
#include "include/OptiXData.h"
#include "include/OptiXConstants.h"
#include "include/OptiXErrorChecking.h"
#include "include/OptiXFileUtils.h"
#include <vector>
#include <cstring>
#include <optix_stubs.h>

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

PipelineManager::PipelineManager(OptiXContext& context)
    : optix_context(context) {
}

PipelineManager::~PipelineManager() {
    cleanup(true);
}

OptixModule PipelineManager::loadPTXModules() {
    // Try multiple PTX locations in priority order:
    // 1. Working directory (for packaged app after OptiXRenderer extracts from JAR)
    // 2. Build output directory (for sbt run / sbt test)
    // 3. Classes directory (for IntelliJ/IDE runs)
    std::vector<std::string> ptx_search_paths = {
        "target/native/x86_64-linux/bin/sphere_combined.ptx",  // Extracted from JAR
        "optix-jni/target/native/x86_64-linux/bin/sphere_combined.ptx",  // sbt build output
        "optix-jni/target/classes/native/x86_64-linux/sphere_combined.ptx"  // sbt-jni managed
    };
    std::string ptx_content = optix_utils::readPTXFile(ptx_search_paths);

    OptixModuleCompileOptions module_compile_options = {};
    module_compile_options.maxRegisterCount = OPTIX_COMPILE_DEFAULT_MAX_REGISTER_COUNT;
    module_compile_options.optLevel = OPTIX_COMPILE_OPTIMIZATION_DEFAULT;
    module_compile_options.debugLevel = OPTIX_COMPILE_DEBUG_LEVEL_MINIMAL;

    OptixPipelineCompileOptions pipeline_compile_options = getDefaultPipelineCompileOptions();

    // Use OptiXContext to create module
    module = optix_context.createModuleFromPTX(
        ptx_content,
        module_compile_options,
        pipeline_compile_options
    );

    return module;
}

void PipelineManager::createProgramGroups() {
    // Use OptiXContext to create program groups
    raygen_prog_group = optix_context.createRaygenProgramGroup(
        module, "__raygen__rg"
    );

    // Primary ray miss program
    miss_prog_group = optix_context.createMissProgramGroup(
        module, "__miss__ms"
    );

    // Shadow ray miss program
    shadow_miss_prog_group = optix_context.createMissProgramGroup(
        module, "__miss__shadow"
    );

    // Primary ray hit group (closest hit + intersection)
    hitgroup_prog_group = optix_context.createHitgroupProgramGroup(
        module, "__closesthit__ch",
        module, "__intersection__sphere"
    );

    // Shadow ray hit group (only closest hit, same intersection program)
    shadow_hitgroup_prog_group = optix_context.createHitgroupProgramGroup(
        module, "__closesthit__shadow",
        module, "__intersection__sphere"
    );

    // Caustics raygen programs (for Progressive Photon Mapping)
    caustics_hitpoints_raygen = optix_context.createRaygenProgramGroup(
        module, "__raygen__hitpoints"
    );
    caustics_photons_raygen = optix_context.createRaygenProgramGroup(
        module, "__raygen__photons"
    );
    caustics_radiance_raygen = optix_context.createRaygenProgramGroup(
        module, "__raygen__caustics_radiance"
    );
}

void PipelineManager::createPipeline() {
    OptixProgramGroup program_groups[] = {
        raygen_prog_group,
        miss_prog_group,
        hitgroup_prog_group,
        shadow_miss_prog_group,
        shadow_hitgroup_prog_group,
        caustics_hitpoints_raygen,
        caustics_photons_raygen,
        caustics_radiance_raygen
    };

    OptixPipelineCompileOptions pipeline_compile_options = getDefaultPipelineCompileOptions();

    OptixPipelineLinkOptions pipeline_link_options = {};
    pipeline_link_options.maxTraceDepth = MAX_TRACE_DEPTH;  // Defined in OptiXData.h

    // Use OptiXContext to create pipeline
    pipeline = optix_context.createPipeline(
        pipeline_compile_options,
        pipeline_link_options,
        program_groups,
        8  // Updated to include caustics raygen programs
    );
}

void PipelineManager::setupShaderBindingTable(const SceneParameters& scene, OptixTraversableHandle gasHandle) {
    // Clean up old SBT records if they exist (for pipeline rebuild)
    if (sbt.raygenRecord) {
        optix_context.freeSBTRecord(sbt.raygenRecord);
        sbt.raygenRecord = 0;
    }
    if (sbt.missRecordBase) {
        optix_context.freeSBTRecord(sbt.missRecordBase);
        sbt.missRecordBase = 0;
    }
    if (sbt.hitgroupRecordBase) {
        optix_context.freeSBTRecord(sbt.hitgroupRecordBase);
        sbt.hitgroupRecordBase = 0;
    }

    // Ray generation record data
    const auto& camera = scene.getCamera();
    RayGenData rg_data;
    std::memcpy(rg_data.cam_eye, camera.eye, sizeof(float) * 3);
    std::memcpy(rg_data.camera_u, camera.u, sizeof(float) * 3);
    std::memcpy(rg_data.camera_v, camera.v, sizeof(float) * 3);
    std::memcpy(rg_data.camera_w, camera.w, sizeof(float) * 3);

    sbt.raygenRecord = optix_context.createRaygenSBTRecord(
        raygen_prog_group,
        rg_data
    );

    // Miss records: [0] = primary ray miss, [1] = shadow ray miss
    MissData ms_data;
    ms_data.r = OptiXConstants::DEFAULT_BG_R;
    ms_data.g = OptiXConstants::DEFAULT_BG_G;
    ms_data.b = OptiXConstants::DEFAULT_BG_B;

    // Allocate array for 2 miss records
    MissSbtRecord miss_records[2];
    optixSbtRecordPackHeader(miss_prog_group, &miss_records[0]);
    miss_records[0].data = ms_data;

    // Shadow miss has no data (just marks as not occluded)
    optixSbtRecordPackHeader(shadow_miss_prog_group, &miss_records[1]);
    miss_records[1].data = ms_data;  // Reuse same data (not used by shadow miss)

    CUdeviceptr d_miss_records;
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_miss_records), sizeof(miss_records)));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(d_miss_records),
        miss_records,
        sizeof(miss_records),
        cudaMemcpyHostToDevice
    ));
    sbt.missRecordBase = d_miss_records;
    sbt.missRecordStrideInBytes = sizeof(MissSbtRecord);
    sbt.missRecordCount = 2;

    // Hit group records: [0] = primary ray hitgroup, [1] = shadow ray hitgroup
    const auto& sphere = scene.getSphere();
    HitGroupData hg_data;
    std::memcpy(hg_data.sphere_center, sphere.center, sizeof(float) * 3);
    hg_data.sphere_radius = sphere.radius;

    // Allocate array for 2 hitgroup records
    HitGroupSbtRecord hitgroup_records[2];
    optixSbtRecordPackHeader(hitgroup_prog_group, &hitgroup_records[0]);
    hitgroup_records[0].data = hg_data;

    // Shadow hitgroup uses same geometry data
    optixSbtRecordPackHeader(shadow_hitgroup_prog_group, &hitgroup_records[1]);
    hitgroup_records[1].data = hg_data;

    CUdeviceptr d_hitgroup_records;
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_hitgroup_records), sizeof(hitgroup_records)));
    CUDA_CHECK(cudaMemcpy(
        reinterpret_cast<void*>(d_hitgroup_records),
        hitgroup_records,
        sizeof(hitgroup_records),
        cudaMemcpyHostToDevice
    ));
    sbt.hitgroupRecordBase = d_hitgroup_records;
    sbt.hitgroupRecordStrideInBytes = sizeof(HitGroupSbtRecord);
    sbt.hitgroupRecordCount = 2;
}

void PipelineManager::destroyProgramGroupIfExists(OptixProgramGroup& prog_group) {
    if (prog_group) {
        optix_context.destroyProgramGroup(prog_group);
        prog_group = nullptr;
    }
}

void PipelineManager::cleanup(bool includeCaustics) {
    if (pipeline) {
        optix_context.destroyPipeline(pipeline);
        pipeline = nullptr;
    }

    destroyProgramGroupIfExists(raygen_prog_group);
    destroyProgramGroupIfExists(miss_prog_group);
    destroyProgramGroupIfExists(shadow_miss_prog_group);
    destroyProgramGroupIfExists(hitgroup_prog_group);
    destroyProgramGroupIfExists(shadow_hitgroup_prog_group);

    if (includeCaustics) {
        destroyProgramGroupIfExists(caustics_hitpoints_raygen);
        destroyProgramGroupIfExists(caustics_photons_raygen);
        destroyProgramGroupIfExists(caustics_radiance_raygen);
    }

    if (module) {
        optix_context.destroyModule(module);
        module = nullptr;
    }

    // Clean up SBT buffers
    if (sbt.raygenRecord) {
        optix_context.freeSBTRecord(sbt.raygenRecord);
        sbt.raygenRecord = 0;
    }
    if (sbt.missRecordBase) {
        optix_context.freeSBTRecord(sbt.missRecordBase);
        sbt.missRecordBase = 0;
    }
    if (sbt.hitgroupRecordBase) {
        optix_context.freeSBTRecord(sbt.hitgroupRecordBase);
        sbt.hitgroupRecordBase = 0;
    }

    // Clean up params buffer
    if (d_params) {
        cudaFree(reinterpret_cast<void*>(d_params));
        d_params = 0;
    }
}

void PipelineManager::buildPipeline(const SceneParameters& scene, OptixTraversableHandle gasHandle) {
    // Clean up old pipeline resources if they exist (for pipeline rebuild)
    cleanup(false);

    loadPTXModules();
    createProgramGroups();
    createPipeline();
    setupShaderBindingTable(scene, gasHandle);

    // Allocate params buffer (only on first build)
    if (!d_params) {
        CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_params), sizeof(Params)));
    }
}

CUdeviceptr PipelineManager::createTempRaygenSBTRecord(OptixProgramGroup raygen, const SceneParameters& scene) {
    const auto& camera = scene.getCamera();
    RayGenData rg_data;
    std::memcpy(rg_data.cam_eye, camera.eye, sizeof(float) * 3);
    std::memcpy(rg_data.camera_u, camera.u, sizeof(float) * 3);
    std::memcpy(rg_data.camera_v, camera.v, sizeof(float) * 3);
    std::memcpy(rg_data.camera_w, camera.w, sizeof(float) * 3);

    return optix_context.createRaygenSBTRecord(raygen, rg_data);
}

void PipelineManager::freeTempRaygenSBTRecord(CUdeviceptr record) {
    optix_context.freeSBTRecord(record);
}
