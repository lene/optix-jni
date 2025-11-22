#include <jni.h>
#include "include/OptiXWrapper.h"
#include <iostream>
#include <cstring>

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

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setSphereColorNative(
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

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setCameraNative(
    JNIEnv* env, jobject obj, jfloatArray eye, jfloatArray lookAt, jfloatArray up, jfloat horizontalFovDegrees) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            jfloat* eyeArr = env->GetFloatArrayElements(eye, nullptr);
            jfloat* lookAtArr = env->GetFloatArrayElements(lookAt, nullptr);
            jfloat* upArr = env->GetFloatArrayElements(up, nullptr);

            wrapper->setCamera(eyeArr, lookAtArr, upArr, horizontalFovDegrees);

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

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setLights(
    JNIEnv* env, jobject obj, jobjectArray lightsArray) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper == nullptr) {
            return;
        }

        jsize count = env->GetArrayLength(lightsArray);

        // Validate count BEFORE creating stack array to prevent buffer overflow
        if (count < 0 || count > RayTracingConstants::MAX_LIGHTS) {
            jclass exception_class = env->FindClass("java/lang/IllegalArgumentException");
            std::string message = "Light count " + std::to_string(count) +
                                " out of range [0, " + std::to_string(RayTracingConstants::MAX_LIGHTS) + "]";
            env->ThrowNew(exception_class, message.c_str());
            return;
        }

        // Find Light class and field IDs
        jclass lightClass = env->FindClass("menger/optix/Light");
        if (lightClass == nullptr) {
            jclass exception_class = env->FindClass("java/lang/RuntimeException");
            env->ThrowNew(exception_class, "Failed to find Light class");
            return;
        }

        jfieldID typeField = env->GetFieldID(lightClass, "lightType", "I");
        jfieldID directionField = env->GetFieldID(lightClass, "direction", "[F");
        jfieldID positionField = env->GetFieldID(lightClass, "position", "[F");
        jfieldID colorField = env->GetFieldID(lightClass, "color", "[F");
        jfieldID intensityField = env->GetFieldID(lightClass, "intensity", "F");

        if (typeField == nullptr || directionField == nullptr || positionField == nullptr ||
            colorField == nullptr || intensityField == nullptr) {
            jclass exception_class = env->FindClass("java/lang/RuntimeException");
            env->ThrowNew(exception_class, "Failed to find Light fields");
            return;
        }

        // Convert Java lights to C++ lights
        Light lights[RayTracingConstants::MAX_LIGHTS];
        for (jsize i = 0; i < count; ++i) {
            jobject lightObj = env->GetObjectArrayElement(lightsArray, i);
            if (lightObj == nullptr) {
                std::cerr << "[JNI] Light at index " << i << " is null" << std::endl;
                continue;
            }

            // Extract fields
            jint type = env->GetIntField(lightObj, typeField);
            lights[i].type = static_cast<LightType>(type);

            jfloatArray dirArray = static_cast<jfloatArray>(env->GetObjectField(lightObj, directionField));
            jfloat* dirArr = env->GetFloatArrayElements(dirArray, nullptr);
            std::memcpy(lights[i].direction, dirArr, 3 * sizeof(float));
            env->ReleaseFloatArrayElements(dirArray, dirArr, 0);

            jfloatArray posArray = static_cast<jfloatArray>(env->GetObjectField(lightObj, positionField));
            jfloat* posArr = env->GetFloatArrayElements(posArray, nullptr);
            std::memcpy(lights[i].position, posArr, 3 * sizeof(float));
            env->ReleaseFloatArrayElements(posArray, posArr, 0);

            jfloatArray colArray = static_cast<jfloatArray>(env->GetObjectField(lightObj, colorField));
            jfloat* colArr = env->GetFloatArrayElements(colArray, nullptr);
            std::memcpy(lights[i].color, colArr, 3 * sizeof(float));
            env->ReleaseFloatArrayElements(colArray, colArr, 0);

            lights[i].intensity = env->GetFloatField(lightObj, intensityField);

            env->DeleteLocalRef(lightObj);
        }

        // Call C++ method (may throw std::invalid_argument)
        wrapper->setLights(lights, count);

    } catch (const std::invalid_argument& e) {
        // Convert C++ validation exception to Java IllegalArgumentException
        jclass exception_class = env->FindClass("java/lang/IllegalArgumentException");
        env->ThrowNew(exception_class, e.what());

    } catch (const std::exception& e) {
        // Convert other C++ exceptions to Java RuntimeException
        jclass exception_class = env->FindClass("java/lang/RuntimeException");
        env->ThrowNew(exception_class, e.what());
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setShadows(
    JNIEnv* env, jobject obj, jboolean enabled) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            wrapper->setShadows(enabled == JNI_TRUE);
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in setShadows: " << e.what() << std::endl;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setAntialiasing(
    JNIEnv* env, jobject obj, jboolean enabled, jint maxDepth, jfloat threshold) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            wrapper->setAntialiasing(enabled == JNI_TRUE, maxDepth, threshold);
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in setAntialiasing: " << e.what() << std::endl;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setCaustics(
    JNIEnv* env, jobject obj, jboolean enabled, jint photonsPerIter, jint iterations,
    jfloat initialRadius, jfloat alpha) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            wrapper->setCaustics(enabled == JNI_TRUE, photonsPerIter, iterations, initialRadius, alpha);
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in setCaustics: " << e.what() << std::endl;
    }
}

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setPlaneSolidColorNative(
    JNIEnv* env, jobject obj, jfloat r, jfloat g, jfloat b) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            wrapper->setPlaneSolidColor(r, g, b);
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in setPlaneSolidColor: " << e.what() << std::endl;
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

