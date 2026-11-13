#include "include/HdrLoader.h"

#define STB_IMAGE_IMPLEMENTATION
#define STBI_FAILURE_USERMSG
// stb_image rejects, before allocating, any image (HDR, PNG, JPEG, ...) wider or taller than this.
// Its default (1 << 24) would let a corrupt header ask for gigabytes.
#define STBI_MAX_DIMENSIONS HDR_MAX_DIMENSION
#include "stb_image.h"
