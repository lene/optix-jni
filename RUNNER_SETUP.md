# GitLab Runner GPU Configuration

This document explains how to configure a GitLab Runner to support GPU-accelerated CI jobs for OptiX JNI testing.

## Overview

The `Test:OptiXJni` CI job requires a GitLab Runner with:
- NVIDIA GPU hardware
- NVIDIA Driver installed
- NVIDIA Container Toolkit (nvidia-docker2)
- Proper runner configuration to expose GPU to Docker containers

## Prerequisites

### Hardware
- NVIDIA GPU (any CUDA-capable GPU, e.g., GTX 1060, RTX 3060, Tesla T4, A10G)
- Linux host system (Ubuntu 22.04/24.04 recommended)

### Software
- Docker Engine 19.03+ (for `--gpus` flag support)
- NVIDIA Driver (version compatible with CUDA 12.8)
- GitLab Runner 13.9+ (for GPU support)

## Installation Steps

**Note**: These instructions use `sudo` for elevated privileges. If your system doesn't have `sudo` configured, use `pkexec` instead (e.g., `pkexec systemctl restart docker` instead of `sudo systemctl restart docker`).

### 1. Install NVIDIA Driver

```bash
# Check if driver is already installed
nvidia-smi

# If not installed, install the driver (Ubuntu)
sudo apt-get update
sudo apt-get install -y nvidia-driver-550  # or latest version

# Reboot to load the driver
sudo reboot

# Verify installation
nvidia-smi
```

### 2. Install Docker

```bash
# Remove old versions
sudo apt-get remove docker docker-engine docker.io containerd runc

# Install Docker Engine
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# Verify installation
sudo docker run hello-world
```

### 3. Install NVIDIA Container Toolkit

```bash
# Configure the repository
curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey | sudo gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg

curl -s -L https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list | \
  sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' | \
  sudo tee /etc/apt/sources.list.d/nvidia-container-toolkit.list

# Install the toolkit
sudo apt-get update
sudo apt-get install -y nvidia-container-toolkit

# Configure Docker to use the NVIDIA runtime
sudo nvidia-ctk runtime configure --runtime=docker

# Restart Docker daemon
sudo systemctl restart docker
```

### 4. Verify GPU Access in Docker

```bash
# Test that Docker can access the GPU
sudo docker run --rm --gpus all nvidia/cuda:12.8.0-devel-ubuntu24.04 nvidia-smi

# You should see GPU information similar to running nvidia-smi directly
```

### 5. Install GitLab Runner

```bash
# Download and install GitLab Runner
curl -L "https://packages.gitlab.com/install/repositories/runner/gitlab-runner/script.deb.sh" | sudo bash
sudo apt-get install gitlab-runner

# Verify installation
gitlab-runner --version
```

### 6. Register the Runner

```bash
# Register the runner with your GitLab instance
sudo gitlab-runner register

# When prompted, provide:
# - GitLab instance URL: https://gitlab.com/ (or your instance URL)
# - Registration token: (from your project's Settings > CI/CD > Runners)
# - Description: nvidia-gpu-runner
# - Tags: nvidia
# - Executor: docker
# - Default Docker image: ubuntu:24.04
```

### 7. Configure Runner for GPU Access

Edit the GitLab Runner configuration file:

```bash
sudo nano /etc/gitlab-runner/config.toml
```

Add the `gpus` configuration to the `[runners.docker]` section:

```toml
[[runners]]
  name = "nvidia-gpu-runner"
  url = "https://gitlab.com/"
  token = "YOUR_RUNNER_TOKEN"
  executor = "docker"
  [runners.docker]
    tls_verify = false
    image = "ubuntu:24.04"
    privileged = false
    disable_entrypoint_overwrite = false
    oom_kill_disable = false
    disable_cache = false
    volumes = ["/cache"]
    shm_size = 0
    # Enable GPU support - THIS IS CRITICAL
    gpus = "all"
```

**Important**:
- The `gpus = "all"` line is critical for GPU access
- OptiX libraries are automatically mounted when using `NVIDIA_DRIVER_CAPABILITIES` in the CI job (no manual volume mounts needed)

### 8. Restart GitLab Runner

```bash
sudo gitlab-runner restart

# Verify the runner is active
sudo gitlab-runner status
```

## Verification

### Test GPU Access in CI

Create a test job in your `.gitlab-ci.yml`:

```yaml
test_gpu:
  tags:
    - nvidia
  image: nvidia/cuda:12.8.0-devel-ubuntu24.04
  script:
    - nvidia-smi
```

The job should succeed and display GPU information.

### Test OptiX JNI Locally

Before pushing to CI, test the complete setup locally with Docker:

```bash
# Clean any cached build artifacts
rm -rf optix-jni/target/native

# Run OptiX JNI tests in Docker (mimics CI environment)
docker run --rm --gpus all \
  -v "$PWD:/workspace" -w /workspace \
  -e NVIDIA_DRIVER_CAPABILITIES=graphics,compute,utility \
  registry.gitlab.com/lilacashes/menger/optix-cuda:latest bash -c "
    # Create RTX library symlink if needed
    if [ -f /usr/lib/x86_64-linux-gnu/libnvidia-rtcore.so.* ] && [ ! -f /usr/lib/x86_64-linux-gnu/libnvidia-rtcore.so.1 ]; then
      ln -sf /usr/lib/x86_64-linux-gnu/libnvidia-rtcore.so.* /usr/lib/x86_64-linux-gnu/libnvidia-rtcore.so.1
    fi
    ldconfig || true

    # Run tests
    ENABLE_OPTIX_JNI=true sbt 'project optixJni' test
"
```

