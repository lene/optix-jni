# OptiX JNI Module

This module provides JNI bindings for NVIDIA OptiX ray tracing API.

## CI Configuration

### Docker Image

The CI uses a pre-built Docker image based on NVIDIA's official CUDA image with OptiX SDK, Java 25, and sbt pre-installed. This avoids 15-20 minutes of installation time on every job run.

**Image Versioning:**

Images are tagged with version numbers of all pre-installed components:
- Format: `{CUDA}-{OptiX}-{Java}-{sbt}`
- Example: `12.8-9.0-25-1.11.7` = CUDA 12.8, OptiX 9.0, Java 25, sbt 1.11.7
- The `latest` tag always points to the newest stable version
- Scala version is NOT in the tag (managed by sbt from build.sbt at runtime)

### GitLab Runner Setup

**IMPORTANT**: The OptiX JNI CI tests require a GitLab Runner with GPU support. The Docker image alone is not sufficient - the runner itself must be configured to expose GPU access to containers.

See [RUNNER_SETUP.md](RUNNER_SETUP.md) for complete instructions on configuring a GitLab Runner with NVIDIA GPU support.

### Building and Pushing the Docker Image

**Build the image locally** (one-time setup, or when Dockerfile changes):

```bash
# Set version tag (update when upgrading CUDA/OptiX/Java/sbt)
export VERSION=12.8-9.0-25-1.11.7

# Build the image (uses NVIDIA CUDA base image, faster than manual install)
docker build -t registry.gitlab.com/lilacashes/menger/optix-cuda:$VERSION -f optix-jni/Dockerfile optix-jni/

# Tag as 'latest'
docker tag registry.gitlab.com/lilacashes/menger/optix-cuda:$VERSION registry.gitlab.com/lilacashes/menger/optix-cuda:latest

# Login to GitLab container registry
docker login registry.gitlab.com
# Username: your GitLab username
# Password: use a Personal Access Token with 'write_registry' scope

# Push both tags
docker push registry.gitlab.com/lilacashes/menger/optix-cuda:$VERSION
docker push registry.gitlab.com/lilacashes/menger/optix-cuda:latest
```

**After pushing a new version**, update `OPTIX_DOCKER_VERSION` in `.gitlab-ci.yml` to match.

### Updating the Image

When you need to update components (e.g., new CUDA/Java/sbt version):

1. Edit `Dockerfile` (update FROM line, version numbers)
2. Update version tag in build commands above
3. Update `OPTIX_DOCKER_VERSION` in `.gitlab-ci.yml`
4. Rebuild and push both tags
5. The CI will automatically use the new image on the next run

**Layer optimization:** The image uses NVIDIA's official CUDA base image and separates components into distinct layers. When upgrading:
- Only Java: Only rebuild/push Java + sbt layers (~500MB)
- Only sbt: Only rebuild/push sbt layer (~100MB)
- CUDA from DockerHub is never pushed to our registry (saves 9GB)

### Image Contents

- Base: `nvidia/cuda:12.8.0-devel-ubuntu24.04` (~9GB, pulled from DockerHub)
- Layer 2: Build tools (cmake, g++, wget, ~200MB)
- Layer 3: OptiX SDK 9.0 (~500MB)
- Layer 4: Java 25 LTS from Eclipse Temurin (~400MB)
- Layer 5: sbt 1.11.7 and git (~100MB)

Total image size: ~11GB (but CUDA layer shared across all NVIDIA images)

## Architecture

### Two-Layer Design

**Low-Level (OptiXContext):**
- Pure OptiX API wrapper, stateless (only holds device context)
- Explicit resource management (create/destroy pairs)
- 1:1 mapping to OptiX operations
- 16 Google Test C++ unit tests

**High-Level (OptiXWrapper):**
- Scene state management (sphere, camera, light, plane, material)
- Convenience methods for scene setup
- Performance: scene data in Params (not SBT) for fast parameter updates
- Uses OptiXContext via composition

### Directory Structure

```
optix-jni/src/main/
  native/
    CMakeLists.txt          # CMake build config
    OptiXContext.cpp        # Low-level OptiX wrapper
    OptiXWrapper.cpp        # High-level scene renderer
    JNIBindings.cpp         # JNI interface
    include/
      OptiXContext.h
      OptiXWrapper.h
      OptiXData.h           # Shared data structures (Params, SBT)
      OptiXConstants.h      # Magic number constants
    shaders/
      sphere_combined.cu    # Combined CUDA shaders (ONLY this file is compiled)
    tests/
      OptiXContextTest.cpp  # Google Test suite (16 tests)

  scala/menger/optix/
    OptiXRenderer.scala     # Main Scala API

target/native/x86_64-linux/
  bin/
    liboptixjni.so          # Compiled JNI shared library
    sphere_combined.ptx     # Compiled CUDA kernels
```

**IMPORTANT:** Only `sphere_combined.cu` is compiled. Separate shader files (`sphere_miss.cu`, `sphere_closesthit.cu`, `sphere_raygen.cu`) are outdated and NOT used. Check `CMakeLists.txt` to verify.

### Key Components

**OptiXContext** (`OptiXContext.h/.cpp`):
- `initialize()`/`destroy()` - Device context lifecycle
- `createModuleFromPTX()`/`destroyModule()` - Shader compilation
- `createRaygenProgramGroup()`, `createMissProgramGroup()`, `createHitgroupProgramGroup()`
- `createPipeline()`/`destroyPipeline()` - Pipeline assembly
- `buildCustomPrimitiveGAS()`/`destroyGAS()` - Geometry acceleration
- `createRaygenSBTRecord()`, `createMissSBTRecord()`, `createHitgroupSBTRecord()`
- `launch()` - OptiX kernel execution

**OptiXWrapper** (`OptiXWrapper.h/.cpp`):
- `setSphere()`, `setSphereColor()`, `setIOR()`, `setScale()` - Scene config
- `setCamera()`, `setLight()`, `setPlane()` - Environment
- `render()` - High-level rendering (builds pipeline if needed, returns RGBA image)

**JNI Interface** (`JNIBindings.cpp`, `OptiXRenderer.scala`):
- Per-instance native handles (multiple renderer instances supported)
- Error propagation via return codes
- Functional-style library loading with Try monad

**Shaders** (`sphere_combined.cu`):
- Ray generation, miss, closest hit, custom sphere intersection
- Reads scene data from Params struct (not SBT) for performance
- Compiled to PTX at build time

### Build Process

1. sbt-jni plugin detects `CMakeLists.txt`
2. CMake compiles C++/CUDA to PTX
3. Google Test suite runs (16 C++ tests)
4. Artifacts copied to `target/native/*/bin/`
5. Scala loads native library via functional loader with Try monad

**Note:** sbt-jni runs CMake every compile, but CMake skips unchanged files. Minimal output via `-Wno-dev`, `--log-level=WARNING`, `CMAKE_INSTALL_MESSAGE LAZY`.

## Local Development

For local development with OptiX support, see the main project's `GPU_DEVELOPMENT.md`.
