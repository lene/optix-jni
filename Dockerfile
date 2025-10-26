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
# When installing with --prefix=/usr/local, OptiX extracts directly to /usr/local/
ENV OPTIX_ROOT=/usr/local

# Install Java 25 from Eclipse Temurin
RUN apt-get update && apt-get install -y gnupg apt-transport-https && \
    wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor > /usr/share/keyrings/adoptium-archive-keyring.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/adoptium-archive-keyring.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && apt-get install -y temurin-25-jdk && \
    rm -rf /var/lib/apt/lists/*

# Set JAVA_HOME for JNI detection
ENV JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64

# Install sbt
RUN echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list && \
    wget -qO - "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | gpg --dearmor > /usr/share/keyrings/sbt-archive-keyring.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/sbt-archive-keyring.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list && \
    apt-get update && apt-get install -y sbt git && \
    rm -rf /var/lib/apt/lists/*

# Create a non-root user for running builds (optional, for security)
RUN useradd -m -s /bin/bash builder

WORKDIR /builds

# Default command
CMD ["/bin/bash"]
