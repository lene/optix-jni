#include <jni.h>
#include "include/OptiXContext.h"
#include "include/OptiXErrorChecking.h"
#include <iostream>
#include <string>
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
    opts.numPayloadValues                 = 10;  // Primary: RGB+depth(4), Photon: flux+origin+dir+flags(10)
    opts.numAttributeValues               = 4;   // Normal x,y,z + radius from SDK intersection
    opts.exceptionFlags                   = OPTIX_EXCEPTION_FLAG_NONE;
    opts.pipelineLaunchParamsVariableName = "params";
    opts.usesPrimitiveTypeFlags           = OPTIX_PRIMITIVE_TYPE_FLAGS_CUSTOM |
                                            OPTIX_PRIMITIVE_TYPE_FLAGS_TRIANGLE;
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
        OptixProgramGroup pg = ctx->createRaygenProgramGroup(mod, entry);
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
        OptixProgramGroup pg = ctx->createMissProgramGroup(mod, entry);
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
        const char* is = env->GetStringUTFChars(isEntry, nullptr);
        OptixProgramGroup pg = ctx->createHitgroupProgramGroup(mod, ch, mod, is);
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
        OptixProgramGroup pg = ctx->createTriangleHitgroupProgramGroup(mod, ch);
        env->ReleaseStringUTFChars(chEntry, ch);
        return reinterpret_cast<jlong>(pg);
    } catch (const std::exception& e) {
        std::cerr << "[OptiXApi] createTriangleHitGroup: " << e.what() << std::endl;
        return 0L;
    }
}

JNIEXPORT void JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_destroyProgramGroup(
    JNIEnv* env, jobject obj, jlong contextHandle, jlong groupHandle) {
    auto* ctx = reinterpret_cast<OptiXContext*>(contextHandle);
    auto  pg  = reinterpret_cast<OptixProgramGroup>(groupHandle);
    if (ctx && pg) {
        try { ctx->destroyProgramGroup(pg); } catch (...) {}
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
        std::vector<OptixProgramGroup> groups(static_cast<size_t>(count));
        for (jsize i = 0; i < count; ++i) {
            groups[static_cast<size_t>(i)] = reinterpret_cast<OptixProgramGroup>(raw[i]);
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

} // extern "C"
