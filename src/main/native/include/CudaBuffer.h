#ifndef CUDA_BUFFER_H
#define CUDA_BUFFER_H

#include <cuda_runtime.h>
#include "OptiXErrorChecking.h"
#include <cstring>

/**
 * RAII wrapper for CUDA device memory.
 * Automatically frees memory on destruction, preventing leaks.
 *
 * Addresses Issue 5.2: Missing error recovery in buffer allocation.
 * If allocation fails partway through multiple allocations, previously
 * allocated buffers are automatically freed via RAII.
 */
template<typename T>
class CudaBuffer {
public:
    CudaBuffer() : d_ptr(0), size_bytes(0) {}

    explicit CudaBuffer(size_t count) : d_ptr(0), size_bytes(0) {
        allocate(count);
    }

    ~CudaBuffer() {
        free();
    }

    // Disable copy (to prevent double-free)
    CudaBuffer(const CudaBuffer&) = delete;
    CudaBuffer& operator=(const CudaBuffer&) = delete;

    // Enable move semantics
    CudaBuffer(CudaBuffer&& other) noexcept
        : d_ptr(other.d_ptr), size_bytes(other.size_bytes) {
        other.d_ptr = 0;
        other.size_bytes = 0;
    }

    CudaBuffer& operator=(CudaBuffer&& other) noexcept {
        if (this != &other) {
            free();
            d_ptr = other.d_ptr;
            size_bytes = other.size_bytes;
            other.d_ptr = 0;
            other.size_bytes = 0;
        }
        return *this;
    }

    void allocate(size_t count) {
        if (count == 0) {
            free();
            return;
        }

        const size_t new_size = count * sizeof(T);

        // Only reallocate if size changed
        if (size_bytes != new_size) {
            free();
            CUDA_CHECK(cudaMalloc(reinterpret_cast<void**>(&d_ptr), new_size));
            size_bytes = new_size;
        }
    }

    void free() {
        if (d_ptr) {
            cudaFree(reinterpret_cast<void*>(d_ptr));
            d_ptr = 0;
            size_bytes = 0;
        }
    }

    void uploadFrom(const T* host_ptr, size_t count) {
        if (!d_ptr || count == 0) {
            throw std::runtime_error("Cannot upload to unallocated or zero-size buffer");
        }
        CUDA_CHECK(cudaMemcpy(
            reinterpret_cast<void*>(d_ptr),
            host_ptr,
            count * sizeof(T),
            cudaMemcpyHostToDevice
        ));
    }

    void downloadTo(T* host_ptr, size_t count) const {
        if (!d_ptr || count == 0) {
            throw std::runtime_error("Cannot download from unallocated or zero-size buffer");
        }
        CUDA_CHECK(cudaMemcpy(
            host_ptr,
            reinterpret_cast<void*>(d_ptr),
            count * sizeof(T),
            cudaMemcpyDeviceToHost
        ));
    }

    void zero(size_t count) {
        if (d_ptr && count > 0) {
            CUDA_CHECK(cudaMemset(reinterpret_cast<void*>(d_ptr), 0, count * sizeof(T)));
        }
    }

    CUdeviceptr get() const { return d_ptr; }
    size_t sizeBytes() const { return size_bytes; }
    bool isAllocated() const { return d_ptr != 0; }

    // Get typed pointer (for reinterpret_cast in params setup)
    T* getTyped() const { return reinterpret_cast<T*>(d_ptr); }

private:
    CUdeviceptr d_ptr;
    size_t size_bytes;
};

#endif // CUDA_BUFFER_H
