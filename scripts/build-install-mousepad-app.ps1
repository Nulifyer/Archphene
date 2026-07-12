param(
    [switch]$SkipInstall,
    [string]$DescriptorPath
)

$ErrorActionPreference = "Stop"

function Run-Native([scriptblock]$Command, [string]$Step) {
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE"
    }
}

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Sdk = Join-Path $Root "tooling/android-sdk"
$BuildTools = Join-Path $Sdk "build-tools/36.0.0"
$App = Join-Path $Root "prototypes/mousepad-android-app"
$Out = Join-Path $App "out"
if (-not $DescriptorPath) {
    $DescriptorPath = Join-Path $App "archphene-app.json"
}
$Descriptor = Get-Content -LiteralPath $DescriptorPath -Raw | ConvertFrom-Json
if ($Descriptor.schema -ne "org.archphene.app.v1") {
    throw "Unsupported Archphene app descriptor schema: $($Descriptor.schema)"
}
if ($Descriptor.android.package -ne "org.archphene.linux.mousepad") {
    throw "This Mousepad wrapper template requires package org.archphene.linux.mousepad"
}
$ManifestText = Get-Content -LiteralPath (Join-Path $App "AndroidManifest.xml") -Raw
$ProviderCount = ([regex]::Matches($ManifestText, '<provider\s')).Count
if ($ProviderCount -ne 1) {
    throw "Mousepad manifest must declare exactly one DocumentsProvider; found $ProviderCount"
}
foreach ($Expected in @($Descriptor.android.package, $Descriptor.android.label,
        $Descriptor.android.versionName, $Descriptor.source.metadataUrl, $Descriptor.runtime.linuxAbi)) {
    if (-not $ManifestText.Contains([string]$Expected)) {
        throw "Android manifest does not match descriptor value: $Expected"
    }
}
$PayloadPath = Join-Path $App $Descriptor.payload.apkLibrary
if ((Get-FileHash -LiteralPath $PayloadPath -Algorithm SHA256).Hash -ne $Descriptor.payload.sha256) {
    throw "Packaged Linux entrypoint does not match descriptor SHA-256"
}

if (Test-Path -LiteralPath $Out) {
    Remove-Item -LiteralPath $Out -Recurse -Force
}

