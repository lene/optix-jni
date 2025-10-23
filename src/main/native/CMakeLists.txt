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
    enable_language(CUDA)
    set(CMAKE_CUDA_STANDARD 17)
    set(CMAKE_CUDA_STANDARD_REQUIRED ON)

    # Try to find CUDA Toolkit
    find_package(CUDAToolkit 12.0 QUIET)

    if(CUDAToolkit_FOUND)
      message(STATUS "✓ CUDA enabled: ${CUDAToolkit_VERSION} (${CMAKE_CUDA_COMPILER})")
      set(HAVE_CUDA TRUE)

      # CUDA architectures for NVIDIA GPUs
      # For PTX compilation, we need to use a single virtual architecture
      # Using sm_75 as the base architecture for compatibility with RTX 20 series and newer
      set(CMAKE_CUDA_ARCHITECTURES 75)
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
  # OptiX SDK paths (can be overridden via -DOptiX_INSTALL_DIR=/custom/path)
  set(OptiX_INSTALL_DIR "/usr/local/NVIDIA-OptiX-SDK-8.0.0-linux64-x86_64"
      CACHE PATH "Path to OptiX SDK")
  set(OptiX_INCLUDE_DIR "${OptiX_INSTALL_DIR}/include")

  # Verify OptiX installation
  if(EXISTS "${OptiX_INCLUDE_DIR}/optix.h")
    message(STATUS "✓ OptiX enabled: ${OptiX_INSTALL_DIR}")
    set(HAVE_OPTIX TRUE)
  else()
    message(STATUS "✗ OptiX SDK not found at ${OptiX_INSTALL_DIR}")
    message(STATUS "  Set -DOptiX_INSTALL_DIR=/path/to/sdk to specify custom location")
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

  # Set PTX compilation
  set_target_properties(optix_shaders PROPERTIES
      CUDA_PTX_COMPILATION ON
      CUDA_SEPARABLE_COMPILATION ON
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
  install(FILES $<TARGET_OBJECTS:optix_shaders> DESTINATION . OPTIONAL)
endif()

# Set output directory for development
set_target_properties(optixjni PROPERTIES
    LIBRARY_OUTPUT_DIRECTORY "${CMAKE_BINARY_DIR}"
)
