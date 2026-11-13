#include "include/HdrLoader.h"

#include "stb_image.h"

std::optional<HdrImage> loadHdr(const char* path, std::string& error) {
    if (!path) {
        error = "null path";
        return std::nullopt;
    }
    int w = 0, h = 0, c = 0;
    float* data = stbi_loadf(path, &w, &h, &c, 4);
    if (!data) {
        error = stbi_failure_reason() ? stbi_failure_reason() : "unknown error";
        return std::nullopt;
    }
    HdrImage image;
    image.width = w;
    image.height = h;
    image.rgba.assign(data, data + static_cast<size_t>(w) * h * 4);
    stbi_image_free(data);
    return image;
}
