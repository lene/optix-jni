#pragma once

// Input checks at the JNI boundary, the library's only trusted boundary (review 2026-10-14,
// optix-jni#68, #69): sizes are computed in 64 bits and rejected when negative or too large,
// instead of wrapping in 32-bit `int`/`jsize` arithmetic -- 32768 x 32769 RGBA wrapped to 131072
// bytes, passed the length check and made cudaMemcpy2DToArray read ~4 GiB from a 128 KiB array;
// exactly 2^30 pixels wrapped to 0. Every check throws a Java exception and returns false (or -1),
// so the caller just returns.

#include <jni.h>

#include <cstdint>
#include <initializer_list>
#include <limits>
#include <string>

namespace jni_checks {

// The largest image a render or texture may have: 16384 x 16384 pixels, 1 GiB as RGBA.
constexpr int64_t kMaxPixels = 16384LL * 16384LL;
constexpr int64_t kMaxArrayLength = std::numeric_limits<jsize>::max();

// The product of `factors` in 64 bits, or -1 if a factor is negative or the product exceeds the
// longest Java array.
inline int64_t product(std::initializer_list<int64_t> factors) {
    int64_t result = 1;
    for (int64_t factor : factors) {
        if (factor < 0) return -1;
        if (factor != 0 && result > kMaxArrayLength / factor) return -1;
        result *= factor;
    }
    return result;
}

inline void throwNew(JNIEnv* env, const char* exceptionClass, const std::string& message) {
    jclass cls = env->FindClass(exceptionClass);
    if (cls != nullptr) env->ThrowNew(cls, message.c_str());
}

inline void throwIllegalArgument(JNIEnv* env, const std::string& message) {
    throwNew(env, "java/lang/IllegalArgumentException", message);
}

// True if `value` is not null; otherwise throws NullPointerException naming it. A null used to
// reach GetArrayLength/GetStringUTFChars and abort the JVM (optix-jni#69).
inline bool requireNonNull(JNIEnv* env, jobject value, const char* name) {
    if (value != nullptr) return true;
    throwNew(env, "java/lang/NullPointerException", std::string(name) + " must not be null");
    return false;
}

// True if `array` is not null and has exactly product(factors) elements; otherwise throws.
inline bool requireLength(JNIEnv* env, jarray array, const char* name,
                          std::initializer_list<int64_t> factors, const char* formula) {
    if (!requireNonNull(env, array, name)) return false;
    const int64_t expected = product(factors);
    if (expected < 0) {
        throwIllegalArgument(env, std::string(formula) + " is negative or too large");
        return false;
    }
    const jsize actual = env->GetArrayLength(array);
    if (actual != expected) {
        throwIllegalArgument(env, std::string(name) + " length (" + std::to_string(actual) +
            ") must equal " + formula + " (" + std::to_string(expected) + ")");
        return false;
    }
    return true;
}

// True if `array` is not null and has at least `minimum` elements; otherwise throws.
inline bool requireMinLength(JNIEnv* env, jarray array, const char* name, int64_t minimum) {
    if (!requireNonNull(env, array, name)) return false;
    const jsize actual = env->GetArrayLength(array);
    if (actual < minimum) {
        throwIllegalArgument(env, std::string(name) + " length (" + std::to_string(actual) +
            ") must be at least " + std::to_string(minimum));
        return false;
    }
    return true;
}

// True if every one of the `count` indices is in [0, bound); otherwise throws naming the first
// that is not. An index out of range made OptiX read past the vertex buffer (optix-jni#70).
inline bool requireIndicesBelow(JNIEnv* env, const jint* indices, int64_t count, jint bound,
                                const char* name) {
    for (int64_t i = 0; i < count; ++i) {
        if (indices[i] < 0 || indices[i] >= bound) {
            throwIllegalArgument(env, std::string(name) + "[" + std::to_string(i) + "] = " +
                std::to_string(indices[i]) + " is outside [0, " + std::to_string(bound) + ")");
            return false;
        }
    }
    return true;
}

// True for the vertex strides the mesh code reads (pos+normal, +uv, +uv+alpha); otherwise throws.
inline bool requireVertexStride(JNIEnv* env, jint stride) {
    if (stride == 6 || stride == 8 || stride == 9) return true;
    throwIllegalArgument(env, "vertexStride must be 6, 8, or 9, got " + std::to_string(stride));
    return false;
}

// Loop counts the device runs per pixel or per frame (optix-jni#70): unchecked, a huge value
// hung the GPU. Generous next to menger's own caps (IBL 8, accumulation 256, menger-common's
// CausticsConfig limits for photons and iterations).
constexpr int kMaxIblSamples = 64;
constexpr int kMaxAccumulationFrames = 1024;
constexpr int kMaxPhotonsPerIteration = 10000000;
constexpr int kMaxCausticsIterations = 1000;

// True if lo <= value <= hi; otherwise throws naming the value and the range.
template <typename T>
inline bool requireRange(JNIEnv* env, T value, T lo, T hi, const char* name) {
    if (value >= lo && value <= hi) return true;  // false for NaN as well
    throwIllegalArgument(env, std::string(name) + " must be in [" + std::to_string(lo) + ", " +
        std::to_string(hi) + "], got " + std::to_string(value));
    return false;
}

// The RGBA byte count of a width x height image, or -1 (after throwing) unless both are positive
// and the image has at most kMaxPixels pixels.
inline int64_t rgbaBytes(JNIEnv* env, jint width, jint height) {
    const int64_t pixels = static_cast<int64_t>(width) * static_cast<int64_t>(height);
    if (width <= 0 || height <= 0 || pixels > kMaxPixels) {
        throwIllegalArgument(env, "image dimensions " + std::to_string(width) + "x" +
            std::to_string(height) + " must be positive and at most " +
            std::to_string(kMaxPixels) + " pixels");
        return -1;
    }
    return pixels * 4;
}

}  // namespace jni_checks
