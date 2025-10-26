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

## Local Development

For local development with OptiX support, see the main project's `GPU_DEVELOPMENT.md`.
