# OptiX JNI Module

This module provides JNI bindings for NVIDIA OptiX ray tracing API.

## CI Configuration

### Docker Image

The CI uses a pre-built Docker image with CUDA toolkit pre-installed to avoid the 15-20 minute installation time on every job run.

### GitLab Runner Setup

**IMPORTANT**: The OptiX JNI CI tests require a GitLab Runner with GPU support. The Docker image alone is not sufficient - the runner itself must be configured to expose GPU access to containers.

See [RUNNER_SETUP.md](RUNNER_SETUP.md) for complete instructions on configuring a GitLab Runner with NVIDIA GPU support.

### Building and Pushing the Docker Image

**Build the image locally** (one-time setup, or when Dockerfile changes):

```bash
cd optix-jni

# Build the image (takes ~20 minutes - downloads and installs CUDA 12.8)
docker build -t registry.gitlab.com/lilacashes/menger/optix-cuda:latest .

# Login to GitLab container registry
docker login registry.gitlab.com
# Username: your GitLab username
# Password: use a Personal Access Token with 'write_registry' scope

# Push to registry
docker push registry.gitlab.com/lilacashes/menger/optix-cuda:latest
```

### Updating the Image

When you need to update the base image (e.g., new CUDA version):

1. Edit `Dockerfile`
2. Rebuild and push with the commands above
3. The CI will automatically use the new image on the next run

### Image Contents

- Base: Ubuntu 24.04
- CUDA Toolkit 12.8
- CMake
- g++ compiler
- Build tools

The image does NOT include:
- sbt (installed in CI job's before_script)
- Java/JDK (installed in CI job's before_script)
- OptiX SDK (not needed in CI, only required at runtime)

## Local Development

For local development with OptiX support, see the main project's `GPU_DEVELOPMENT.md`.
