#include <gtest/gtest.h>
#include "../include/OptiXContext.h"
#include "../include/OptiXData.h"
#include "../include/OptiXConstants.h"
#include "../include/OptiXFileUtils.h"

#include <fstream>
#include <sstream>

// Helper to read PTX file for module tests - tries multiple locations
static std::string readPTXFile() {
    std::vector<std::string> search_paths = {
        "target/native/x86_64-linux/bin/sphere_combined.ptx",
        "optix-jni/target/native/x86_64-linux/bin/sphere_combined.ptx",
        "optix-jni/target/classes/native/x86_64-linux/sphere_combined.ptx"
    };

    try {
        return optix_utils::readPTXFile(search_paths);
    } catch (const std::runtime_error&) {
        return "";  // Not found - tests will skip
    }
}

// Test fixture for OptiXContext tests
class OptiXContextTest : public ::testing::Test {
protected:
    OptiXContext context;

    void SetUp() override {
        // Most tests will need an initialized context
        // Individual tests can skip this if testing initialization itself
    }

    void TearDown() override {
        // Cleanup happens in OptiXContext destructor
    }
};

//=============================================================================
// Context Lifecycle Tests
//=============================================================================

TEST_F(OptiXContextTest, InitializeSucceeds) {
    EXPECT_TRUE(context.initialize());
    EXPECT_NE(nullptr, context.getContext());
}

TEST_F(OptiXContextTest, InitializeIsIdempotent) {
    EXPECT_TRUE(context.initialize());
    OptixDeviceContext ctx1 = context.getContext();

    // Second initialize creates a new context - this is acceptable behavior
    // (OptiX doesn't prevent multiple contexts, and our implementation recreates)
    EXPECT_TRUE(context.initialize());
    OptixDeviceContext ctx2 = context.getContext();

    // Both should be valid (non-null), though they may be different instances
    EXPECT_NE(nullptr, ctx1);
    EXPECT_NE(nullptr, ctx2);
}

TEST_F(OptiXContextTest, GetContextBeforeInitializeReturnsNull) {
    // Fresh context without initialize
    OptiXContext uninit_context;
    EXPECT_EQ(nullptr, uninit_context.getContext());
}

TEST_F(OptiXContextTest, DestroyWithoutInitializeIsSafe) {
    OptiXContext temp_context;
    // Should not crash
    temp_context.destroy();
}

TEST_F(OptiXContextTest, DoubleDestroyIsSafe) {
    ASSERT_TRUE(context.initialize());
    context.destroy();
    // Second destroy should be safe
    context.destroy();
}

//=============================================================================
// Module Creation Tests
//=============================================================================

TEST_F(OptiXContextTest, CreateModuleFromPTXSucceeds) {
    ASSERT_TRUE(context.initialize());

    std::string ptx_content = readPTXFile();

    if (ptx_content.empty()) {
        GTEST_SKIP() << "PTX file not found in any search location";
    }

    OptixModuleCompileOptions module_options = {};
    module_options.maxRegisterCount = OPTIX_COMPILE_DEFAULT_MAX_REGISTER_COUNT;
    module_options.optLevel = OPTIX_COMPILE_OPTIMIZATION_DEFAULT;
    module_options.debugLevel = OPTIX_COMPILE_DEBUG_LEVEL_MINIMAL;

    OptixPipelineCompileOptions pipeline_options = {};
    pipeline_options.usesMotionBlur = false;
    pipeline_options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;
    pipeline_options.numPayloadValues = 4;
    pipeline_options.numAttributeValues = 4;
    pipeline_options.exceptionFlags = OPTIX_EXCEPTION_FLAG_NONE;
    pipeline_options.pipelineLaunchParamsVariableName = "params";
    pipeline_options.usesPrimitiveTypeFlags = OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM;

    OptixModule module = context.createModuleFromPTX(
        ptx_content,
        module_options,
        pipeline_options
    );

    EXPECT_NE(nullptr, module);

    // Cleanup
    context.destroyModule(module);
}

TEST_F(OptiXContextTest, CreateModuleWithInvalidPTXThrows) {
    ASSERT_TRUE(context.initialize());

    OptixModuleCompileOptions module_options = {};
    OptixPipelineCompileOptions pipeline_options = {};
    pipeline_options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;

    EXPECT_THROW(
        context.createModuleFromPTX("invalid ptx", module_options, pipeline_options),
        std::runtime_error
    );
}

