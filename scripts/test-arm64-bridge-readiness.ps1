$ErrorActionPreference = "Stop"

function Run-Native([scriptblock]$Command, [string]$Step) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit code $LASTEXITCODE" }
}

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$App = Join-Path $Root "prototypes/mousepad-android-app"
$Sdk = Join-Path $Root "tooling/android-sdk"
$NdkBin = Join-Path $Sdk "ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin"
$Clang = Join-Path $NdkBin "aarch64-linux-android35-clang.cmd"
$ReadElf = Join-Path $NdkBin "llvm-readelf.exe"
$Out = Join-Path $Root "tooling/build/arm64-bridge-readiness"
$Include = Join-Path $App "wayland-include"
New-Item -ItemType Directory -Force -Path $Out | Out-Null

$Jni = Join-Path $Out "libarchphene_wayland_jni.so"
$Client = Join-Path $Out "libarchphene_wayland_client_android.so"
$Frame = Join-Path $Out "archphene_frame_client"

Run-Native { & $Clang -shared -fPIC -O2 -Wall -Wextra -o $Jni (Join-Path $App "wayland_socket_jni.c") } `
        "compile ARM64 Wayland JNI socket bridge"
Run-Native { & $Clang -shared -fPIC -O2 -Wall -Wextra -I $Include `
        "-Wl,-soname,libarchphene_wayland_client_android.so" -o $Client `
        (Join-Path $App "archphene_wayland_client_android.c") } `
        "compile ARM64 Wayland client shim"
Run-Native { & $Clang -fPIE -pie -O2 -Wall -Wextra -o $Frame `
        (Join-Path $App "archphene_frame_client.c") } `
        "compile ARM64 frame client"

$results = foreach ($file in @($Jni, $Client, $Frame)) {
    $header = (& $ReadElf -h $file) -join "`n"
    if ($LASTEXITCODE -ne 0 -or $header -notmatch 'Machine:\s+AArch64') {
        throw "ARM64 ELF verification failed for $file"
    }
    [pscustomobject]@{
        File = Split-Path $file -Leaf
        Bytes = (Get-Item $file).Length
        SHA256 = (Get-FileHash $file -Algorithm SHA256).Hash
    }
}

Write-Host "ARM64 bridge readiness passed for Android-owned native components."
Write-Host "Run test-arm64-physical-regression.ps1 for the complete AArch64 application/device gate."
$results | Format-Table File,Bytes,SHA256 -AutoSize