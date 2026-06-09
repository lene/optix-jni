# OptiX JNI Module

This module provides JNI bindings for NVIDIA OptiX ray tracing API.

## Standalone Usage

`optix-jni` publishes JNI bindings and native resources for Linux x86_64.
It depends on `menger-common` for shared scene types such as `Color`, `Vector`,
`ImageSize`, and `Material`.

Publication target: Maven Central.

### sbt

```scala
ThisBuild / scalaVersion := "3.8.3"

libraryDependencies ++= Seq(
  "io.github.lene" %% "menger-common" % "0.1.0",
  "io.github.lene" % "optix-jni" % "0.1.0"
)
```

### Maven

```xml
<dependencies>
  <dependency>
    <groupId>io.github.lene</groupId>
    <artifactId>menger-common_3</artifactId>
    <version>0.1.0</version>
  </dependency>
  <dependency>
    <groupId>io.github.lene</groupId>
    <artifactId>optix-jni</artifactId>
    <version>0.1.0</version>
  </dependency>
</dependencies>
```

### Gradle Kotlin DSL

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.lene:menger-common_3:0.1.0")
    implementation("io.github.lene:optix-jni:0.1.0")
}
```

### Runtime Requirements

- Linux x86_64.
- NVIDIA GPU with OptiX support.
- NVIDIA driver new enough for CUDA 12.8 runtime and OptiX SDK 9.0.
- CUDA runtime libraries available to the dynamic linker. In local shells this is
  usually `LD_LIBRARY_PATH=/usr/local/cuda/lib64` unless the system linker cache
  already contains CUDA.
- For containerized execution, expose GPU devices and set
  `NVIDIA_DRIVER_CAPABILITIES=graphics,compute,utility`.

### JVM Flags and Native Library Loading

Published `optix-jni` artifacts bundle `liboptixjni.so` and `optix_shaders.ptx`
as classpath resources. In that case no `java.library.path` flag is normally
needed.

For local unpublished builds, point the JVM at the native build output:

```bash
java \
  -Djava.library.path=/path/to/menger/optix-jni/target/native/x86_64-linux/bin \
  -cp your-app.jar your.Main
```

When running through sbt:

```bash
sbt -Djava.library.path=/path/to/menger/optix-jni/target/native/x86_64-linux/bin run
```

If CUDA libraries are not in the system linker cache, also set:

```bash
export LD_LIBRARY_PATH=/usr/local/cuda/lib64:$LD_LIBRARY_PATH
```

### Basic sphere render

```scala
import io.github.lene.optix.OptiXRenderer
import menger.common.Color
import menger.common.ImageSize
import menger.common.Vector

if !OptiXRenderer.isLibraryLoaded then
  sys.error("OptiX native library failed to load; check CUDA and OptiX setup")

val renderer = new OptiXRenderer()
try
  if !renderer.initialize() then
    sys.error("OptiX renderer failed to initialize")

  renderer.setSphere(Vector[3](0f, 0f, 0f), radius = 1f)
  renderer.setSphereColor(Color(1f, 0.5f, 0.2f, 1f))
  renderer.setCamera(
    eye = Vector[3](0f, 0f, 5f),
    lookAt = Vector[3](0f, 0f, 0f),
    up = Vector[3](0f, 1f, 0f),
    horizontalFovDegrees = 45f
  )

  val result = renderer.renderWithStats(ImageSize(800, 600))
  if result == null then
    sys.error("render failed")

  println(s"Rendered ${result.image.length} RGBA bytes")
finally
  renderer.dispose()
