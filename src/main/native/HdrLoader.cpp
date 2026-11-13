#include "include/HdrLoader.h"

#include "stb_image.h"

#include <cstdio>
#include <memory>

namespace {

struct FileCloser {
    void operator()(FILE* file) const { fclose(file); }
};
using FilePtr = std::unique_ptr<FILE, FileCloser>;

// stb_image reads a file through these callbacks. `starved` records that the decoder asked for a
// byte after the last one of the file: stb_image does not report that, it decodes the missing
// bytes as zeros. The HDR decoder reads only through the buffer-refill path, and refills only
// when it needs a byte, so a read that returns nothing means the data ended before the decoder
// was done.
struct FileSource {
    FILE* file;
    bool starved = false;
};

int readCallback(void* user, char* data, int size) {
    auto* source = static_cast<FileSource*>(user);
    const size_t got = fread(data, 1, static_cast<size_t>(size), source->file);
    if (got == 0 && size > 0) source->starved = true;
    return static_cast<int>(got);
}

void skipCallback(void* user, int n) {
    fseek(static_cast<FileSource*>(user)->file, n, SEEK_CUR);
}

int eofCallback(void* user) {
    return feof(static_cast<FileSource*>(user)->file) ? 1 : 0;
}

const stbi_io_callbacks FILE_CALLBACKS = {readCallback, skipCallback, eofCallback};

}  // namespace

std::optional<HdrImage> loadHdr(const char* path, std::string& error) {
    if (!path) {
        error = "null path";
        return std::nullopt;
    }
    FilePtr file(fopen(path, "rb"));
    if (!file) {
        error = "cannot open file";
        return std::nullopt;
    }

    FileSource source{file.get()};
    int w = 0, h = 0, c = 0;
    float* data = stbi_loadf_from_callbacks(&FILE_CALLBACKS, &source, &w, &h, &c, 4);
    if (!data) {
        const char* reason = stbi_failure_reason();
        error = reason ? reason : "unknown error";
        return std::nullopt;
    }
    std::unique_ptr<float, decltype(&stbi_image_free)> pixels(data, stbi_image_free);
    if (source.starved) {
        error = "truncated file: the pixel data ends before the header's size";
        return std::nullopt;
    }

    HdrImage image;
    image.width = w;
    image.height = h;
    image.rgba.assign(data, data + static_cast<size_t>(w) * static_cast<size_t>(h) * 4);
    return image;
}
