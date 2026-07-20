# Docker image for OptiX JNI CI builds
# Pre-installs CUDA, OptiX, Java, sbt, and common CI tools to speed up CI jobs
#
# Layer structure (optimized for caching):
#   1. CUDA (from NVIDIA base image, ~9GB)
#   2. Build tools (cmake, g++, ~200MB)
#   3. OptiX SDK (~500MB)
#   4. Java 25 (~400MB)
#   5. sbt 1.12.0 (~100MB)
#   6. CI tools (xvfb, valgrind, curl, etc., ~150MB)
#
# Image tag format: {CUDA}-{OptiX}-{Java}-{sbt}
# Example: registry.gitlab.com/lilacashes/menger/optix-cuda:13.2-9.0-25-1.12.0
# Build args:
#   CUDA_VERSION   (default: 13.2.0) — must be 13.x: src/main/native/CMakeLists.txt
#                  pins find_package(CUDAToolkit 13.0 REQUIRED) and the published
#                  artifacts link libcudart.so.13. This is a project decision
#                  (standardized in Sprint 27, see menger arc42 TC-4), NOT an OptiX
#                  requirement — OptiX 9.0 itself is fine on CUDA 12.x.
#   OPTIX_VERSION  (default: 9.0.0) — must match installer filename in build context
#
ARG CUDA_VERSION=13.2.0
ARG OPTIX_VERSION=9.0.0
FROM nvidia/cuda:${CUDA_VERSION}-devel-ubuntu24.04

# Avoid interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

# Layer 2: Build tools
# Install CMake, g++, and other build dependencies
RUN apt-get update && apt-get install -y \
    apt-transport-https \
    ca-certificates \
    cmake \
    g++ \
    gnupg2 \
    lsb-release \
    wget \
    && rm -rf /var/lib/apt/lists/*

# Layer 3: OptiX SDK
# Create target directory explicitly and install there, then create symlink
ARG OPTIX_VERSION
COPY NVIDIA-OptiX-SDK-${OPTIX_VERSION}-linux64-x86_64.sh /tmp/optix-installer.sh
RUN chmod +x /tmp/optix-installer.sh ; \
    mkdir -p /usr/local/NVIDIA-OptiX-SDK-${OPTIX_VERSION}-linux64-x86_64 ; \
    /tmp/optix-installer.sh --skip-license --prefix=/usr/local/NVIDIA-OptiX-SDK-${OPTIX_VERSION}-linux64-x86_64 ; \
    ln -s /usr/local/NVIDIA-OptiX-SDK-${OPTIX_VERSION}-linux64-x86_64 /usr/local/optix ; \
    rm /tmp/optix-installer.sh

# Set OptiX and CUDA environment variables for CMake auto-detection
ENV OPTIX_ROOT=/usr/local/optix
ENV CUDA_HOME=/usr/local/cuda

# Layer 4: Java 25 (LTS, supported until 2031)
# Install from Eclipse Temurin (formerly AdoptOpenJDK)
RUN wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor > /usr/share/keyrings/adoptium-archive-keyring.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/adoptium-archive-keyring.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && apt-get install -y temurin-25-jdk && \
    rm -rf /var/lib/apt/lists/*

# Set JAVA_HOME for JNI detection
ENV JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64

# Layer 5: sbt 1.12.0 and git
# sbt manages Scala version (downloads from build.sbt at runtime)
RUN echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list && \
    wget -qO - "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | gpg --dearmor > /usr/share/keyrings/sbt-archive-keyring.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/sbt-archive-keyring.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" > /etc/apt/sources.list.d/sbt.list && \
    apt-get update && apt-get install -y sbt git && \
    rm -rf /var/lib/apt/lists/*

# Layer 6: CI tools commonly used across jobs
# Pre-install packages to speed up CI jobs and avoid repeated apt-get updates
RUN apt-get update && apt-get install -y \
    bc \
    curl \
    imagemagick \
    mesa-utils \
    time \
    unzip \
    clang-tidy \
    cppcheck \
    valgrind \
    x11-xserver-utils \
    xvfb \
    && rm -rf /var/lib/apt/lists/*

# Create a non-root user for running builds (optional, for security)
RUN useradd -m -s /bin/bash builder

WORKDIR /builds

# Default command
CMD ["/bin/bash"]