All 15 OptiX JNI tests should pass if the setup is correct.

## Troubleshooting

### Error: "could not select device driver with capabilities: [[gpu]]"

**Cause**: Docker doesn't have GPU runtime configured.

**Solution**:
1. Verify NVIDIA Container Toolkit is installed: `which nvidia-ctk`
2. Reconfigure Docker runtime: `sudo nvidia-ctk runtime configure --runtime=docker`
3. Restart Docker: `sudo systemctl restart docker`
4. Restart GitLab Runner: `sudo gitlab-runner restart`

### Error: "nvidia-smi: command not found" in CI job

**Cause**: Using a base image without NVIDIA utilities.

**Solution**: Use a CUDA-enabled base image like `nvidia/cuda:12.1.0-base-ubuntu22.04` or install `nvidia-utils` in your image.

### GPU not visible in nvidia-smi output

**Cause**: Runner config.toml missing `gpus = "all"` setting.

**Solution**: Add `gpus = "all"` to `[runners.docker]` section and restart runner.

### Runner not picking up jobs with 'nvidia' tag

**Cause**: Runner not registered with the correct tag.

**Solution**:
1. Check runner tags in GitLab UI: Settings > CI/CD > Runners
2. Add 'nvidia' tag if missing
3. Or re-register runner with correct tags

### Error: "OptiX call 'optixInit()' failed: OPTIX_ERROR_LIBRARY_NOT_FOUND" or "Error initializing RTX library"

**Cause**: OptiX 7.0+ runtime libraries (`libnvoptix.so`, `libnvidia-rtcore.so`) from the NVIDIA driver are not accessible inside the container.

**Background**: Since OptiX 6.0+, the runtime libraries are part of the NVIDIA driver, not the OptiX SDK. Containers need access to these driver libraries to use OptiX.

**Solution**: Use the `NVIDIA_DRIVER_CAPABILITIES` environment variable to automatically mount driver libraries:

1. **In your CI job** (`.gitlab-ci.yml`), add:
   ```yaml
   Test:OptiXJni:
     variables:
       NVIDIA_DRIVER_CAPABILITIES: "graphics,compute,utility"
     before_script:
       # Create symlink for RTX library if needed (some drivers use versioned names)
       - |
         if [ -f /usr/lib/x86_64-linux-gnu/libnvidia-rtcore.so.* ] && [ ! -f /usr/lib/x86_64-linux-gnu/libnvidia-rtcore.so.1 ]; then
           ln -sf /usr/lib/x86_64-linux-gnu/libnvidia-rtcore.so.* /usr/lib/x86_64-linux-gnu/libnvidia-rtcore.so.1
         fi
       - ldconfig || true
   ```

2. **In GitLab Runner config** (`/etc/gitlab-runner/config.toml`), ensure GPU access is enabled:
   ```toml
   [[runners]]
     [runners.docker]
       gpus = "all"
       # No manual volume mounts needed for OptiX libraries!
   ```

3. **Verify libraries on host** (for debugging):
   ```bash
   ls -la /usr/lib/x86_64-linux-gnu/libnvoptix.so*
   ls -la /usr/lib/x86_64-linux-gnu/libnvidia-rtcore.so*
   ```

**Why this works**: The `NVIDIA_DRIVER_CAPABILITIES` environment variable tells the NVIDIA Container Toolkit which driver subsystems to expose. Setting it to `"graphics,compute,utility"` automatically mounts all required OptiX and RTX libraries into the container at their standard locations.

**Alternative (if environment variable doesn't work)**: Manually mount the libraries in runner config:
```toml
volumes = [
  "/cache",
  "/usr/lib/x86_64-linux-gnu/libnvoptix.so.1:/usr/lib/x86_64-linux-gnu/libnvoptix.so.1:ro",
  "/usr/lib/x86_64-linux-gnu/libnvidia-rtcore.so.1:/usr/lib/x86_64-linux-gnu/libnvidia-rtcore.so.1:ro"
]
```

## Alternative: Using GitLab SaaS GPU Runners

GitLab.com offers hosted runners with GPU support (requires GitLab Premium/Ultimate):

```yaml
test_gpu:
  tags:
    - saas-linux-medium-amd64-gpu-standard  # GitLab SaaS GPU runner
  image: nvidia/cuda:12.8.0-devel-ubuntu24.04
  script:
    - nvidia-smi
```

See: https://docs.gitlab.com/ee/ci/runners/hosted_runners/gpu_enabled.html

## Security Considerations

- GPU runners should be on a secure network
- Consider using runner registration tokens with expiration
- Limit runner to specific projects/groups
- Monitor GPU usage and costs
- Keep NVIDIA drivers and Container Toolkit updated

## Cost Optimization

For occasional GPU testing, consider:
- Using shared runners (if available)
- Spot/preemptible GPU instances (AWS, GCP)
- Auto-scaling runner configuration
- Using AWS EC2 spot instances (see `GPU_DEVELOPMENT.md`)

## References

- [GitLab GPU Configuration Documentation](https://docs.gitlab.com/runner/configuration/gpus/)
- [NVIDIA Container Toolkit Installation Guide](https://docs.nvidia.com/datacenter/cloud-native/container-toolkit/install-guide.html)
- [Docker GPU Support Documentation](https://docs.docker.com/config/containers/resource_constraints/#gpu)
