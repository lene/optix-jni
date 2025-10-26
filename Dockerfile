# Docker image for OptiX JNI CI builds
# Pre-installs CUDA toolkit and build dependencies to speed up CI jobs
FROM ubuntu:24.04

# Avoid interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

# Install basic dependencies
RUN apt-get update && apt-get install -y \
    wget \
    gnupg2 \
    lsb-release \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Add CUDA repository and install CUDA toolkit
RUN wget https://developer.download.nvidia.com/compute/cuda/repos/ubuntu2204/x86_64/cuda-keyring_1.1-1_all.deb && \
    dpkg -i cuda-keyring_1.1-1_all.deb && \
    rm cuda-keyring_1.1-1_all.deb && \
    apt-get update && \
    apt-get install -y \
        cuda-toolkit-12-8 \
        cmake \
        g++ \
    && rm -rf /var/lib/apt/lists/*

# Set CUDA environment variables
ENV CUDA_HOME=/usr/local/cuda-12.8
ENV PATH=${CUDA_HOME}/bin:${PATH}
ENV LD_LIBRARY_PATH=${CUDA_HOME}/lib64:${LD_LIBRARY_PATH}

# Verify CUDA installation
RUN nvcc --version

# Install OptiX SDK (TEMPORARY SOLUTION - see issue for proper implementation)
#
# CURRENT APPROACH: OptiX installer must be copied to this directory before building
# LIMITATIONS:
#   - Not portable (requires manual file copy)
#   - Not suitable for CI/CD (installer not in version control)
#   - Requires NVIDIA developer account to download
#
# TODO: Store OptiX SDK in GitLab Package Registry with proper authentication
#       See related GitLab issue for implementation plan
#
# To build this image:
#   1. Download OptiX SDK from https://developer.nvidia.com/optix
#   2. Copy installer to this directory: cp ~/Downloads/NVIDIA-OptiX-SDK-*.sh optix-jni/
#   3. Build: docker build -t optix-cuda optix-jni/
#
COPY NVIDIA-OptiX-SDK-9.0.0-linux64-x86_64.sh /tmp/optix-installer.sh
RUN chmod +x /tmp/optix-installer.sh && \
    /tmp/optix-installer.sh --skip-license --prefix=/usr/local && \
    rm /tmp/optix-installer.sh

# Set OptiX environment variable for CMake auto-detection
ENV OPTIX_ROOT=/usr/local/NVIDIA-OptiX-SDK-9.0.0-linux64-x86_64

# Create a non-root user for running builds (optional, for security)
RUN useradd -m -s /bin/bash builder

WORKDIR /builds

# Default command
CMD ["/bin/bash"]
