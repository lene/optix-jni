cmake_minimum_required(VERSION 3.18 FATAL_ERROR)
project(optixjni LANGUAGES CXX)

# Set C++ standard
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

#=============================================================================
# User Options
#=============================================================================
option(ENABLE_CUDA "Enable CUDA compilation (auto-detected if not set)" ON)
option(ENABLE_OPTIX "Enable OptiX support (requires CUDA)" ON)

#=============================================================================
# Detect CUDA Availability
#=============================================================================
set(HAVE_CUDA FALSE)

if(ENABLE_CUDA)
  include(CheckLanguage)
  check_language(CUDA)

  if(CMAKE_CUDA_COMPILER)
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

    # Try to find CUDA Toolkit
    find_package(CUDAToolkit 12.0 QUIET)

    if(CUDAToolkit_FOUND)
      message(STATUS "✓ CUDA enabled: ${CUDAToolkit_VERSION} (${CMAKE_CUDA_COMPILER})")
      set(HAVE_CUDA TRUE)
    else()
      message(STATUS "✗ CUDA compiler found but CUDAToolkit not found")
    endif()
  else()
    message(STATUS "✗ CUDA compiler not found")
  endif()
else()
  message(STATUS "✗ CUDA disabled by user (-DENABLE_CUDA=OFF)")
endif()

#=============================================================================
# Detect OptiX Availability
#=============================================================================
set(HAVE_OPTIX FALSE)

if(ENABLE_OPTIX AND HAVE_CUDA)
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
          list(GET OPTIX_CANDIDATES 0 OptiX_INSTALL_DIR)
          message(STATUS "Auto-detected OptiX at: ${OptiX_INSTALL_DIR}")
        else()
          message(WARNING "OptiX SDK not found in common locations. Set OPTIX_ROOT environment variable or use -DOptiX_INSTALL_DIR=/path/to/sdk")
        endif()
      endif()
    endif()
  endif()

  set(OptiX_INCLUDE_DIR "${OptiX_INSTALL_DIR}/include")

  # Verify OptiX installation
  if(EXISTS "${OptiX_INCLUDE_DIR}/optix.h")
    message(STATUS "✓ OptiX enabled: ${OptiX_INSTALL_DIR}")
    set(HAVE_OPTIX TRUE)
  else()
    message(STATUS "✗ OptiX SDK not found at ${OptiX_INSTALL_DIR}")
    message(STATUS "  Set OPTIX_ROOT environment variable or -DOptiX_INSTALL_DIR=/path/to/sdk")
  endif()
elseif(ENABLE_OPTIX AND NOT HAVE_CUDA)
  message(STATUS "✗ OptiX requires CUDA - skipping OptiX")
else()
  message(STATUS "✗ OptiX disabled by user (-DENABLE_OPTIX=OFF)")
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
message(STATUS "  CUDA Support:   ${HAVE_CUDA}")
message(STATUS "  OptiX Support:  ${HAVE_OPTIX}")
if(NOT HAVE_CUDA OR NOT HAVE_OPTIX)
  message(STATUS "")
  message(STATUS "  NOTE: Building stub library without GPU support")
  message(STATUS "  The OptiXRenderer will use fallback implementation")
endif()
message(STATUS "========================================")
message(STATUS "")

#=============================================================================
# Compile PTX Shaders (CUDA + OptiX only)
#=============================================================================
if(HAVE_CUDA AND HAVE_OPTIX)
  add_library(optix_shaders OBJECT
      shaders/sphere_combined.cu
  )

  target_include_directories(optix_shaders PRIVATE
      ${OptiX_INCLUDE_DIR}
      ${CUDAToolkit_INCLUDE_DIRS}
      ${CMAKE_CURRENT_SOURCE_DIR}/include
  )

  # Define preprocessor macros for CUDA shaders
  target_compile_definitions(optix_shaders PRIVATE HAVE_CUDA HAVE_OPTIX)

  # OptiX PTX shaders can only target ONE architecture at a time
  # Use virtual architecture (PTX) for forward compatibility
  #
  # Using compute_52 (Maxwell) as minimum for maximum compatibility
  # PTX will be JIT-compiled at runtime to actual GPU (sm_52, 75, 86, 89, etc.)
  # This single PTX works on any NVIDIA GPU from 2014 onwards
  set_target_properties(optix_shaders PROPERTIES
      CUDA_PTX_COMPILATION ON
      CUDA_SEPARABLE_COMPILATION ON
      CUDA_ARCHITECTURES "52-virtual"
  )
endif()

#=============================================================================
# Main JNI Library (always built)
#=============================================================================
add_library(optixjni SHARED
    OptiXWrapper.cpp
    JNIBindings.cpp
)

target_include_directories(optixjni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# Add CUDA/OptiX includes if available
if(HAVE_CUDA)
  target_include_directories(optixjni PRIVATE ${CUDAToolkit_INCLUDE_DIRS})
endif()

if(HAVE_OPTIX)
  target_include_directories(optixjni PRIVATE ${OptiX_INCLUDE_DIR})
endif()

# Link CUDA libraries if available
if(HAVE_CUDA)
  target_link_libraries(optixjni PRIVATE
      CUDA::cuda_driver
      CUDA::cudart
  )
endif()

# Define preprocessor macros for conditional compilation
if(HAVE_CUDA)
  target_compile_definitions(optixjni PRIVATE HAVE_CUDA)
endif()

if(HAVE_OPTIX)
  target_compile_definitions(optixjni PRIVATE HAVE_OPTIX)
endif()

#=============================================================================
# Installation
#=============================================================================
install(TARGETS optixjni LIBRARY DESTINATION .)

# Install PTX files only if they were compiled
if(HAVE_CUDA AND HAVE_OPTIX)
  install(FILES "${CMAKE_BINARY_DIR}/CMakeFiles/optix_shaders.dir/shaders/sphere_combined.ptx"
          DESTINATION .
          RENAME "sphere_combined.ptx"
          OPTIONAL)
endif()

# Set output directory for development
set_target_properties(optixjni PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)
