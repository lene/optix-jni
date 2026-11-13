#include "include/HdrLoader.h"

#include "stb_image.h"

#include <sys/stat.h>

#include <algorithm>
#include <cstdio>

void StbImageFree::operator()(float* pixels) const {
    stbi_image_free(pixels);
}

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

constexpr int RLE_MIN_WIDTH = 8;
constexpr int RLE_MAX_WIDTH = 32767;
constexpr long long RLE_PIXELS_PER_RUN = 127;
constexpr long long RLE_SCANLINE_HEADER_BYTES = 4;
constexpr long long RLE_RUN_BYTES = 2;
constexpr long long CHANNELS = 4;

long long fileSize(FILE* file) {
    struct stat info {};
    return fstat(fileno(file), &info) == 0 ? static_cast<long long>(info.st_size) : -1;
}

// Reads width and height from the header without decoding. False if stb_image cannot tell.
bool readDimensions(FileSource& source, int& width, int& height) {
    int components = 0;
    const bool known =
        stbi_info_from_callbacks(&FILE_CALLBACKS, &source, &width, &height, &components) != 0;
    rewind(source.file);
    source.starved = false;
    return known;
}

// A message if the header's size cannot be right, empty if it can. Costs no allocation.
std::string rejectBeforeDecoding(FileSource& source) {
    int width = 0, height = 0;
    if (!readDimensions(source, width, height)) return "";  // decoding gives stb_image's reason

    const std::string size = std::to_string(width) + "x" + std::to_string(height);
    if (width > HDR_MAX_DIMENSION || height > HDR_MAX_DIMENSION) {
        return "image too large: " + size + ", at most " + std::to_string(HDR_MAX_DIMENSION);
    }
    const long long bytes = fileSize(source.file);
    if (bytes >= 0 && bytes < minimumHdrDataBytes(width, height)) {
        return "file too small for " + size + " pixels: " + std::to_string(bytes) + " bytes";
    }
    return "";
}

}  // namespace

long long minimumHdrDataBytes(long long width, long long height) {
    const long long flatScanline = CHANNELS * width;
    if (width < RLE_MIN_WIDTH || width > RLE_MAX_WIDTH) return flatScanline * height;
    const long long runsPerChannel = (width + RLE_PIXELS_PER_RUN - 1) / RLE_PIXELS_PER_RUN;
    const long long rleScanline = RLE_SCANLINE_HEADER_BYTES + CHANNELS * RLE_RUN_BYTES * runsPerChannel;
    return std::min(flatScanline, rleScanline) * height;
}

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
    // RED-STATE-STUB

    HdrImage image;
    int components = 0;
    image.rgba.reset(stbi_loadf_from_callbacks(&FILE_CALLBACKS, &source, &image.width, &image.height,
                                               &components, 4));
    if (!image.rgba) {
        const char* reason = stbi_failure_reason();
        error = reason ? reason : "unknown error";
        return std::nullopt;
    }
    if (source.starved) {
        error = "truncated file: the pixel data ends before the header's size";
        return std::nullopt;
    }
    return image;
}