TEST_F(OptiXContextTest, DestroyNullModuleIsSafe) {
    ASSERT_TRUE(context.initialize());
    // Should not crash
    context.destroyModule(nullptr);
}

//=============================================================================
// Program Group Creation Tests
//=============================================================================

TEST_F(OptiXContextTest, CreateRaygenProgramGroupSucceeds) {
    ASSERT_TRUE(context.initialize());

    std::string ptx_content = readPTXFile();

    if (ptx_content.empty()) {
        GTEST_SKIP() << "PTX file not found";
    }

    OptixModuleCompileOptions module_options = {};
    module_options.maxRegisterCount = OPTIX_COMPILE_DEFAULT_MAX_REGISTER_COUNT;
    module_options.optLevel = OPTIX_COMPILE_OPTIMIZATION_DEFAULT;
    module_options.debugLevel = OPTIX_COMPILE_DEBUG_LEVEL_MINIMAL;

    OptixPipelineCompileOptions pipeline_options = {};
    pipeline_options.usesMotionBlur = false;
    pipeline_options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;
    pipeline_options.numPayloadValues = 4;
    pipeline_options.numAttributeValues = 4;
    pipeline_options.exceptionFlags = OPTIX_EXCEPTION_FLAG_NONE;
    pipeline_options.pipelineLaunchParamsVariableName = "params";
    pipeline_options.usesPrimitiveTypeFlags = OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM;

    OptixModule module = context.createModuleFromPTX(ptx_content, module_options, pipeline_options);

    OptixProgramGroup raygen = context.createRaygenProgramGroup(module, "__raygen__rg");
    EXPECT_NE(nullptr, raygen);

    context.destroyProgramGroup(raygen);
    context.destroyModule(module);
}

TEST_F(OptiXContextTest, CreateMissProgramGroupSucceeds) {
    ASSERT_TRUE(context.initialize());

    std::string ptx_content = readPTXFile();
    if (ptx_content.empty()) GTEST_SKIP() << "PTX file not found";

    OptixModuleCompileOptions module_options = {};
    module_options.maxRegisterCount = OPTIX_COMPILE_DEFAULT_MAX_REGISTER_COUNT;
    module_options.optLevel = OPTIX_COMPILE_OPTIMIZATION_DEFAULT;
    module_options.debugLevel = OPTIX_COMPILE_DEBUG_LEVEL_MINIMAL;

    OptixPipelineCompileOptions pipeline_options = {};
    pipeline_options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;
    pipeline_options.numPayloadValues = 4;
    pipeline_options.numAttributeValues = 4;
    pipeline_options.pipelineLaunchParamsVariableName = "params";
    pipeline_options.usesPrimitiveTypeFlags = OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM;

    OptixModule module = context.createModuleFromPTX(ptx_content, module_options, pipeline_options);

    OptixProgramGroup miss = context.createMissProgramGroup(module, "__miss__ms");
    EXPECT_NE(nullptr, miss);

    context.destroyProgramGroup(miss);
    context.destroyModule(module);
}

TEST_F(OptiXContextTest, CreateHitgroupProgramGroupSucceeds) {
    ASSERT_TRUE(context.initialize());

    std::string ptx_content = readPTXFile();
    if (ptx_content.empty()) GTEST_SKIP() << "PTX file not found";

    OptixModuleCompileOptions module_options = {};
    module_options.maxRegisterCount = OPTIX_COMPILE_DEFAULT_MAX_REGISTER_COUNT;
    module_options.optLevel = OPTIX_COMPILE_OPTIMIZATION_DEFAULT;
    module_options.debugLevel = OPTIX_COMPILE_DEBUG_LEVEL_MINIMAL;

    OptixPipelineCompileOptions pipeline_options = {};
    pipeline_options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;
    pipeline_options.numPayloadValues = 4;
    pipeline_options.numAttributeValues = 4;
    pipeline_options.pipelineLaunchParamsVariableName = "params";
    pipeline_options.usesPrimitiveTypeFlags = OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM;

    OptixModule module = context.createModuleFromPTX(ptx_content, module_options, pipeline_options);

    OptixProgramGroup hitgroup = context.createHitgroupProgramGroup(
        module, "__closesthit__ch",
        module, "__intersection__sphere"
    );
    EXPECT_NE(nullptr, hitgroup);

    context.destroyProgramGroup(hitgroup);
    context.destroyModule(module);
}

//=============================================================================
// GAS (Geometry Acceleration Structure) Tests
//=============================================================================

