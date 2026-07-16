FROM ghcr.io/cirruslabs/android-sdk:36

ARG NDK_VERSION=29.0.14206865
ARG RUST_VERSION=1.88.0

ENV RUSTUP_HOME=/opt/rustup \
    CARGO_HOME=/opt/cargo \
    PATH=/opt/cargo/bin:${PATH}

RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && yes | sdkmanager --licenses >/dev/null \
    && sdkmanager "ndk;${NDK_VERSION}" \
    && curl --proto '=https' --tlsv1.2 -fsSL https://sh.rustup.rs \
       | sh -s -- -y --profile minimal --default-toolchain "${RUST_VERSION}" \
    && rustup component add rustfmt \
    && rustup target add x86_64-linux-android aarch64-linux-android

RUN apt-get update \
    && apt-get install -y --no-install-recommends \
       curl libarchive-tools meson ninja-build patch patchelf pkg-config python3-yaml \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace
