#include <optix.h>
#include "../include/OptiXData.h"
#include "../include/VectorMath.h"

extern "C" {
    __constant__ Params params;
}

// Import ray tracing constants from OptiXData.h
using namespace RayTracingConstants;

#include "helpers.cu"
#include "raygen_primary.cu"
#include "miss_plane.cu"
#include "hit_sphere.cu"
#include "hit_triangle.cu"
#include "hit_cylinder.cu"
#include "shadows.cu"
#include "caustics_ppm.cu"
