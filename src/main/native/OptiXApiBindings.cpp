#include <jni.h>
#include "include/CudaBuffer.h"
#include "include/DenoiserManager.h"
#include "include/OptiXContext.h"
#include "include/OptiXErrorChecking.h"
#include <cstdint>
#include <iostream>
#include <string>
#include <unordered_map>
#include <vector>

/**
 * Thin JNI bindings for NativeOptiXApi (io.github.lene.optix.api).
 *
 * Exposes OptiXContext methods as independent JNI functions keyed on Long handles.
 * Each OptiXContext* is stored as a jlong; callers are responsible for lifecycle.
 * OptiX handle types (OptixModule, OptixProgramGroup, OptixPipeline) are opaque
 * pointers and fit in a jlong.
 */

extern "C" {

static std::unordered_map<OptixProgramGroup, OptixModule> curve_builtin_modules;

static jfloatArray emptyFloatArray(JNIEnv* env) {
    return env->NewFloatArray(0);
}

static bool hasExpectedFloatLength(JNIEnv* env, jfloatArray array, jsize expected) {
    return array != nullptr && env->GetArrayLength(array) == expected;
}

// ---- Default compile/pipeline options used for createModuleFromPTX and createPipeline ----

static OptixModuleCompileOptions defaultModuleCompileOptions() {
    OptixModuleCompileOptions opts = {};
    opts.optLevel   = OPTIX_COMPILE_OPTIMIZATION_DEFAULT;
    opts.debugLevel = OPTIX_COMPILE_DEBUG_LEVEL_NONE;
    return opts;
}

static OptixPipelineCompileOptions defaultPipelineCompileOptions() {
    OptixPipelineCompileOptions opts = {};
    opts.usesMotionBlur                   = 0;
    opts.traversableGraphFlags            = OPTIX_TRAVERSABLE_GRAPH_FLAG_ALLOW_ANY;
    // Primary: RGB+depth(4), Photon: flux+origin+dir+flags(10)
    opts.numPayloadValues                 = 11;
    opts.numAttributeValues               = 4;   // Normal x,y,z + radius from SDK intersection
    opts.exceptionFlags                   = OPTIX_EXCEPTION_FLAG_NONE;
    opts.pipelineLaunchParamsVariableName = "params";
    opts.usesPrimitiveTypeFlags           = OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM |
                                            OPTIX_PRIMITIVE_TYPE_FLAGS_TRIANGLE |
                                            OPTIX_PRIMITIVE_TYPE_FLAGS_ROUND_CUBIC_BSPLINE;
    return opts;
}

static OptixPipelineLinkOptions defaultPipelineLinkOptions(int maxTraceDepth) {
    OptixPipelineLinkOptions opts = {};
    opts.maxTraceDepth = static_cast<unsigned int>(maxTraceDepth);
    return opts;
}

// ---- Context lifecycle ----

JNIEXPORT jlong JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_createContext(
    JNIEnv* env, jobject obj) {
    try {
        auto* ctx = new OptiXContext();
        if (!ctx->initialize()) {
            delete ctx;
            std::cerr << "[OptiXApi] Context initialization failed" << std::endl;
            return 0L;
        }
        return reinterpret_cast<jlong>(ctx);
    } catch (const std::exception& e) {
        std::cerr << "[OptiXApi] createContext: " << e.what() << std::endl;
        return 0L;
    }
}

JNIEXPORT void JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_destroyContext(
    JNIEnv* env, jobject obj, jlong contextHandle) {
    auto* ctx = reinterpret_cast<OptiXContext*>(contextHandle);
    if (ctx) {
        try { ctx->destroy(); } catch (...) {}
        delete ctx;
    }
}

// ---- Module lifecycle ----

JNIEXPORT jlong JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_createModuleFromPTX(
    JNIEnv* env, jobject obj, jlong contextHandle, jbyteArray ptxBytes) {
    auto* ctx = reinterpret_cast<OptiXContext*>(contextHandle);
    if (!ctx) return 0L;
    try {
        jsize len = env->GetArrayLength(ptxBytes);
        jbyte* raw = env->GetByteArrayElements(ptxBytes, nullptr);
        if (!raw) return 0L;
        std::string ptx(reinterpret_cast<const char*>(raw), static_cast<size_t>(len));
        env->ReleaseByteArrayElements(ptxBytes, raw, JNI_ABORT);

        OptixModuleCompileOptions  mco = defaultModuleCompileOptions();
        OptixPipelineCompileOptions pco = defaultPipelineCompileOptions();
        OptixModule mod = ctx->createModuleFromPTX(ptx, mco, pco);
        return reinterpret_cast<jlong>(mod);
    } catch (const std::exception& e) {
        std::cerr << "[OptiXApi] createModuleFromPTX: " << e.what() << std::endl;
        return 0L;
    }
}

JNIEXPORT void JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_destroyModule(
    JNIEnv* env, jobject obj, jlong contextHandle, jlong moduleHandle) {
    auto* ctx = reinterpret_cast<OptiXContext*>(contextHandle);
    auto  mod = reinterpret_cast<OptixModule>(moduleHandle);
    if (ctx && mod) {
        try { ctx->destroyModule(mod); } catch (...) {}
    }
}

// ---- Program group lifecycle ----

JNIEXPORT jlong JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_createRaygenGroup(
    JNIEnv* env, jobject obj, jlong contextHandle, jlong moduleHandle, jstring entryPoint) {
    auto* ctx = reinterpret_cast<OptiXContext*>(contextHandle);
    auto  mod = reinterpret_cast<OptixModule>(moduleHandle);
    if (!ctx || !mod) return 0L;
    try {
        const char* entry = env->GetStringUTFChars(entryPoint, nullptr);
        if (!entry) return 0L;
        OptixProgramGroup pg = nullptr;
        try { pg = ctx->createRaygenProgramGroup(mod, entry); }
        catch (...) { env->ReleaseStringUTFChars(entryPoint, entry); throw; }
        env->ReleaseStringUTFChars(entryPoint, entry);
        return reinterpret_cast<jlong>(pg);
    } catch (const std::exception& e) {
        std::cerr << "[OptiXApi] createRaygenGroup: " << e.what() << std::endl;
        return 0L;
    }
}

JNIEXPORT jlong JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_createMissGroup(
    JNIEnv* env, jobject obj, jlong contextHandle, jlong moduleHandle, jstring entryPoint) {
    auto* ctx = reinterpret_cast<OptiXContext*>(contextHandle);
    auto  mod = reinterpret_cast<OptixModule>(moduleHandle);
    if (!ctx || !mod) return 0L;
    try {
        const char* entry = env->GetStringUTFChars(entryPoint, nullptr);
        if (!entry) return 0L;
        OptixProgramGroup pg = nullptr;
        try { pg = ctx->createMissProgramGroup(mod, entry); }
        catch (...) { env->ReleaseStringUTFChars(entryPoint, entry); throw; }
        env->ReleaseStringUTFChars(entryPoint, entry);
        return reinterpret_cast<jlong>(pg);
    } catch (const std::exception& e) {
        std::cerr << "[OptiXApi] createMissGroup: " << e.what() << std::endl;
        return 0L;
    }
}

// Custom-primitive hitgroup with both closesthit and intersection shaders.
JNIEXPORT jlong JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_createHitGroup(
    JNIEnv* env, jobject obj, jlong contextHandle, jlong moduleHandle,
    jstring chEntry, jstring isEntry) {
    auto* ctx = reinterpret_cast<OptiXContext*>(contextHandle);
    auto  mod = reinterpret_cast<OptixModule>(moduleHandle);
    if (!ctx || !mod) return 0L;
    try {
        const char* ch = env->GetStringUTFChars(chEntry, nullptr);
        if (!ch) return 0L;
        const char* is = env->GetStringUTFChars(isEntry, nullptr);
        if (!is) { env->ReleaseStringUTFChars(chEntry, ch); return 0L; }
        OptixProgramGroup pg = nullptr;
        try { pg = ctx->createHitgroupProgramGroup(mod, ch, mod, is); }
        catch (...) {
            env->ReleaseStringUTFChars(chEntry, ch);
            env->ReleaseStringUTFChars(isEntry, is);
            throw;
        }
        env->ReleaseStringUTFChars(chEntry, ch);
        env->ReleaseStringUTFChars(isEntry, is);
        return reinterpret_cast<jlong>(pg);
    } catch (const std::exception& e) {
        std::cerr << "[OptiXApi] createHitGroup: " << e.what() << std::endl;
        return 0L;
    }
}

// Triangle hitgroup (built-in intersection — no IS shader needed).
JNIEXPORT jlong JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_createTriangleHitGroup(
    JNIEnv* env, jobject obj, jlong contextHandle, jlong moduleHandle, jstring chEntry) {
    auto* ctx = reinterpret_cast<OptiXContext*>(contextHandle);
    auto  mod = reinterpret_cast<OptixModule>(moduleHandle);
    if (!ctx || !mod) return 0L;
    try {
        const char* ch = env->GetStringUTFChars(chEntry, nullptr);
        if (!ch) return 0L;
        OptixProgramGroup pg = nullptr;
        try { pg = ctx->createTriangleHitgroupProgramGroup(mod, ch); }
        catch (...) { env->ReleaseStringUTFChars(chEntry, ch); throw; }
        env->ReleaseStringUTFChars(chEntry, ch);
        return reinterpret_cast<jlong>(pg);
    } catch (const std::exception& e) {
        std::cerr << "[OptiXApi] createTriangleHitGroup: " << e.what() << std::endl;
        return 0L;
    }
}

// Curve hitgroup (built-in round cubic B-spline intersection).
JNIEXPORT jlong JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_createCurveHitGroup(
    JNIEnv* env, jobject obj, jlong contextHandle, jlong moduleHandle, jstring chEntry) {
    auto* ctx = reinterpret_cast<OptiXContext*>(contextHandle);
    auto  mod = reinterpret_cast<OptixModule>(moduleHandle);
    if (!ctx || !mod) return 0L;
    try {
        const char* ch = env->GetStringUTFChars(chEntry, nullptr);
        if (!ch) return 0L;
        OptixModule curve_module = nullptr;
        OptixProgramGroup pg = nullptr;
        try {
            pg = ctx->createCurveHitgroupProgramGroup(mod, ch, curve_module);
        } catch (...) {
            env->ReleaseStringUTFChars(chEntry, ch);
            if (curve_module) {
                ctx->destroyModule(curve_module);
            }
            throw;
        }
        env->ReleaseStringUTFChars(chEntry, ch);
        if (pg && curve_module) {
            curve_builtin_modules[pg] = curve_module;
        }
        return reinterpret_cast<jlong>(pg);
    } catch (const std::exception& e) {
        std::cerr << "[OptiXApi] createCurveHitGroup: " << e.what() << std::endl;
        return 0L;
    }
}

JNIEXPORT void JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_destroyProgramGroup(
    JNIEnv* env, jobject obj, jlong contextHandle, jlong groupHandle) {
    auto* ctx = reinterpret_cast<OptiXContext*>(contextHandle);
    auto  pg  = reinterpret_cast<OptixProgramGroup>(groupHandle);
    if (ctx && pg) {
        OptixModule curve_module = nullptr;
        const auto curve_module_entry = curve_builtin_modules.find(pg);
        if (curve_module_entry != curve_builtin_modules.end()) {
            curve_module = curve_module_entry->second;
            curve_builtin_modules.erase(curve_module_entry);
        }
        try { ctx->destroyProgramGroup(pg); } catch (...) {}
        if (curve_module) {
            try { ctx->destroyModule(curve_module); } catch (...) {}
        }
    }
}

// ---- Pipeline lifecycle ----

JNIEXPORT jlong JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_createPipeline(
    JNIEnv* env, jobject obj, jlong contextHandle, jlongArray groupHandles, jint maxTraceDepth) {
    auto* ctx = reinterpret_cast<OptiXContext*>(contextHandle);
    if (!ctx) return 0L;
    try {
        jsize count = env->GetArrayLength(groupHandles);
        jlong* raw = env->GetLongArrayElements(groupHandles, nullptr);
        if (!raw) return 0L;
        std::vector<OptixProgramGroup> groups(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            groups[static_cast<size_t>(i)] = reinterpret_cast<OptixProgramGroup>(raw[i]);
            if (!groups[static_cast<size_t>(i)]) {
                env->ReleaseLongArrayElements(groupHandles, raw, JNI_ABORT);
                std::cerr << "[OptiXApi] createPipeline: null group handle at index " << i << std::endl;
                return 0L;
            }
        }
        env->ReleaseLongArrayElements(groupHandles, raw, JNI_ABORT);

        OptixPipelineCompileOptions pco = defaultPipelineCompileOptions();
        OptixPipelineLinkOptions    plo = defaultPipelineLinkOptions(maxTraceDepth);
        OptixPipeline pipeline = ctx->createPipeline(pco, plo, groups.data(), static_cast<unsigned int>(count));
        return reinterpret_cast<jlong>(pipeline);
    } catch (const std::exception& e) {
        std::cerr << "[OptiXApi] createPipeline: " << e.what() << std::endl;
        return 0L;
    }
}

JNIEXPORT void JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_destroyPipeline(
    JNIEnv* env, jobject obj, jlong contextHandle, jlong pipelineHandle) {
    auto* ctx      = reinterpret_cast<OptiXContext*>(contextHandle);
    auto  pipeline = reinterpret_cast<OptixPipeline>(pipelineHandle);
    if (ctx && pipeline) {
        try { ctx->destroyPipeline(pipeline); } catch (...) {}
    }
}

// ---- Denoiser lifecycle ----

JNIEXPORT jlong JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_createDenoiser(
    JNIEnv* env, jobject obj, jlong contextHandle, jboolean guideAlbedo, jboolean guideNormal) {
    const auto* ctx = reinterpret_cast<const OptiXContext*>(contextHandle);
    if (!ctx) return 0L;
    try {
        auto* denoiser = new DenoiserManager(
            ctx->getContext(),
            guideAlbedo == JNI_TRUE,
            guideNormal == JNI_TRUE
        );
        return reinterpret_cast<jlong>(denoiser);
    } catch (const std::exception& e) {
        std::cerr << "[OptiXApi] createDenoiser: " << e.what() << std::endl;
        return 0L;
    }
}

JNIEXPORT jfloatArray JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_denoiseFloat4Native(
    JNIEnv* env,
    jobject obj,
    jlong denoiserHandle,
    jint width,
    jint height,
    jfloatArray colorRgba,
    jfloatArray albedoRgba,
    jfloatArray normalRgba) {
    auto* denoiser = reinterpret_cast<DenoiserManager*>(denoiserHandle);
    if (!denoiser || width <= 0 || height <= 0) {
        return emptyFloatArray(env);
    }

    const long long float_count =
        static_cast<long long>(width) * static_cast<long long>(height) * 4LL;
    if (float_count <= 0 || float_count > static_cast<long long>(INT32_MAX)) {
        return emptyFloatArray(env);
    }
    const jsize expected_length = static_cast<jsize>(float_count);

    if (!hasExpectedFloatLength(env, colorRgba, expected_length)) {
        return emptyFloatArray(env);
    }
    if (denoiser->usesAlbedoGuide()
            && !hasExpectedFloatLength(env, albedoRgba, expected_length)) {
        return emptyFloatArray(env);
    }
    if (denoiser->usesNormalGuide()
            && !hasExpectedFloatLength(env, normalRgba, expected_length)) {
        return emptyFloatArray(env);
    }

    try {
        CudaBuffer<float> color;
        CudaBuffer<float> output;
        CudaBuffer<float> albedo;
        CudaBuffer<float> normal;
        const size_t count = static_cast<size_t>(expected_length);

        color.allocate(count);
        output.allocate(count);
        if (denoiser->usesAlbedoGuide()) {
            albedo.allocate(count);
        }
        if (denoiser->usesNormalGuide()) {
            normal.allocate(count);
        }

        std::vector<float> host_color(count);
        env->GetFloatArrayRegion(colorRgba, 0, expected_length, host_color.data());
        color.uploadFrom(host_color.data(), count);

        if (denoiser->usesAlbedoGuide()) {
            std::vector<float> host_albedo(count);
            env->GetFloatArrayRegion(albedoRgba, 0, expected_length, host_albedo.data());
            albedo.uploadFrom(host_albedo.data(), count);
        }

        if (denoiser->usesNormalGuide()) {
            std::vector<float> host_normal(count);
            env->GetFloatArrayRegion(normalRgba, 0, expected_length, host_normal.data());
            normal.uploadFrom(host_normal.data(), count);
        }

        const bool ok = denoiser->denoiseFloat4(
            width,
            height,
            color.get(),
            output.get(),
            denoiser->usesAlbedoGuide() ? albedo.get() : 0,
            denoiser->usesNormalGuide() ? normal.get() : 0
        );
        if (!ok) {
            return emptyFloatArray(env);
        }

        std::vector<float> host_output(count);
        output.downloadTo(host_output.data(), count);

        jfloatArray result = env->NewFloatArray(expected_length);
        if (result == nullptr) {
            return emptyFloatArray(env);
        }
        env->SetFloatArrayRegion(result, 0, expected_length, host_output.data());
        return result;
    } catch (const std::exception& e) {
        std::cerr << "[OptiXApi] denoiseFloat4: " << e.what() << std::endl;
        return emptyFloatArray(env);
    }
}

JNIEXPORT void JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_destroyDenoiser(
    JNIEnv* env, jobject obj, jlong denoiserHandle) {
    auto* denoiser = reinterpret_cast<DenoiserManager*>(denoiserHandle);
    if (denoiser) {
        delete denoiser;
    }
}

} // extern "C"
