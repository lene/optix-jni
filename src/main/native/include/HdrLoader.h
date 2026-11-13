#pragma once

#include <optional>
#include <string>
#include <vector>

// Host-only Radiance .hdr loader. It includes no CUDA or OptiX header, so it can be unit-tested
// (and run under the address sanitizer) without a GPU.

struct HdrImage {
    std::vector<float> rgba;  // width * height * 4 floats, row-major from the top
    int width = 0;
    int height = 0;
};

// Loads a Radiance .hdr as RGBA floats. Returns nullopt on any failure; `error` then says why.
// Rejects images wider or taller than the STBI_MAX_DIMENSIONS cap (see stb_image_impl.cpp) and
// truncated files, which stb_image alone would pad with zeros.
std::optional<HdrImage> loadHdr(const char* path, std::string& error);
