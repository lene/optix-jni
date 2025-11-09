cmake_minimum_required(VERSION 3.18 FATAL_ERROR)
project(optixjni LANGUAGES CXX)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Suppress verbose install messages
set(CMAKE_INSTALL_MESSAGE LAZY)

#=============================================================================
# Detect CUDA Availability (REQUIRED)
#=============================================================================
include(CheckLanguage)
check_language(CUDA)

if(NOT CMAKE_CUDA_COMPILER)
  message(FATAL_ERROR "CUDA compiler not found. OptiX JNI requires CUDA Toolkit 12.0+")
endif()

# CUDA architectures for NVIDIA GPUs
# IMPORTANT: Must be set BEFORE enable_language(CUDA)
#
# Build a "fat binary" containing native code for multiple architectures
# plus PTX for forward compatibility. CUDA runtime automatically selects
# the best version for the actual GPU at runtime.
#
# Architectures included:
# - 52-real: Maxwell  (GTX 900 series, Tesla M40, 2014)
# - 75-real: Turing   (RTX 20xx, Tesla T4, 2018)
# - 86-real: Ampere   (RTX 30xx, A100, 2020)
# - 89-real: Ada      (RTX 40xx, L40, 2022)
# - 75-virtual: PTX intermediate code for forward compatibility with future GPUs
#
# This single artifact works on any GPU from 2014 onwards.
set(CMAKE_CUDA_ARCHITECTURES "52-real;75-real;86-real;89-real;75-virtual")

enable_language(CUDA)
set(CMAKE_CUDA_STANDARD 17)
set(CMAKE_CUDA_STANDARD_REQUIRED ON)

# Find CUDA Toolkit (REQUIRED)
find_package(CUDAToolkit 12.0 REQUIRED)
message(STATUS "✓ CUDA enabled: ${CUDAToolkit_VERSION} (${CMAKE_CUDA_COMPILER})")

#=============================================================================
# Detect OptiX Availability (REQUIRED)
#=============================================================================
# OptiX SDK paths - check in order of preference:
# 1. CMake variable: -DOptiX_INSTALL_DIR=/custom/path
# 2. Environment variable: OPTIX_ROOT
# 3. Common installation locations

if(NOT DEFINED OptiX_INSTALL_DIR)
  if(DEFINED ENV{OPTIX_ROOT})
    set(OptiX_INSTALL_DIR "$ENV{OPTIX_ROOT}")
    message(STATUS "Using OptiX from OPTIX_ROOT: ${OptiX_INSTALL_DIR}")
  else()
    # Try common locations (check specific common paths first, then glob patterns)
    set(OPTIX_COMMON_PATHS
      "/usr/local/optix"
      "/opt/optix"
      "/usr/local/OptiX"
    )

    # Check specific common paths
    foreach(PATH ${OPTIX_COMMON_PATHS})
      if(EXISTS "${PATH}/include/optix.h")
        set(OptiX_INSTALL_DIR "${PATH}")
        message(STATUS "Found OptiX at: ${OptiX_INSTALL_DIR}")
        break()
      endif()
    endforeach()

    # If not found in common paths, try glob patterns for versioned SDK directories
    if(NOT DEFINED OptiX_INSTALL_DIR)
      file(GLOB OPTIX_CANDIDATES
        "/usr/local/NVIDIA-OptiX-SDK-*"
        "/opt/NVIDIA-OptiX-SDK-*"
        "$ENV{HOME}/NVIDIA-OptiX-SDK-*"
        "$ENV{HOME}/Downloads/NVIDIA-OptiX-SDK-*"
      )
      if(OPTIX_CANDIDATES)
        # Sort in descending order to get highest version first
        list(SORT OPTIX_CANDIDATES ORDER DESCENDING)
        list(GET OPTIX_CANDIDATES 0 OptiX_INSTALL_DIR)
        message(STATUS "Auto-detected OptiX at: ${OptiX_INSTALL_DIR}")
      else()
        message(FATAL_ERROR "OptiX SDK not found. Set OPTIX_ROOT environment variable or use -DOptiX_INSTALL_DIR=/path/to/sdk")
      endif()
    endif()
  endif()
endif()

set(OptiX_INCLUDE_DIR "${OptiX_INSTALL_DIR}/include")

# Verify OptiX installation (REQUIRED)
if(NOT EXISTS "${OptiX_INCLUDE_DIR}/optix.h")
  message(FATAL_ERROR "OptiX header not found at ${OptiX_INCLUDE_DIR}/optix.h. Set OPTIX_ROOT environment variable or use -DOptiX_INSTALL_DIR=/path/to/sdk")
endif()

message(STATUS "✓ OptiX enabled: ${OptiX_INSTALL_DIR}")

