# Docker image for OptiX JNI CI builds
# Pre-installs CUDA, OptiX, Java, and sbt to speed up CI jobs
#
# Layer structure (optimized for caching):
#   1. CUDA 12.8 (from NVIDIA base image, ~9GB)
#   2. Build tools (cmake, g++, ~200MB)
#   3. OptiX SDK 9.0 (~500MB)
#   4. Java 25 (~400MB)
#   5. sbt 1.11.7 (~100MB)
#
# Image tag format: {CUDA}-{OptiX}-{Java}-{sbt}
# Example: registry.gitlab.com/lilacashes/menger/optix-cuda:12.8-9.0-25-1.11.7
#
FROM nvidia/cuda:12.8.0-devel-ubuntu24.04

# Avoid interactive prompts during package installation
ENV DEBIAN_FRONTEND=noninteractive

# Layer 2: Build tools
# Install CMake, g++, and other build dependencies
RUN apt-get update && apt-get install -y \
    cmake \
    g++ \
    wget \
    gnupg2 \
    lsb-release \
    ca-certificates \
    apt-transport-https \
    && rm -rf /var/lib/apt/lists/*

# Layer 3: OptiX SDK 9.0
# The OptiX SDK installer is stored in this directory (checked into git)
# When installed with --prefix=/usr/local, it extracts directly to /usr/local/
# (not to /usr/local/NVIDIA-OptiX-SDK-9.0.0-linux64-x86_64)
COPY NVIDIA-OptiX-SDK-9.0.0-linux64-x86_64.sh /tmp/optix-installer.sh
RUN chmod +x /tmp/optix-installer.sh && \
    /tmp/optix-installer.sh --skip-license --prefix=/usr/local && \
    rm /tmp/optix-installer.sh

# Set OptiX environment variable for CMake auto-detection
ENV OPTIX_ROOT=/usr/local

# Layer 4: Java 25 (LTS, supported until 2031)
# Install from Eclipse Temurin (formerly AdoptOpenJDK)
RUN wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | gpg --dearmor > /usr/share/keyrings/adoptium-archive-keyring.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/adoptium-archive-keyring.gpg] https://packages.adoptium.net/artifactory/deb $(lsb_release -cs) main" > /etc/apt/sources.list.d/adoptium.list && \
    apt-get update && apt-get install -y temurin-25-jdk && \
    rm -rf /var/lib/apt/lists/*

# Set JAVA_HOME for JNI detection
ENV JAVA_HOME=/usr/lib/jvm/temurin-25-jdk-amd64

# Layer 5: sbt 1.11.7 and git
# sbt manages Scala version (downloads from build.sbt at runtime)
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
