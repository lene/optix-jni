#ifndef OPTIX_CONTEXT_H
#define OPTIX_CONTEXT_H

#include <optix.h>
#include <cuda_runtime.h>
#include <string>

// Forward declarations for data structures
#include "OptiXData.h"

/**
 * Low-level OptiX API wrapper.
 *
 * This class provides a thin abstraction over OptiX operations,
 * managing OptiX resources and providing explicit control over
 * the rendering pipeline.
 *
 * All methods take explicit parameters rather than storing state,
 * making this layer stateless and easier to reason about.
 *
 * Responsibilities:
 * - OptiX context lifecycle
 * - Module/pipeline creation and destruction
 * - Geometry acceleration structure (GAS) building
 * - Shader binding table (SBT) management
 * - Ray tracing launch execution
 */
class OptiXContext {
public:
    OptiXContext();
    ~OptiXContext();

    // Context lifecycle
    bool initialize();
    void destroy();
    OptixDeviceContext getContext() const { return context_; }

    // Module management
    OptixModule createModuleFromPTX(
        const std::string& ptx_content,
        const OptixModuleCompileOptions& module_options,
        const OptixPipelineCompileOptions& pipeline_options
    );
    void destroyModule(OptixModule module);

    // Program group management
    OptixProgramGroup createRaygenProgramGroup(
        OptixModule module,
        const char* entry_function_name
    );
    OptixProgramGroup createMissProgramGroup(
        OptixModule module,
        const char* entry_function_name
    );
    OptixProgramGroup createHitgroupProgramGroup(
        OptixModule module_ch,
        const char* entry_ch,
        OptixModule module_is,
        const char* entry_is
    );
    void destroyProgramGroup(OptixProgramGroup program_group);

    // Pipeline management
    OptixPipeline createPipeline(
        const OptixPipelineCompileOptions& pipeline_options,
        const OptixPipelineLinkOptions& link_options,
        OptixProgramGroup* program_groups,
        unsigned int num_program_groups
    );
    void destroyPipeline(OptixPipeline pipeline);

    // Geometry acceleration structure (GAS)
    struct GASBuildResult {
        CUdeviceptr gas_buffer;
        OptixTraversableHandle handle;
    };

    GASBuildResult buildCustomPrimitiveGAS(
        const OptixAabb& aabb,
        const OptixAccelBuildOptions& build_options
    );
    void destroyGAS(CUdeviceptr gas_buffer);

    // Shader binding table (SBT) helpers
    CUdeviceptr createRaygenSBTRecord(OptixProgramGroup program_group, const RayGenData& data);
    CUdeviceptr createMissSBTRecord(OptixProgramGroup program_group, const MissData& data);
    CUdeviceptr createHitgroupSBTRecord(OptixProgramGroup program_group, const HitGroupData& data);
    void freeSBTRecord(CUdeviceptr record);

    // Launch
    void launch(
        OptixPipeline pipeline,
        const OptixShaderBindingTable& sbt,
        CUdeviceptr params_buffer,
        unsigned int width,
        unsigned int height
    );

    // Cache management utilities
    // Get the default OptiX cache directory path for current user
    static std::string getDefaultCachePath();

    // Clear the OptiX cache directory (removes all cached data)
    // Returns true if cache was cleared or didn't exist, false on error
    static bool clearCache();

    // Clear cache at a specific path
    static bool clearCache(const std::string& cache_path);

private:
    OptixDeviceContext context_;
    bool initialized_;

    // Disable copy
    OptiXContext(const OptiXContext&) = delete;
    OptiXContext& operator=(const OptiXContext&) = delete;
};

#endif // OPTIX_CONTEXT_H