# Extract OptiX version from SDK path and warn about version 8.0
string(REGEX MATCH "SDK-([0-9]+)\\.([0-9]+)" OPTIX_VERSION_MATCH "${OptiX_INSTALL_DIR}")
if(CMAKE_MATCH_1)
  set(OPTIX_MAJOR_VERSION ${CMAKE_MATCH_1})
  set(OPTIX_MINOR_VERSION ${CMAKE_MATCH_2})
  message(STATUS "  OptiX Version:  ${OPTIX_MAJOR_VERSION}.${OPTIX_MINOR_VERSION}")

  if(OPTIX_MAJOR_VERSION LESS 9)
    message(WARNING "")
    message(WARNING "═══════════════════════════════════════════════════════")
    message(WARNING "OptiX SDK ${OPTIX_MAJOR_VERSION}.${OPTIX_MINOR_VERSION} detected - COMPATIBILITY WARNING")
    message(WARNING "═══════════════════════════════════════════════════════")
    message(WARNING "OptiX ${OPTIX_MAJOR_VERSION}.x is NOT compatible with modern NVIDIA drivers (580.x+)")
    message(WARNING "Driver 580.x+ includes OptiX 9.0 runtime - version mismatch")
    message(WARNING "will cause CUDA error 718 ('invalid program counter')")
    message(WARNING "")
    message(WARNING "Download OptiX SDK 9.0+ from:")
    message(WARNING "https://developer.nvidia.com/optix")
    message(WARNING "═══════════════════════════════════════════════════════")
    message(WARNING "")
  endif()
endif()

#=============================================================================
# Find JNI (always required)
#=============================================================================
find_package(JNI REQUIRED)
message(STATUS "✓ JNI: ${JNI_INCLUDE_DIRS}")

#=============================================================================
# Build Configuration Summary
#=============================================================================
message(STATUS "")
message(STATUS "========================================")
message(STATUS "OptiX JNI Build Configuration:")
message(STATUS "  CUDA Version:   ${CUDAToolkit_VERSION}")
message(STATUS "  OptiX SDK:      ${OptiX_INSTALL_DIR}")
message(STATUS "========================================")
message(STATUS "")

#=============================================================================
# Compile PTX Shaders
#=============================================================================
add_library(optix_shaders OBJECT
    shaders/sphere_combined.cu
)

target_include_directories(optix_shaders PRIVATE
    ${OptiX_INCLUDE_DIR}
    ${CUDAToolkit_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# OptiX PTX shaders can only target ONE architecture at a time
# Use virtual architecture (PTX) for forward compatibility
#
# Using virtual architecture 86 for PTX intermediate representation
# JIT-compiled at runtime for forward compatibility with any GPU >= sm_86
set_target_properties(optix_shaders PROPERTIES
    CUDA_PTX_COMPILATION ON
    CUDA_SEPARABLE_COMPILATION ON
    CUDA_ARCHITECTURES "86-virtual"
)

#=============================================================================
# Main JNI Library
#=============================================================================
add_library(optixjni SHARED
    OptiXContext.cpp
    OptiXWrapper.cpp
    JNIBindings.cpp
)

target_include_directories(optixjni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
    ${CUDAToolkit_INCLUDE_DIRS}
    ${OptiX_INCLUDE_DIR}
)

target_link_libraries(optixjni PRIVATE
    CUDA::cuda_driver
    CUDA::cudart
)

#=============================================================================
# Installation
#=============================================================================
install(TARGETS optixjni LIBRARY DESTINATION .)

# Install PTX files
install(FILES "${CMAKE_BINARY_DIR}/CMakeFiles/optix_shaders.dir/shaders/sphere_combined.ptx"
        DESTINATION .
        RENAME "sphere_combined.ptx"
)

# Set output directory for development
set_target_properties(optixjni PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)

#=============================================================================
# Google Test for Unit Tests
#=============================================================================
# Only build tests if this is the main project or explicitly requested
option(BUILD_OPTIX_TESTS "Build OptiX unit tests" ON)

if(BUILD_OPTIX_TESTS)
    include(FetchContent)
    FetchContent_Declare(
        googletest
        URL https://github.com/google/googletest/archive/release-1.12.1.zip
        DOWNLOAD_EXTRACT_TIMESTAMP TRUE
    )

    # For Windows: Prevent overriding the parent project's compiler/linker settings
    set(gtest_force_shared_crt ON CACHE BOOL "" FORCE)

    FetchContent_MakeAvailable(googletest)

    enable_testing()

    # Create test executable
    add_executable(optixcontext_test
        tests/OptiXContextTest.cpp
        tests/optix_function_table.cpp  # Define optix function table for tests
        OptiXContext.cpp  # Need to compile OptiXContext again for tests
    )

    target_include_directories(optixcontext_test PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/include
        ${CUDAToolkit_INCLUDE_DIRS}
        ${OptiX_INCLUDE_DIR}
    )

    target_link_libraries(optixcontext_test PRIVATE
        gtest
        CUDA::cuda_driver
        CUDA::cudart
    )

    # Discover and register tests with CTest
    include(GoogleTest)
    gtest_discover_tests(optixcontext_test)

    message(STATUS "✓ Google Test enabled for OptiXContext unit tests")
endif()