New-Item -ItemType Directory -Force -Path `
    $Out, `
    (Join-Path $Out "compiled"), `
    (Join-Path $Out "gen"), `
    (Join-Path $Out "classes"), `
    (Join-Path $Out "dex") | Out-Null

$NativeLibDir = Join-Path $App "lib/x86_64"
New-Item -ItemType Directory -Force -Path $NativeLibDir | Out-Null
$NdkBin = Join-Path $Sdk "ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin"
$Clang = Join-Path $NdkBin "x86_64-linux-android35-clang.cmd"
$ArchRuntimeRoot = Join-Path $Root "tooling/downloads/arch-curated-mousepad-x86_64/runtime-root"
$ArchInclude = Join-Path $ArchRuntimeRoot "usr/include"
$GconvRuntimeRoot = Join-Path $Root "tooling/downloads/arch-curated-kcalc-x86_64/runtime-root"
$GconvSource = Join-Path $GconvRuntimeRoot "usr/lib/gconv"
$GconvAsset = Join-Path $App "assets/glibc-gconv.zip"
if (-not (Test-Path -LiteralPath $GconvSource)) {
    throw "Arch glibc gconv runtime is missing: $GconvSource"
}
Push-Location $GconvRuntimeRoot
try {
    Run-Native { & jar cf $GconvAsset "usr/lib/gconv" } "package glibc gconv runtime"
}
finally {
    Pop-Location
}
$WaylandInclude = Join-Path $App "wayland-include"
Run-Native { & $Clang -shared -fPIC -O2 -Wall -Wextra -o (Join-Path $NativeLibDir "libarchphene_wayland_jni.so") (Join-Path $App "wayland_socket_jni.c") } "clang build package-specific Wayland JNI"
Run-Native { & $Clang -fPIE -pie -O2 -Wall -Wextra -o (Join-Path $NativeLibDir "libarchphene_frame_client.so") (Join-Path $App "archphene_frame_client.c") } "clang build Linux frame client"
Run-Native { & $Clang -fPIE -pie -O2 -Wall -Wextra -o (Join-Path $NativeLibDir "libarchphene_shm_frame_client.so") (Join-Path $App "archphene_shm_frame_client.c") } "clang build Linux shm frame client"
Run-Native { & $Clang -fPIE -pie -O2 -Wall -Wextra -o (Join-Path $NativeLibDir "libarchphene_wayland_shm_client.so") (Join-Path $App "archphene_wayland_shm_client.c") } "clang build raw Wayland shm client"
Run-Native { & $Clang -fPIE -pie -O2 -Wall -Wextra -o (Join-Path $NativeLibDir "libarchphene_wayland_evented_client.so") (Join-Path $App "archphene_wayland_evented_client.c") } "clang build evented Wayland client"
Run-Native { & $Clang -fPIE -pie -O2 -Wall -Wextra -o (Join-Path $NativeLibDir "libarchphene_wayland_xdg_client.so") (Join-Path $App "archphene_wayland_xdg_client.c") } "clang build xdg Wayland client"
Run-Native { & $Clang -fPIE -pie -O2 -Wall -Wextra -I $WaylandInclude -L $NativeLibDir "-Wl,--allow-shlib-undefined" -o (Join-Path $NativeLibDir "libarchphene_wayland_api_client.so") (Join-Path $App "archphene_wayland_api_client.c") "-l:libwayland-client.so.0" } "clang build libwayland-client API probe"
Run-Native { & $Clang -shared -fPIC -O2 -Wall -Wextra -I $WaylandInclude "-Wl,-soname,libarchphene_wayland_client_android.so" -o (Join-Path $NativeLibDir "libarchphene_wayland_client_android.so") (Join-Path $App "archphene_wayland_client_android.c") } "clang build Android Wayland client shim"
Run-Native { & $Clang -fPIE -pie -O2 -Wall -Wextra -I $WaylandInclude -L $NativeLibDir "-Wl,--allow-shlib-undefined" -o (Join-Path $NativeLibDir "libarchphene_wayland_android_api_client.so") (Join-Path $App "archphene_wayland_api_client.c") "-l:libarchphene_wayland_client_android.so" } "clang build Android Wayland API probe"
Run-Native { & $Clang -fPIE -pie -O2 -Wall -Wextra -I $WaylandInclude -L $NativeLibDir "-Wl,--allow-shlib-undefined" -o (Join-Path $NativeLibDir "libarchphene_wayland_android_api_render_client.so") (Join-Path $App "archphene_wayland_api_render_client.c") "-l:libarchphene_wayland_client_android.so" } "clang build Android Wayland API render probe"
Run-Native { & $Clang -fPIE -pie -O2 -Wall -Wextra -I $WaylandInclude -L $NativeLibDir "-Wl,--allow-shlib-undefined" -o (Join-Path $NativeLibDir "libarchphene_wayland_android_api_xdg_client.so") (Join-Path $App "archphene_wayland_api_xdg_client.c") "-l:libarchphene_wayland_client_android.so" } "clang build Android Wayland API xdg probe"
$OldGoos = $env:GOOS
$OldGoarch = $env:GOARCH
$OldCgo = $env:CGO_ENABLED
$OldGo111Module = $env:GO111MODULE
try {
    $env:GOOS = "linux"
    $env:GOARCH = "amd64"
    $env:CGO_ENABLED = "0"
    $env:GO111MODULE = "off"
    Run-Native { & go build -trimpath -ldflags "-s -w" -o (Join-Path $NativeLibDir "libarchphene_syscall_probe.so") (Join-Path $Root "prototypes/linux-payloads/syscall-probe/main.go") } "go build syscall probe"
} finally {
    $env:GOOS = $OldGoos
    $env:GOARCH = $OldGoarch
    $env:CGO_ENABLED = $OldCgo
    $env:GO111MODULE = $OldGo111Module
}

Run-Native { & (Join-Path $BuildTools "aapt2.exe") compile --dir (Join-Path $App "res") -o (Join-Path $Out "compiled/res.zip") } "aapt2 compile"
Run-Native { & (Join-Path $BuildTools "aapt2.exe") link -o (Join-Path $Out "unsigned.apk") -I (Join-Path $Sdk "platforms/android-36/android.jar") --version-code ([int]$Descriptor.android.versionCode) --version-name ([string]$Descriptor.android.versionName) --manifest (Join-Path $App "AndroidManifest.xml") --java (Join-Path $Out "gen") (Join-Path $Out "compiled/res.zip") } "aapt2 link"

$JavaFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $App "src") -Recurse -Filter *.java
    Get-ChildItem -LiteralPath (Join-Path $Root "prototypes/shared-android-bridge/src") -Recurse -Filter *.java
) | ForEach-Object { $_.FullName }
Run-Native { & javac --release 17 -classpath (Join-Path $Sdk "platforms/android-36/android.jar") -d (Join-Path $Out "classes") $JavaFiles } "javac"

$ClassFiles = Get-ChildItem -LiteralPath (Join-Path $Out "classes") -Recurse -Filter *.class | ForEach-Object { $_.FullName }
Run-Native { & cmd.exe /d /c call (Join-Path $BuildTools "d8.bat") --lib (Join-Path $Sdk "platforms/android-36/android.jar") --min-api 23 --output (Join-Path $Out "dex") $ClassFiles } "d8"

Push-Location (Join-Path $Out "dex")
Run-Native { & jar uf "..\unsigned.apk" classes.dex } "jar add classes"
Pop-Location

Push-Location $App
$NativeFiles = Get-ChildItem -LiteralPath (Join-Path $App "lib/x86_64") -File | ForEach-Object { "lib\x86_64\$($_.Name)" }
if ($NativeFiles.Count -gt 0) {
    Run-Native { & jar uf "out\unsigned.apk" $NativeFiles } "jar add native payloads"
}
$AssetFiles = Get-ChildItem -LiteralPath (Join-Path $App "assets") -File | ForEach-Object { "assets\$($_.Name)" }
foreach ($AssetFile in $AssetFiles) {
    Run-Native { & jar uf "out\unsigned.apk" $AssetFile } "jar add asset $AssetFile"
}
Pop-Location

$SigningDir = Join-Path $Root "tooling/signing"
New-Item -ItemType Directory -Force -Path $SigningDir | Out-Null
$Key = Join-Path $SigningDir "archpheneos-mousepad-debug.keystore"
if (-not (Test-Path -LiteralPath $Key)) {
    Run-Native { & keytool -genkeypair -keystore $Key -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Mousepad,O=ArchpheneOS,C=US" } "keytool"
}

Run-Native { & (Join-Path $BuildTools "zipalign.exe") -f 4 (Join-Path $Out "unsigned.apk") (Join-Path $Out "aligned.apk") } "zipalign"
Run-Native { & (Join-Path $BuildTools "apksigner.bat") sign --ks $Key --ks-pass pass:android --key-pass pass:android --out (Join-Path $Out "archpheneos-mousepad.apk") (Join-Path $Out "aligned.apk") } "apksigner sign"
Run-Native { & (Join-Path $BuildTools "apksigner.bat") verify --verbose (Join-Path $Out "archpheneos-mousepad.apk") } "apksigner verify"

$Adb = Join-Path $Sdk "platform-tools/adb.exe"
if (-not $SkipInstall) {
    Run-Native { & $Adb install -r (Join-Path $Out "archpheneos-mousepad.apk") } "adb install"
    Run-Native { & $Adb shell am start -n org.archphene.linux.mousepad/org.archphene.linux.mousepad.MainActivity } "adb launch"
}











