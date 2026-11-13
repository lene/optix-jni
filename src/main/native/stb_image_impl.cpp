#define STB_IMAGE_IMPLEMENTATION
#define STBI_FAILURE_USERMSG
// Largest width or height of any image stb_image decodes (HDR, PNG, JPEG, ...); it rejects larger
// ones before allocating. 8192 is the width of the 8K Kiara environment map: an 8192x4096 RGBA
// float image is 537 MB. stb_image's default (1 << 24) would let a corrupt header ask for
// gigabytes. Keep in sync with MAX_DIMENSION in tests/HdrLoaderTest.cpp.
#define STBI_MAX_DIMENSIONS 8192
#include "stb_image.h"