```

### Alpha convention

`alpha = 0.0` → fully transparent. `alpha = 1.0` → fully opaque.

---

## CI Configuration

GitHub Actions runs on self-hosted Linux x64 NVIDIA runners.

- Every branch push and every pull request runs `quality` and `coverage`.
- `quality` checks the sbt launcher, Scalafix, native C++ tests, Scala/GPU tests,
  packaging, and Scaladoc via `sbt clean "scalafix --check" test package doc`.
- `coverage` runs `sbt clean coverage test coverageReport`, then enforces the
  repository coverage policy with `scripts/check-coverage-policy.sh`.
- Pushes to `main` run the release gate after quality and coverage. The gate
  finds the merged PR associated with the pushed commit. If the PR title contains
  `NORELEASE`, release tagging and publication are skipped. Otherwise the gate
  requires a non-snapshot version whose tag is not already present, then creates
  tag `${version}`.
- Release tags publish to Maven Central after verifying that the tag is a
  semantic release tag and that the tagged commit is reachable from `origin/main`.

Maven Central publishing expects GitHub secrets `SONATYPE_USERNAME`,
`SONATYPE_PASSWORD`, `PGP_SECRET`, and `PGP_PASSPHRASE`. `PGP_SECRET` is a
base64-encoded private GPG key.

Install the local pre-push hook with:

```bash
git config core.hooksPath .git_hooks
```

The hook writes `/tmp/optix-jni-pre-push.log` and runs the local gate:
version policy, sbt launcher check, quality/test/package/doc, coverage report,
and coverage policy.

### Legacy Docker Image Maintenance

The Docker image notes below are legacy GitLab-runner image maintenance docs.
They are not normal GitHub Actions CI jobs and are only relevant when maintaining
old GitLab GPU runner images.

The image is based on NVIDIA's official CUDA image with OptiX SDK, Java 25, and
sbt pre-installed.

**Image Versioning:**

Images are tagged with version numbers of all pre-installed components:
- Format: `{CUDA}-{OptiX}-{Java}-{sbt}`
- Example: `12.8-9.0-25-1.11.7` = CUDA 12.8, OptiX 9.0, Java 25, sbt 1.11.7
- The `latest` tag always points to the newest stable version
- Scala version is NOT in the tag (managed by sbt from build.sbt at runtime)

### Legacy GitLab Runner Setup

The old GitLab runner setup required GPU support. The Docker image alone was not
sufficient because the runner itself had to expose GPU access to containers.

See [RUNNER_SETUP.md](RUNNER_SETUP.md) for archived instructions on configuring a
GitLab Runner with NVIDIA GPU support.

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

These images are not referenced by the active GitHub Actions workflow.

#### Building the CUDA 13 variant

The Dockerfile accepts a `CUDA_VERSION` build arg (default `12.8.0`):

```bash
export VERSION13=13.2-9.0-25-1.12.0

docker build --build-arg CUDA_VERSION=13.2.0 \
  -t registry.gitlab.com/lilacashes/menger/optix-cuda:$VERSION13 \
  -f optix-jni/Dockerfile optix-jni/

docker push registry.gitlab.com/lilacashes/menger/optix-cuda:$VERSION13
```

These images are not referenced by the active GitHub Actions workflow.

### Updating the Image

When you need to update components (e.g., new CUDA/Java/sbt version):

1. Edit `Dockerfile` (update FROM line, version numbers)
2. Update version tag in build commands above
3. Keep local image tags and documentation in sync
4. Rebuild and push both tags
5. Test the image manually on the legacy runner before relying on it

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

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `MENGER_OPTIX_CACHE` | Custom OptiX cache directory path | `/var/tmp/OptixCache_<username>` |

### Cache Management

OptiX uses a disk cache to speed up pipeline compilation. The cache is stored in `/var/tmp/OptixCache_<username>/` by default.

**Cache Corruption Recovery:**
The renderer automatically detects cache corruption (SQLite database errors) and clears the corrupted cache. A fresh cache is rebuilt on the next render.

**Custom Cache Location:**
Set `MENGER_OPTIX_CACHE` to use a different cache directory:
```bash
export MENGER_OPTIX_CACHE=/path/to/cache
```

**Manual Cache Clearing:**
If you encounter persistent issues, manually clear the cache:
```bash
rm -rf /var/tmp/OptixCache_$(whoami)
```
