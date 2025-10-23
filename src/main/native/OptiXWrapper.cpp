#include "include/OptiXWrapper.h"
#include <iostream>
#include <cstring>
#include <sstream>
#include <cmath>
#include <fstream>
#include <vector>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
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
            throw std::runtime_error(ss.str());                               \
        }                                                                     \
    } while(0)

// OptiX log callback
static void optixLogCallback(unsigned int level, const char* tag, const char* message, void* /*cbdata*/) {
    std::cerr << "[OptiX][" << level << "][" << tag << "]: " << message << std::endl;
}
#endif

// Implementation structure
struct OptiXWrapper::Impl {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
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
    OptixShaderBindingTable sbt = {};
    OptixTraversableHandle gas_handle = 0;

    bool pipeline_built = false;
#endif
    bool initialized = false;
};

OptiXWrapper::OptiXWrapper() : impl(std::make_unique<Impl>()) {
}

OptiXWrapper::~OptiXWrapper() {
    dispose();
}

bool OptiXWrapper::initialize() {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
    try {
        // Initialize CUDA runtime
        CUDA_CHECK(cudaFree(0));

        // Initialize OptiX
        OPTIX_CHECK(optixInit());

        // Create OptiX device context
        CUcontext cuCtx = 0;  // 0 = use current CUDA context
        OptixDeviceContextOptions options = {};
        options.logCallbackFunction = &optixLogCallback;
        options.logCallbackLevel = 3;  // Print Info, Warning and Error messages

        OPTIX_CHECK(optixDeviceContextCreate(cuCtx, &options, &impl->context));

        impl->initialized = true;
        std::cout << "[OptiX] Context initialized successfully" << std::endl;
        return true;

    } catch (const std::exception& e) {
        std::cerr << "[OptiX] Initialization failed: " << e.what() << std::endl;
        return false;
    }
#else
    // Stub implementation when CUDA/OptiX not available
    std::cout << "[OptiX] initialize() - stub (CUDA/OptiX not available)" << std::endl;
    impl->initialized = true;
    return true;
#endif
}

void OptiXWrapper::setSphere(float x, float y, float z, float radius) {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
    impl->sphere_center[0] = x;
    impl->sphere_center[1] = y;
    impl->sphere_center[2] = z;
    impl->sphere_radius = radius;

    std::cout << "[OptiX] Sphere configured: center=(" << x << "," << y << "," << z
              << ") radius=" << radius << std::endl;
#else
    // Stub implementation - no-op
    (void)x; (void)y; (void)z; (void)radius; // Suppress unused parameter warnings
#endif
}

void OptiXWrapper::setCamera(const float* eye, const float* lookAt, const float* up, float fov) {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
    // Store eye position and FOV
    std::memcpy(impl->camera_eye, eye, 3 * sizeof(float));
    impl->fov = fov;

    // Calculate W (view direction, negated for OptiX convention)
    float w[3];
    w[0] = lookAt[0] - eye[0];
    w[1] = lookAt[1] - eye[1];
    w[2] = lookAt[2] - eye[2];
    float len_w = std::sqrt(w[0]*w[0] + w[1]*w[1] + w[2]*w[2]);
    impl->camera_w[0] = -w[0] / len_w;
    impl->camera_w[1] = -w[1] / len_w;
    impl->camera_w[2] = -w[2] / len_w;

    // Calculate U (right = up × W)
    float u[3];
    u[0] = up[1]*impl->camera_w[2] - up[2]*impl->camera_w[1];
    u[1] = up[2]*impl->camera_w[0] - up[0]*impl->camera_w[2];
    u[2] = up[0]*impl->camera_w[1] - up[1]*impl->camera_w[0];
    float len_u = std::sqrt(u[0]*u[0] + u[1]*u[1] + u[2]*u[2]);
    u[0] /= len_u;
    u[1] /= len_u;
    u[2] /= len_u;

    // Calculate V (W × U)
    float v[3];
    v[0] = impl->camera_w[1]*u[2] - impl->camera_w[2]*u[1];
    v[1] = impl->camera_w[2]*u[0] - impl->camera_w[0]*u[2];
    v[2] = impl->camera_w[0]*u[1] - impl->camera_w[1]*u[0];

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

    std::cout << "[OptiX] Camera configured: eye=(" << eye[0] << "," << eye[1] << "," << eye[2]
              << ") fov=" << fov << " aspect=" << aspect_ratio << std::endl;
#else
    // Stub implementation - no-op
    (void)eye; (void)lookAt; (void)up; (void)fov; // Suppress unused parameter warnings
#endif
}

#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
// Helper function to read PTX file
static std::string readPTXFile(const std::string& filename) {
    std::ifstream file(filename, std::ios::binary | std::ios::ate);
    if (!file.is_open()) {
        throw std::runtime_error("Failed to open PTX file: " + filename);
    }

    std::streamsize size = file.tellg();
    file.seekg(0, std::ios::beg);

    std::string content(size, '\0');
    if (!file.read(&content[0], size)) {
        throw std::runtime_error("Failed to read PTX file: " + filename);
    }

    return content;
}

// Build OptiX pipeline (called once on first render)
void OptiXWrapper::buildPipeline() {
    std::cout << "[OptiX] Building pipeline..." << std::endl;

    // TODO: Full pipeline implementation
    // For now, this is a placeholder that will be completed
    // This requires:
    // 1. Build geometry acceleration structure for sphere
    // 2. Load and compile PTX modules
    // 3. Create program groups (raygen, miss, hit)
    // 4. Create pipeline
    // 5. Set up Shader Binding Table (SBT)

    // Allocate params buffer
    CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&impl->d_params), sizeof(Params)));

    std::cout << "[OptiX] Pipeline build complete (placeholder)" << std::endl;
}
#endif

void OptiXWrapper::setLight(const float* direction, float intensity) {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
    // Store and normalize light direction
    float len = std::sqrt(direction[0]*direction[0] +
                          direction[1]*direction[1] +
                          direction[2]*direction[2]);

    impl->light_direction[0] = direction[0] / len;
    impl->light_direction[1] = direction[1] / len;
    impl->light_direction[2] = direction[2] / len;
    impl->light_intensity = intensity;

    std::cout << "[OptiX] Light configured: direction=("
              << impl->light_direction[0] << ","
              << impl->light_direction[1] << ","
              << impl->light_direction[2] << ") intensity=" << intensity << std::endl;
#else
    // Stub implementation - no-op
    (void)direction; (void)intensity; // Suppress unused parameter warnings
#endif
}

void OptiXWrapper::render(int width, int height, unsigned char* output) {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
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

        std::cout << "[OptiX] Rendered " << width << "x" << height << " image" << std::endl;

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
#else
    // Stub implementation - return gray placeholder
    std::memset(output, 128, width * height * 4 - 1); // Fill RGB with 128
    for (int i = 3; i < width * height * 4; i += 4) {
        output[i] = 255; // Alpha channel
    }
#endif
}

void OptiXWrapper::dispose() {
    if (impl->initialized) {
#if defined(HAVE_CUDA) && defined(HAVE_OPTIX)
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
            std::cout << "[OptiX] Resources cleaned up" << std::endl;

        } catch (const std::exception& e) {
            std::cerr << "[OptiX] Cleanup error: " << e.what() << std::endl;
        }
#endif
        impl->initialized = false;
    }
}
