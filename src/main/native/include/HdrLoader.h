#pragma once

#include <cstddef>
#include <memory>
#include <optional>
#include <string>

// Host-only Radiance .hdr loader. It includes no CUDA or OptiX header, so it can be unit-tested
// (and run under the address and undefined-behaviour sanitizers) without a GPU.

// Largest width or height of any image stb_image decodes (STBI_MAX_DIMENSIONS, set from this in
// stb_image_impl.cpp). 8192 is the width of the 8K Kiara environment map; an 8192x4096 RGBA float
// image is 537 MB, an 8192x8192 one 1 GiB.
constexpr int HDR_MAX_DIMENSION = 8192;

// Frees a buffer that stb_image allocated.
struct StbImageFree {
    void operator()(float* pixels) const;
};

// A decoded image. It owns the buffer stb_image allocated: no second copy of a possibly 537 MB
// image is made. Move-only.
struct HdrImage {
    std::unique_ptr<float, StbImageFree> rgba;  // width * height * 4 floats, row-major from the top
    int width = 0;
    int height = 0;

    size_t pixelCount() const { return static_cast<size_t>(width) * static_cast<size_t>(height); }
};

// Smallest number of bytes a Radiance file of this size can have: pixel data only, no header.
// Scanlines of width 8 to 32767 may be run-length encoded: 4 bytes of scanline header, then per
// channel at least one 2-byte run for every 127 pixels. Anything else is 4 bytes per pixel.
long long minimumHdrDataBytes(long long width, long long height);

// Loads a Radiance .hdr as RGBA floats. Returns nullopt on any failure; `error` then says why.
// Rejects, before decoding anything, an image over HDR_MAX_DIMENSION and a file too small to hold
// the pixels its header claims. Rejects a file that ends before the decoder is done, which
// stb_image alone would pad with zeros.
std::optional<HdrImage> loadHdr(const char* path, std::string& error);
