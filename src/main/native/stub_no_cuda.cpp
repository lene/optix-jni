#include <jni.h>

// Stub translation unit — produced when CUDA is absent (Scala-only CI mode).
// The resulting .so satisfies the CMake/sbt-jni build without GPU support.

extern "C" {

JNIEXPORT jlong JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_createDenoiser(
    JNIEnv*, jobject, jlong, jboolean, jboolean) {
    return 0L;
}

JNIEXPORT jfloatArray JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_denoiseFloat4Native(
    JNIEnv* env, jobject, jlong, jint, jint, jfloatArray, jfloatArray, jfloatArray) {
    return env->NewFloatArray(0);
}

JNIEXPORT void JNICALL Java_io_github_lene_optix_api_NativeOptiXApi_destroyDenoiser(
    JNIEnv*, jobject, jlong) {
}

JNIEXPORT void JNICALL Java_io_github_lene_optix_OptiXRenderer_setDenoisingEnabled(
    JNIEnv*, jobject, jboolean) {
}

JNIEXPORT jboolean JNICALL Java_io_github_lene_optix_OptiXRenderer_isDenoisingEnabled(
    JNIEnv*, jobject) {
    return JNI_FALSE;
}

} // extern "C"
