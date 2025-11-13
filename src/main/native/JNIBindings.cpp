#include <jni.h>
#include "include/OptiXWrapper.h"
#include <iostream>

/**
 * @file JNIBindings.cpp
 * @brief JNI bindings for OptiXRenderer Scala class
 *
 * Each OptiXRenderer instance maintains its own native OptiXWrapper pointer,
 * stored in the `nativeHandle` field as a jlong. This enables proper
 * multi-instance support without global state.
 *
 * Handle management:
 * - nativeHandle == 0: Not initialized
 * - nativeHandle != 0: Points to valid OptiXWrapper instance
 */

extern "C" {

/**
 * Get the OptiXWrapper instance from the Java object's nativeHandle field.
 * Returns nullptr if not initialized.
 */
static OptiXWrapper* getWrapper(JNIEnv* env, jobject obj) {
    jclass cls = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(cls, "nativeHandle", "J");
    if (fid == nullptr) {
        std::cerr << "[JNI] Failed to get nativeHandle field" << std::endl;
        return nullptr;
    }
    jlong handle = env->GetLongField(obj, fid);
    return reinterpret_cast<OptiXWrapper*>(handle);
}

/**
 * Set the OptiXWrapper instance pointer in the Java object's nativeHandle field.
 */
static void setWrapper(JNIEnv* env, jobject obj, OptiXWrapper* wrapper) {
    jclass cls = env->GetObjectClass(obj);
    jfieldID fid = env->GetFieldID(cls, "nativeHandle", "J");
    if (fid == nullptr) {
        std::cerr << "[JNI] Failed to get nativeHandle field" << std::endl;
        return;
    }
    env->SetLongField(obj, fid, reinterpret_cast<jlong>(wrapper));
}

JNIEXPORT jboolean JNICALL Java_menger_optix_OptiXRenderer_initializeNative(JNIEnv* env, jobject obj) {
    try {
        // Check if already initialized (defensive check - Scala layer should prevent this)
        OptiXWrapper* existing = getWrapper(env, obj);
        if (existing != nullptr) {
            // Already initialized at native level - this shouldn't happen if Scala
            // idempotence layer is working correctly, but we handle it gracefully
            return JNI_TRUE;
        }

        // Create new wrapper instance for this Java object
        OptiXWrapper* wrapper = new OptiXWrapper();
        bool success = wrapper->initialize();

        if (success) {
            setWrapper(env, obj, wrapper);
            return JNI_TRUE;
        } else {
            // Initialization failed, clean up
            delete wrapper;
            return JNI_FALSE;
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in initializeNative: " << e.what() << std::endl;
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setSphere(
    JNIEnv* env, jobject obj, jfloat x, jfloat y, jfloat z, jfloat radius) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            wrapper->setSphere(x, y, z, radius);
        } else {
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in setSphere: " << e.what() << std::endl;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setSphereColor(
    JNIEnv* env, jobject obj, jfloat r, jfloat g, jfloat b, jfloat a) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            wrapper->setSphereColor(r, g, b, a);
        } else {
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in setSphereColor: " << e.what() << std::endl;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setIOR(
    JNIEnv* env, jobject obj, jfloat ior) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            wrapper->setIOR(ior);
        } else {
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in setIOR: " << e.what() << std::endl;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setScale(
    JNIEnv* env, jobject obj, jfloat scale) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            wrapper->setScale(scale);
        } else {
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in setScale: " << e.what() << std::endl;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setCamera(
    JNIEnv* env, jobject obj, jfloatArray eye, jfloatArray lookAt, jfloatArray up, jfloat fov) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            jfloat* eyeArr = env->GetFloatArrayElements(eye, nullptr);
            jfloat* lookAtArr = env->GetFloatArrayElements(lookAt, nullptr);
            jfloat* upArr = env->GetFloatArrayElements(up, nullptr);

            wrapper->setCamera(eyeArr, lookAtArr, upArr, fov);

            env->ReleaseFloatArrayElements(eye, eyeArr, 0);
            env->ReleaseFloatArrayElements(lookAt, lookAtArr, 0);
            env->ReleaseFloatArrayElements(up, upArr, 0);
        } else {
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in setCamera: " << e.what() << std::endl;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_updateImageDimensions(
    JNIEnv* env, jobject obj, jint width, jint height) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            wrapper->updateImageDimensions(width, height);
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in updateImageDimensions: " << e.what() << std::endl;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setLight(
    JNIEnv* env, jobject obj, jfloatArray direction, jfloat intensity) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            jfloat* dirArr = env->GetFloatArrayElements(direction, nullptr);
            wrapper->setLight(dirArr, intensity);
            env->ReleaseFloatArrayElements(direction, dirArr, 0);
        } else {
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in setLight: " << e.what() << std::endl;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setPlane(
    JNIEnv* env, jobject obj, jint axis, jboolean positive, jfloat value) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            wrapper->setPlane(axis, positive == JNI_TRUE, value);
        } else {
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in setPlane: " << e.what() << std::endl;
    }
}

JNIEXPORT jobject JNICALL Java_menger_optix_OptiXRenderer_renderWithStats(
    JNIEnv* env, jobject obj, jint width, jint height) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper == nullptr) {
            return nullptr;
        }

        int size = width * height * 4; // RGBA
        jbyteArray imageArray = env->NewByteArray(size);
        if (imageArray == nullptr) {
            std::cerr << "[JNI] Failed to allocate byte array for render output" << std::endl;
            return nullptr;
        }

        jbyte* buffer = env->GetByteArrayElements(imageArray, nullptr);
        if (buffer == nullptr) {
            std::cerr << "[JNI] Failed to get byte array elements" << std::endl;
            return nullptr;
        }

        // Get stats from render
        RayStats stats;
        wrapper->render(width, height, reinterpret_cast<unsigned char*>(buffer), &stats);

        env->ReleaseByteArrayElements(imageArray, buffer, 0);

        // Create RenderResult object (image + stats)
        jclass resultClass = env->FindClass("menger/optix/RenderResult");
        if (resultClass == nullptr) {
            std::cerr << "[JNI] Failed to find RenderResult class" << std::endl;
            return nullptr;
        }

        jmethodID constructor = env->GetMethodID(resultClass, "<init>", "([BJJJJII)V");
        if (constructor == nullptr) {
            std::cerr << "[JNI] Failed to find RenderResult constructor" << std::endl;
            return nullptr;
        }

        jobject result = env->NewObject(resultClass, constructor,
            imageArray,
            static_cast<jlong>(stats.total_rays),
            static_cast<jlong>(stats.primary_rays),
            static_cast<jlong>(stats.reflected_rays),
            static_cast<jlong>(stats.refracted_rays),
            static_cast<jint>(stats.max_depth_reached),
            static_cast<jint>(stats.min_depth_reached)
        );

        return result;
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in renderWithStats: " << e.what() << std::endl;
        return nullptr;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_disposeNative(JNIEnv* env, jobject obj) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            wrapper->dispose();
            delete wrapper;
            setWrapper(env, obj, nullptr); // Clear the handle
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in dispose: " << e.what() << std::endl;
    }
}

} // extern "C"
