param(
    [string]$Container = "archphene-glibc-incremental",
    [string]$SourceRef = "fdf10644d6ee345c7b5277c3fa009c1bedb92d60",
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
$Out = Join-Path $Root "tooling/build/glibc-archphene-runtime-x86_64"
$Token = [guid]::NewGuid().ToString("N")
$RemotePatch = "/tmp/archphene-glibc-$Token.patch"
$RemoteSource = "$WorkRoot/source-x86-$Token"
$RemoteBuild = "$WorkRoot/obj-x86-$Token"

Run-Native { & podman cp $Patch "${Container}:$RemotePatch" } "stage glibc compatibility patch"
$build = @"
set -euo pipefail
git -C '$RepositoryDir' cat-file -e '$SourceRef^{commit}'
git -C '$RepositoryDir' worktree add --detach '$RemoteSource' '$SourceRef'
git -C '$RemoteSource' apply '$RemotePatch'
mkdir '$RemoteBuild'
cd '$RemoteBuild'
CPPFLAGS='-DARCHPHENE_ANDROID_APP_COMPAT=1' '$RemoteSource/configure' \
  --prefix=/usr --disable-werror --enable-kernel=5.10
make -j$Jobs > archphene-build.log 2>&1 || { tail -200 archphene-build.log; exit 1; }
make install DESTDIR='$RemoteBuild/install' > archphene-install.log 2>&1 || { tail -200 archphene-install.log; exit 1; }
git -C '$RemoteSource' rev-parse HEAD > archphene-source-commit.txt
"@
Run-Native { & podman exec $Container bash -lc $build } "build patched x86_64 glibc"
New-Item -ItemType Directory -Force -Path $Out | Out-Null
$files = @("ld-linux-x86-64.so.2", "libc.so.6", "libm.so.6", "libdl.so.2",
    "libpthread.so.0", "librt.so.1", "libresolv.so.2", "libutil.so.1",
    "libanl.so.1", "libnss_dns.so.2", "libnss_files.so.2")
foreach ($file in $files) {
    Run-Native { & podman cp "${Container}:$RemoteBuild/install/lib64/$file" (Join-Path $Out $file) } "export $file"
}
Run-Native { & podman cp "${Container}:$RemoteBuild/archphene-source-commit.txt" (Join-Path $Out "source-commit.txt") } "export source revision"
$manifest = foreach ($file in $files) {
    $path = Join-Path $Out $file
    [ordered]@{ file=$file; bytes=(Get-Item $path).Length; sha256=(Get-FileHash $path -Algorithm SHA256).Hash }
}
[ordered]@{ source="https://sourceware.org/git/glibc.git"; commit=$SourceRef; cppflags="-DARCHPHENE_ANDROID_APP_COMPAT=1"; files=$manifest } |
    ConvertTo-Json -Depth 4 | Set-Content (Join-Path $Out "build-manifest.json") -Encoding ascii
Write-Host "Patched x86_64 glibc runtime built: $Out"