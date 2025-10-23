#include <optix.h>
#include "../include/OptiXData.h"

// Miss shader - returns background color when ray hits nothing
extern "C" __global__ void __miss__ms() {
    // Get miss data (background color)
    const MissData* miss_data = reinterpret_cast<MissData*>(optixGetSbtDataPointer());

    // Convert float [0,1] to unsigned int [0,255]
    const unsigned int r = static_cast<unsigned int>(miss_data->r * 255.99f);
    const unsigned int g = static_cast<unsigned int>(miss_data->g * 255.99f);
    const unsigned int b = static_cast<unsigned int>(miss_data->b * 255.99f);

    // Set payload
    optixSetPayload_0(r);
    optixSetPayload_1(g);
    optixSetPayload_2(b);
}