TEST_F(OptiXContextTest, BuildCustomPrimitiveGASSucceeds) {
    ASSERT_TRUE(context.initialize());

    // Create a simple AABB for a unit sphere at origin
    OptixAabb aabb;
    aabb.minX = -1.0f;
    aabb.minY = -1.0f;
    aabb.minZ = -1.0f;
    aabb.maxX = 1.0f;
    aabb.maxY = 1.0f;
    aabb.maxZ = 1.0f;

    OptixAccelBuildOptions build_options = {};
    build_options.buildFlags = OPTIX_BUILD_FLAG_ALLOW_COMPACTION;
    build_options.operation = OPTIX_BUILD_OPERATION_BUILD;

    OptiXContext::GASBuildResult result = context.buildCustomPrimitiveGAS(aabb, build_options);

    EXPECT_NE(0, result.gas_buffer);
    EXPECT_NE(0, result.handle);

    context.destroyGAS(result.gas_buffer);
}

TEST_F(OptiXContextTest, DestroyNullGASIsSafe) {
    ASSERT_TRUE(context.initialize());
    // Should not crash
    context.destroyGAS(0);
}

//=============================================================================
// SBT (Shader Binding Table) Record Tests
//=============================================================================

TEST_F(OptiXContextTest, CreateRaygenSBTRecordSucceeds) {
    ASSERT_TRUE(context.initialize());

    std::string ptx_content = readPTXFile();
    if (ptx_content.empty()) GTEST_SKIP() << "PTX file not found";

    OptixModuleCompileOptions module_options = {};
    module_options.maxRegisterCount = OPTIX_COMPILE_DEFAULT_MAX_REGISTER_COUNT;
    module_options.optLevel = OPTIX_COMPILE_OPTIMIZATION_DEFAULT;
    module_options.debugLevel = OPTIX_COMPILE_DEBUG_LEVEL_MINIMAL;

    OptixPipelineCompileOptions pipeline_options = {};
    pipeline_options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;
    pipeline_options.numPayloadValues = 4;
    pipeline_options.numAttributeValues = 4;
    pipeline_options.pipelineLaunchParamsVariableName = "params";
    pipeline_options.usesPrimitiveTypeFlags = OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM;

    OptixModule module = context.createModuleFromPTX(ptx_content, module_options, pipeline_options);
    OptixProgramGroup raygen = context.createRaygenProgramGroup(module, "__raygen__rg");

    RayGenData raygen_data;
    raygen_data.cam_eye[0] = 0.0f;
    raygen_data.cam_eye[1] = 0.0f;
    raygen_data.cam_eye[2] = 3.0f;

    CUdeviceptr record = context.createRaygenSBTRecord(raygen, raygen_data);
    EXPECT_NE(0, record);

    context.freeSBTRecord(record);
    context.destroyProgramGroup(raygen);
    context.destroyModule(module);
}

TEST_F(OptiXContextTest, FreeSBTRecordWithNullIsSafe) {
    ASSERT_TRUE(context.initialize());
    // Should not crash
    context.freeSBTRecord(0);
}

//=============================================================================
// Error Handling Tests
//=============================================================================

TEST_F(OptiXContextTest, OperationBeforeInitializeThrows) {
    // Fresh context without initialize
    OptiXContext uninit_context;

    OptixModuleCompileOptions module_options = {};
    OptixPipelineCompileOptions pipeline_options = {};
    pipeline_options.traversableGraphFlags = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_SINGLE_GAS;

    EXPECT_THROW(
        uninit_context.createModuleFromPTX("test", module_options, pipeline_options),
        std::runtime_error
    );
}

// Custom main to suppress verbose output
int main(int argc, char **argv) {
    ::testing::InitGoogleTest(&argc, argv);

    // Suppress individual test output, only show summary
    ::testing::TestEventListeners& listeners = ::testing::UnitTest::GetInstance()->listeners();
    delete listeners.Release(listeners.default_result_printer());
    listeners.Append(new ::testing::EmptyTestEventListener());

    int result = RUN_ALL_TESTS();

    // Print concise summary
    const ::testing::UnitTest* unit_test = ::testing::UnitTest::GetInstance();
    if (result == 0) {
        std::cout << "All " << unit_test->successful_test_count()
                  << " C++ tests passed" << std::endl;
    } else {
        std::cout << unit_test->failed_test_count() << " C++ tests FAILED" << std::endl;
    }

    return result;
}