JNIEXPORT void JNICALL Java_menger_optix_OptiXRenderer_setPlaneCheckerColorsNative(
    JNIEnv* env, jobject obj, jfloat r1, jfloat g1, jfloat b1, jfloat r2, jfloat g2, jfloat b2) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper != nullptr) {
            wrapper->setPlaneCheckerColors(r1, g1, b1, r2, g2, b2);
        }
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in setPlaneCheckerColors: " << e.what() << std::endl;
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

        jmethodID constructor = env->GetMethodID(resultClass, "<init>", "([BJJJJJJII)V");
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
            static_cast<jlong>(stats.shadow_rays),
            static_cast<jlong>(stats.aa_rays),
            static_cast<jint>(stats.max_depth_reached),
            static_cast<jint>(stats.min_depth_reached)
        );

        return result;
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in renderWithStats: " << e.what() << std::endl;
        return nullptr;
    }
}

/**
 * Get caustics statistics for PPM validation (C1-C8 test ladder).
 * Returns a CausticsStats object with all tracked metrics.
 */
JNIEXPORT jobject JNICALL Java_menger_optix_OptiXRenderer_getCausticsStatsNative(JNIEnv* env, jobject obj) {
    try {
        OptiXWrapper* wrapper = getWrapper(env, obj);
        if (wrapper == nullptr) {
            return nullptr;
        }

        // Get caustics stats from wrapper
        CausticsStats stats;
        if (!wrapper->getCausticsStats(&stats)) {
            return nullptr;  // Caustics not enabled or no stats available
        }

        // Find CausticsStats Scala class
        jclass statsClass = env->FindClass("menger/optix/OptiXRenderer$CausticsStats");
        if (statsClass == nullptr) {
            std::cerr << "[JNI] Failed to find CausticsStats class" << std::endl;
            return nullptr;
        }

        // Constructor signature: (JJJJJJJJDDDDFFFFFFFFF)V
        // 8 longs + 4 doubles + 9 floats
        jmethodID constructor = env->GetMethodID(statsClass, "<init>",
            "(JJJJJJJJDDDDFFFFFFFFF)V");
        if (constructor == nullptr) {
            std::cerr << "[JNI] Failed to find CausticsStats constructor" << std::endl;
            return nullptr;
        }

        jobject result = env->NewObject(statsClass, constructor,
            // C1: Emission
            static_cast<jlong>(stats.photons_emitted),
            static_cast<jlong>(stats.photons_toward_sphere),
            // C2: Sphere hits
            static_cast<jlong>(stats.sphere_hits),
            static_cast<jlong>(stats.sphere_misses),
            // C3: Refraction
            static_cast<jlong>(stats.refraction_events),
            static_cast<jlong>(stats.tir_events),
            // C4: Deposition
            static_cast<jlong>(stats.photons_deposited),
            static_cast<jlong>(stats.hit_points_with_flux),
            // C5: Energy
            static_cast<jdouble>(stats.total_flux_emitted),
            static_cast<jdouble>(stats.total_flux_deposited),
            static_cast<jdouble>(stats.total_flux_absorbed),
            static_cast<jdouble>(stats.total_flux_reflected),
            // C6: Convergence
            static_cast<jfloat>(stats.avg_radius),
            static_cast<jfloat>(stats.min_radius),
            static_cast<jfloat>(stats.max_radius),
            static_cast<jfloat>(stats.flux_variance),
            // C7: Brightness
            static_cast<jfloat>(stats.max_caustic_brightness),
            static_cast<jfloat>(stats.avg_floor_brightness),
            // Timing
            static_cast<jfloat>(stats.hit_point_generation_ms),
            static_cast<jfloat>(stats.photon_tracing_ms),
            static_cast<jfloat>(stats.radiance_computation_ms)
        );

        return result;
    } catch (const std::exception& e) {
        std::cerr << "[JNI] Error in getCausticsStats: " << e.what() << std::endl;
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
