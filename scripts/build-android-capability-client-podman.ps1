param([switch]$RebuildArmImage)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$ArmImage = "localhost/archphene-arm-runtime-builder:latest"

podman image exists $ArmImage
if ($RebuildArmImage -or $LASTEXITCODE -ne 0) {
    podman build -f (Join-Path $Root "containers/arm-runtime-builder.Containerfile") `
        -t $ArmImage $Root
    if ($LASTEXITCODE -ne 0) { throw "AArch64 runtime builder image failed" }
}

$x86 = "mkdir -p tooling/build/android-capability/x86_64 && " +
    "gcc -shared -fPIC -O2 -Wall -Wextra -Werror " +
    "-o tooling/build/android-capability/x86_64/libarchphene_android.so " +
    "native/archphene-android-capability/archphene_android.c && " +
    "readelf -h tooling/build/android-capability/x86_64/libarchphene_android.so " +
    "| grep -F 'Advanced Micro Devices X86-64'"
podman run --rm -v "${Root}:/workspace" -w /workspace `
    docker.io/library/archlinux:base-devel sh -lc $x86
if ($LASTEXITCODE -ne 0) { throw "x86_64 glibc capability client build failed" }

$arm = "mkdir -p tooling/build/android-capability/aarch64 && " +
    "aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -Wextra -Werror " +
    "-o tooling/build/android-capability/aarch64/libarchphene_android.so " +
    "native/archphene-android-capability/archphene_android.c && " +
    "aarch64-linux-gnu-readelf -h " +
    "tooling/build/android-capability/aarch64/libarchphene_android.so " +
    "| grep -F AArch64"
podman run --rm -v "${Root}:/workspace" -w /workspace $ArmImage sh -lc $arm
if ($LASTEXITCODE -ne 0) { throw "AArch64 glibc capability client build failed" }

Write-Host "glibc Android capability clients built for x86_64 and AArch64."
