cmake_minimum_required(VERSION 3.18 FATAL_ERROR)
project(optixjni LANGUAGES CXX CUDA)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CUDA_STANDARD 17)
set(CMAKE_CUDA_STANDARD_REQUIRED ON)

# Find CUDA
find_package(CUDAToolkit 12.0 REQUIRED)

# Find JNI
find_package(JNI REQUIRED)

# OptiX SDK paths
set(OptiX_INSTALL_DIR "/usr/local/NVIDIA-OptiX-SDK-8.0.0-linux64-x86_64" CACHE PATH "Path to OptiX SDK")
set(OptiX_INCLUDE_DIR "${OptiX_INSTALL_DIR}/include")

# Verify OptiX installation
if(NOT EXISTS "${OptiX_INCLUDE_DIR}/optix.h")
    message(FATAL_ERROR "OptiX SDK not found at ${OptiX_INSTALL_DIR}")
endif()

message(STATUS "Found OptiX: ${OptiX_INSTALL_DIR}")
message(STATUS "Found CUDA: ${CUDAToolkit_VERSION}")
message(STATUS "Found JNI: ${JNI_INCLUDE_DIRS}")

# CUDA architectures for NVIDIA GPUs
# For PTX compilation, we need to use a single virtual architecture
# Using sm_75 as the base architecture for compatibility with RTX 20 series and newer
set(CMAKE_CUDA_ARCHITECTURES 75)

# Compile CUDA kernels to PTX
add_library(optix_shaders OBJECT
    shaders/sphere_raygen.cu
    shaders/sphere_closesthit.cu
    shaders/sphere_miss.cu
)

target_include_directories(optix_shaders PRIVATE
    ${OptiX_INCLUDE_DIR}
    ${CUDAToolkit_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# Set PTX compilation
set_target_properties(optix_shaders PROPERTIES
    CUDA_PTX_COMPILATION ON
    CUDA_SEPARABLE_COMPILATION ON
)

# Main JNI library
add_library(optixjni SHARED
    OptiXWrapper.cpp
    JNIBindings.cpp
)

target_include_directories(optixjni PRIVATE
    ${OptiX_INCLUDE_DIR}
    ${CUDAToolkit_INCLUDE_DIRS}
    ${JNI_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

target_link_libraries(optixjni PRIVATE
    CUDA::cuda_driver
    CUDA::cudart
)

# Install PTX files alongside the library
install(TARGETS optixjni LIBRARY DESTINATION .)
install(FILES $<TARGET_OBJECTS:optix_shaders> DESTINATION . OPTIONAL)

# Set output directory for development
set_target_properties(optixjni PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)
