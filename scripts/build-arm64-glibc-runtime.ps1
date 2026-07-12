param(
    [string]$Container = "archphene-glibc-incremental",
    [string]$SourceRef = "8362e8ce10b24068bacc19552c128dd10e082fd9",
    [string]$RepositoryDir = "/build/archphene-glibc-src",
    [string]$WorkRoot = "/build/archphene-glibc-builds",
    [int]$Jobs = 8
)

$ErrorActionPreference = "Stop"

function Run-Native([scriptblock]$Command, [string]$Step) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit code $LASTEXITCODE" }
}

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Patch = Join-Path $Root "patches/glibc/0001-android-app-seccomp-compat.patch"
$Out = Join-Path $Root "tooling/build/glibc-archphene-runtime-aarch64"
$Token = [guid]::NewGuid().ToString("N")
$RemotePatch = "/tmp/archphene-glibc-$Token.patch"
$RemoteSource = "$WorkRoot/source-$Token"
$RemoteBuild = "$WorkRoot/obj-$Token"

$state = & podman inspect $Container --format '{{.State.Status}}' 2>$null
if ($LASTEXITCODE -ne 0 -or $state.Trim() -ne "running") {
    throw "Build container $Container is not running"
}

Run-Native { & podman exec $Container bash -lc "command -v git && command -v aarch64-linux-gnu-gcc && command -v make && command -v bison && command -v python3" } `
        "verify glibc build dependencies"
Run-Native { & podman cp $Patch "${Container}:$RemotePatch" } "stage glibc compatibility patch"

$build = @"
set -euo pipefail
mkdir -p '$WorkRoot'
if [ ! -d '$RepositoryDir/.git' ]; then
  git clone --filter=blob:none https://sourceware.org/git/glibc.git '$RepositoryDir'
fi
git -C '$RepositoryDir' cat-file -e '$SourceRef^{commit}'
git -C '$RepositoryDir' worktree add --detach '$RemoteSource' '$SourceRef'
git -C '$RemoteSource' apply '$RemotePatch'
mkdir '$RemoteBuild'
cd '$RemoteBuild'
CPPFLAGS='-DARCHPHENE_ANDROID_APP_COMPAT=1' '$RemoteSource/configure' \
  --prefix=/usr \
  --build=x86_64-pc-linux-gnu \
  --host=aarch64-linux-gnu \
  --disable-werror \
  --enable-kernel=5.10
if ! make -j$Jobs > archphene-build.log 2>&1; then
  tail -200 archphene-build.log
  exit 1
fi
mkdir archphene-export
cp elf/ld.so archphene-export/ld-linux-aarch64.so.1
cp libc.so archphene-export/libc.so.6
aarch64-linux-gnu-strip --strip-unneeded archphene-export/ld-linux-aarch64.so.1 archphene-export/libc.so.6
git -C '$RemoteSource' rev-parse HEAD > archphene-source-commit.txt
"@
Run-Native { & podman exec $Container bash -lc $build } "build patched AArch64 glibc"

New-Item -ItemType Directory -Force -Path $Out | Out-Null
Run-Native { & podman cp "${Container}:$RemoteBuild/archphene-export/ld-linux-aarch64.so.1" (Join-Path $Out "ld-linux-aarch64.so.1") } "export AArch64 loader"
Run-Native { & podman cp "${Container}:$RemoteBuild/archphene-export/libc.so.6" (Join-Path $Out "libc.so.6") } "export AArch64 libc"
Run-Native { & podman cp "${Container}:$RemoteBuild/archphene-source-commit.txt" (Join-Path $Out "source-commit.txt") } "export source revision"

$ReadElf = Join-Path $Root "tooling/android-sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
$manifest = foreach ($file in @("ld-linux-aarch64.so.1", "libc.so.6")) {
    $path = Join-Path $Out $file
    $header = (& $ReadElf -h $path) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $header -notmatch 'Machine:\s+AArch64') {
        throw "Exported runtime is not AArch64: $path"
    }
    [ordered]@{
        file = $file
        bytes = (Get-Item $path).Length
        sha256 = (Get-FileHash $path -Algorithm SHA256).Hash
    }
}
[ordered]@{
    source = "https://sourceware.org/git/glibc.git"
    commit = (Get-Content (Join-Path $Out "source-commit.txt") -Raw).Trim()
    cppflags = "-DARCHPHENE_ANDROID_APP_COMPAT=1"
    files = $manifest
} | ConvertTo-Json -Depth 4 | Set-Content (Join-Path $Out "build-manifest.json") -Encoding ascii

Write-Host "Patched AArch64 glibc runtime built and verified: $Out"
