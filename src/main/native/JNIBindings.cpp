#include <jni.h>
#include "include/OptiXWrapper.h"
#include <iostream>

// Global wrapper instance (will be managed per-object in Phase 2)
static OptiXWrapper* g_wrapper = nullptr;

extern "C" {

JNIEXPORT jboolean JNICALL Java_menger_optix_OptiXRenderer_initialize(JNIEnv* env, jobject obj) {
    try {
        if (g_wrapper == nullptr) {
            g_wrapper = new OptiXWrapper();
        }
        return g_wrapper->initialize() ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        std::cerr << "Error in initialize: " << e.what() << std::endl;
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setSphere(
    JNIEnv* env, jobject obj, jfloat x, jfloat y, jfloat z, jfloat radius) {
    try {
        if (g_wrapper != nullptr) {
            g_wrapper->setSphere(x, y, z, radius);
        }
    } catch (const std::exception& e) {
        std::cerr << "Error in setSphere: " << e.what() << std::endl;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setSphereColor(
    JNIEnv* env, jobject obj, jfloat r, jfloat g, jfloat b) {
    try {
        if (g_wrapper != nullptr) {
            g_wrapper->setSphereColor(r, g, b);
        }
    } catch (const std::exception& e) {
        std::cerr << "Error in setSphereColor: " << e.what() << std::endl;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setCamera(
    JNIEnv* env, jobject obj, jfloatArray eye, jfloatArray lookAt, jfloatArray up, jfloat fov) {
    try {
        if (g_wrapper != nullptr) {
            jfloat* eyeArr = env->GetFloatArrayElements(eye, nullptr);
            jfloat* lookAtArr = env->GetFloatArrayElements(lookAt, nullptr);
            jfloat* upArr = env->GetFloatArrayElements(up, nullptr);

            g_wrapper->setCamera(eyeArr, lookAtArr, upArr, fov);

            env->ReleaseFloatArrayElements(eye, eyeArr, 0);
            env->ReleaseFloatArrayElements(lookAt, lookAtArr, 0);
            env->ReleaseFloatArrayElements(up, upArr, 0);
        }
    } catch (const std::exception& e) {
        std::cerr << "Error in setCamera: " << e.what() << std::endl;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setLight(
    JNIEnv* env, jobject obj, jfloatArray direction, jfloat intensity) {
    try {
        if (g_wrapper != nullptr) {
            jfloat* dirArr = env->GetFloatArrayElements(direction, nullptr);
            g_wrapper->setLight(dirArr, intensity);
            env->ReleaseFloatArrayElements(direction, dirArr, 0);
        }
    } catch (const std::exception& e) {
        std::cerr << "Error in setLight: " << e.what() << std::endl;
    }
}

JNIEXPORT jbyteArray JNICALL Java_menger_optix_OptiXRenderer_render(
    JNIEnv* env, jobject obj, jint width, jint height) {
    try {
        if (g_wrapper == nullptr) {
            return nullptr;
        }

        int size = width * height * 4; // RGBA
        jbyteArray result = env->NewByteArray(size);
        jbyte* buffer = env->GetByteArrayElements(result, nullptr);

        g_wrapper->render(width, height, reinterpret_cast<unsigned char*>(buffer));

        env->ReleaseByteArrayElements(result, buffer, 0);
        return result;
    } catch (const std::exception& e) {
        std::cerr << "Error in render: " << e.what() << std::endl;
        return nullptr;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_dispose(JNIEnv* env, jobject obj) {
    try {
        if (g_wrapper != nullptr) {
            g_wrapper->dispose();
            delete g_wrapper;
            g_wrapper = nullptr;
        }
    } catch (const std::exception& e) {
        std::cerr << "Error in dispose: " << e.what() << std::endl;
    }
}

} // extern "C"
