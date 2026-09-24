# OptiX JNI

Generic JNI bindings for the NVIDIA OptiX ray tracing API, usable from any JVM language —
spheres, meshes, planes, cones, curves, textures, PBR materials, caustics (progressive photon
mapping), the AI denoiser, and a custom-geometry SPI for registering your own intersection
programs. It contains no Menger-specific types; the [`menger`](https://github.com/lene/menger)
renderer is its main consumer and extends it through the SPI. Development happens in the
[`menger-toplevel`](https://github.com/lene/menger-toplevel) workspace.

## Standalone Usage

`optix-jni` publishes JNI bindings and native resources for Linux x86_64.
It depends on `menger-common` for shared scene types such as `Color`, `Vector`,
`ImageSize`, and `Material`.

Published to Maven Central. The versions below are the current releases at the time of
writing — check [CHANGELOG.md](CHANGELOG.md) (and menger-common's) for newer ones.

### sbt

```scala
ThisBuild / scalaVersion := "3.8.3"

libraryDependencies ++= Seq(
  "io.github.lene" %% "menger-common" % "0.2.0",
  "io.github.lene" % "optix-jni" % "0.3.3"
)
```

### Maven

```xml
<dependencies>
  <dependency>
    <groupId>io.github.lene</groupId>
    <artifactId>menger-common_3</artifactId>
    <version>0.2.0</version>
  </dependency>
  <dependency>
    <groupId>io.github.lene</groupId>
    <artifactId>optix-jni</artifactId>
    <version>0.3.3</version>
  </dependency>
</dependencies>
```

### Gradle Kotlin DSL

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.lene:menger-common_3:0.2.0")
    implementation("io.github.lene:optix-jni:0.3.3")
}
```

### Runtime Requirements

- Linux x86_64.
- NVIDIA GPU with OptiX support.
- NVIDIA driver new enough for CUDA 13.x runtime and OptiX SDK 9.0 (driver ≥ 580.65).
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
  -Djava.library.path=/path/to/optix-jni/target/native/x86_64-linux/bin \
  -cp your-app.jar your.Main
```

When running through sbt:

```bash
sbt -Djava.library.path=/path/to/optix-jni/target/native/x86_64-linux/bin run
```

(Here `/path/to/optix-jni` is your clone of this repo after `sbt nativeCompile`. To test an
unpublished build from a consumer such as menger, `sbt publishLocal` here and point the
consumer's version pin at the local version.)

If CUDA libraries are not in the system linker cache, also set:

```bash
export LD_LIBRARY_PATH=/usr/local/cuda/lib64:$LD_LIBRARY_PATH
```

### Basic sphere render

```scala
import io.github.lene.optix.OptiXRenderer
import io.github.lene.optix.Material
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
  renderer.addCurveInstance(
    points = Array(
      -1.2f, -0.35f, 0f,
      -0.4f,  0.65f, 0f,
       0.4f,  0.65f, 0f,
       1.2f, -0.35f, 0f
    ),
    widths = Array(0.12f, 0.12f, 0.12f, 0.12f),
    material = Material(Color(0.1f, 0.9f, 0.3f, 1f), ior = 1.0f)
  )
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

### OptiX denoiser

Denoising is opt-in. `OptiXRenderer.setDenoisingEnabled(true)` renders the
frame into float4 color and guide AOV buffers, invokes the OptiX HDR denoiser,
then converts the denoised float output back to RGBA8 bytes. Leaving denoising
disabled keeps the existing byte render path unchanged.

The low-level `NativeOptiXApi` denoiser methods and the `OptiXDenoiser` Scala
wrapper expect row-major linear HDR RGBA float arrays (`width * height * 4`).
Albedo and normal guides are optional at creation time; when enabled, the guide
arrays must use the same dense float4 layout. Guide AOVs improve edge stability,
especially around silhouettes and textured materials.

The denoiser allocates OptiX state, scratch, HDR intensity, and image buffers.
Expect roughly 100-400 MB of additional GPU memory depending on resolution and
guide usage.

---

## CI Configuration

### GPU runner setup

CI for this repo (and for menger and menger-common) runs on **GitHub Actions**: hosted
`ubuntu-latest` jobs for cppcheck/scalafix/doc checks, and a self-hosted NVIDIA runner (label
`nvidia`) for native build, GPU tests and publishing. GPU jobs run **bare on the runner host**,
which must provide CUDA 13.x and the OptiX SDK. Runner registration, hardening and host
dependencies are documented in the workspace repo: `../infra/ci-runners/README.md` and
`../infra/RUNNER_SETUP.md`. (`RUNNER_SETUP.md` in this repo is a pointer to those.)

### Docker Image (optional, not used by CI)

`Dockerfile` builds an image based on NVIDIA's official CUDA image with the OptiX SDK, Java 25
and sbt pre-installed. It was the job container of menger's former GitLab CI; no current
pipeline uses it. It remains useful for reproducible local containerized builds.

**Image Versioning:**

Images are tagged with version numbers of all pre-installed components:
- Format: `{CUDA}-{OptiX}-{Java}-{sbt}`
- Example: `13.2-9.0-25-1.12.0` = CUDA 13.2, OptiX 9.0, Java 25, sbt 1.12.0
- The `latest` tag always points to the newest stable version
- Scala version is NOT in the tag (managed by sbt from build.sbt at runtime)

### Building and Pushing the Docker Image

**Build the image locally** (from the root of this repo):

```bash
# Set version tag (update when upgrading CUDA/OptiX/Java/sbt)
export VERSION=13.2-9.0-25-1.12.0

# Build the image (uses NVIDIA CUDA base image, faster than manual install)
docker build -t registry.gitlab.com/lilacashes/menger/optix-cuda:$VERSION -f Dockerfile .

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

(The registry path is historical — it is where menger's retired GitLab CI pulled the image
from. Push elsewhere if you need a shared copy.)

#### Building against a different CUDA version

The toolkit must be **13.x**: `src/main/native/CMakeLists.txt` pins
`find_package(CUDAToolkit 13.0 REQUIRED)`, and the artifacts published to Maven Central link
`libcudart.so.13`, so a 12.x build would not reproduce them. That is a project decision
(standardized in Sprint 27 — see menger arc42 TC-4), **not** an OptiX constraint: OptiX 9.0
runs fine against CUDA 12.x. The driver floor is separate again (R570+ for OptiX 9.0, R590+
for 9.1; CUDA 13.0 itself wants ≥580.65).

13.2 specifically is what the CI runners and dev machines have installed. To build against a
different 13.x toolkit, override the build arg and tag accordingly:

```bash
export VERSION_ALT=13.0-9.0-25-1.12.0

docker build --build-arg CUDA_VERSION=13.0.0 \
  -t registry.gitlab.com/lilacashes/menger/optix-cuda:$VERSION_ALT \
  -f Dockerfile .

docker push registry.gitlab.com/lilacashes/menger/optix-cuda:$VERSION_ALT
```

### Updating the Image

When you need to update components (e.g., new CUDA/Java/sbt version):

1. Edit `Dockerfile` (update FROM line, version numbers)
2. Update version tag in build commands above
3. Rebuild (and push, if you keep a shared copy)
4. Keep the CI runner hosts in step separately — they don't use this image (see
   "GPU runner setup" above)

**Layer optimization:** The image uses NVIDIA's official CUDA base image and separates components into distinct layers. When upgrading:
- Only Java: Only rebuild/push Java + sbt layers (~500MB)
- Only sbt: Only rebuild/push sbt layer (~100MB)
- CUDA from DockerHub is never pushed to our registry (saves 9GB)

### Image Contents

- Base: `nvidia/cuda:13.2.0-devel-ubuntu24.04` (~9GB, pulled from DockerHub)
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
- Scene state management (sphere, camera, light, plane, curves, material)
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
      optix_shaders.cu      # Aggregated CUDA shader entrypoint
      hit_curve.cu          # Built-in round cubic B-spline curve hit shaders
    tests/
      OptiXContextTest.cpp  # Google Test suite (16 tests)

  scala/io/github/lene/optix/
    OptiXRenderer.scala     # Main Scala API

target/native/x86_64-linux/
  bin/
    liboptixjni.so          # Compiled JNI shared library
    optix_shaders.ptx       # Compiled CUDA kernels
```

**IMPORTANT:** `optix_shaders.cu` is the compiled CUDA entrypoint. The files under
`native/shaders/` are included from that aggregate file; do not compile them
individually.

### Key Components

**OptiXContext** (`OptiXContext.h/.cpp`):
- `initialize()`/`destroy()` - Device context lifecycle
- `createModuleFromPTX()`/`destroyModule()` - Shader compilation
- `createRaygenProgramGroup()`, `createMissProgramGroup()`, `createHitgroupProgramGroup()`
- `createCurveHitgroupProgramGroup()` - Built-in round cubic B-spline curve hit groups
- `createPipeline()`/`destroyPipeline()` - Pipeline assembly
- `buildCustomPrimitiveGAS()`, `buildCurveGAS()`/`destroyGAS()` - Geometry acceleration
- `createRaygenSBTRecord()`, `createMissSBTRecord()`, `createHitgroupSBTRecord()`
- `launch()` - OptiX kernel execution

**OptiXWrapper** (`OptiXWrapper.h/.cpp`):
- `setSphere()`, `setSphereColor()`, `setIOR()`, `setScale()` - Scene config
- `setCamera()`, `setLight()`, `setPlane()` - Environment
- `addCurveInstance(points, widths, material)` - World-space cubic B-spline curves
- `render()` - High-level rendering (builds pipeline if needed, returns RGBA image)

**JNI Interface** (`JNIBindings.cpp`, `OptiXRenderer.scala`):
- Per-instance native handles (multiple renderer instances supported)
- Error propagation via return codes
- Functional-style library loading with Try monad

**Shaders** (`optix_shaders.cu` plus included shader files):
- Ray generation, miss, closest hit, custom primitive intersection, built-in curve hit groups
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

Setting up CUDA, OptiX, the driver, Java and sbt from scratch is documented once for the whole
workspace in `../docs/INSTALLATION_FROM_SCRATCH.md`.

Build and native-linkage troubleshooting — PTX-not-found, `libcudart.so.13` linkage, stale
`optix-jni/target/native`, OptiX/driver mismatches — is collected in
`../menger/docs/TROUBLESHOOTING.md`. It is written from a full workspace checkout (it refers to
`optix-jni/` as a sibling), so run those commands from the workspace root.

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
