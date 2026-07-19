param(
    [switch]$SkipInstall,
    [string]$DescriptorPath,
    [ValidateSet("x86_64", "arm64-v8a")]
    [string]$AndroidAbi = "",
    [string]$Serial = ""
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
$App = Join-Path $Root "prototypes/kcalc-android-app"
$Out = Join-Path $App "out"
if (-not $DescriptorPath) {
    $DescriptorPath = Join-Path $App "archphene-app.json"
}
$Descriptor = Get-Content -LiteralPath $DescriptorPath -Raw | ConvertFrom-Json
if (-not $AndroidAbi) {
    $AndroidAbi = if ($Descriptor.source.architecture -eq "aarch64") { "arm64-v8a" } else { "x86_64" }
}
$LinuxArch = if ($AndroidAbi -eq "arm64-v8a") { "aarch64" } else { "x86_64" }
if ($Descriptor.source.architecture -ne $LinuxArch) {
    throw "Descriptor architecture $($Descriptor.source.architecture) does not match Android ABI $AndroidAbi"
}
if ($Descriptor.schema -ne "org.archphene.app.v1") {
    throw "Unsupported Archphene app descriptor schema: $($Descriptor.schema)"
}
if ($Descriptor.android.package -ne "org.archphene.linux.kcalc") {
    throw "This KCalc wrapper template requires package org.archphene.linux.kcalc"
}
$ManifestText = Get-Content -LiteralPath (Join-Path $App "AndroidManifest.xml") -Raw
$ManifestText = [regex]::Replace($ManifestText,
        '(android:name="org\.archphene\.source\.version" android:value=")[^"]+',
        "`${1}$($Descriptor.android.versionName)")
$ManifestText = [regex]::Replace($ManifestText,
        '(android:name="org\.archphene\.source\.update_url" android:value=")[^"]+',
        "`${1}$($Descriptor.source.metadataUrl)")
$ManifestText = [regex]::Replace($ManifestText,
        '(android:name="org\.archphene\.runtime\.abi" android:value=")[^"]+',
        "`${1}$($Descriptor.runtime.linuxAbi)")
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
$BuildManifest = Join-Path $Out "AndroidManifest.xml"
[IO.File]::WriteAllText($BuildManifest, $ManifestText, [Text.UTF8Encoding]::new($false))

$NativeLibDir = Join-Path $App "lib/$AndroidAbi"
New-Item -ItemType Directory -Force -Path $NativeLibDir | Out-Null
$NativeArchitecture = if ($AndroidAbi -eq "arm64-v8a") { "aarch64" } else { "x86_64" }
& (Join-Path $PSScriptRoot "build-native-compositor-podman.ps1") `
    -Architecture $NativeArchitecture -Release
if ($LASTEXITCODE -ne 0) { throw "Shared native compositor build failed" }
$NativeTarget = if ($AndroidAbi -eq "arm64-v8a") {
    "aarch64-linux-android"
} else {
    "x86_64-linux-android"
}
Copy-Item -LiteralPath (Join-Path $Root `
        "native/archphene-compositor/target/$NativeTarget/release/libarchphene_compositor.so") `
    -Destination (Join-Path $NativeLibDir "libarchphene_compositor.so") -Force
$NdkBin = Join-Path $Sdk "ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin"
$ClangPrefix = if ($AndroidAbi -eq "arm64-v8a") { "aarch64" } else { "x86_64" }
$Clang = Join-Path $NdkBin "$ClangPrefix-linux-android35-clang.cmd"
$ArchRuntimeRoot = Join-Path $Root "tooling/downloads/arch-curated-kcalc-$LinuxArch/runtime-root"
$ArchInclude = Join-Path $ArchRuntimeRoot "usr/include"
$WaylandInclude = Join-Path $App "wayland-include"
Run-Native { & $Clang -fPIE -pie -O2 -Wall -Wextra -o (Join-Path $NativeLibDir "libarchphene_wayland_socket_probe.so") (Join-Path $App "wayland_socket_probe.c") } "clang build Wayland socket probe"
Run-Native { & $Clang -DARCHPHENE_CAPABILITY_PROBE_MAIN -fPIE -pie -O2 -Wall -Wextra -Werror -o (Join-Path $NativeLibDir "libarchphene_capability_probe.so") (Join-Path $Root "native/archphene-android-capability/archphene_android.c") } "clang build Android capability probe"
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
$OldGoTelemetry = $env:GOTELEMETRY
try {
    if ($AndroidAbi -eq "x86_64") {
        $env:GOOS = "linux"
        $env:GOARCH = "amd64"
        $env:CGO_ENABLED = "0"
        $env:GO111MODULE = "off"
        $env:GOTELEMETRY = "off"
        Run-Native { & go build -trimpath -ldflags "-s -w" -o (Join-Path $NativeLibDir "libarchphene_syscall_probe.so") (Join-Path $Root "prototypes/linux-payloads/syscall-probe/main.go") } "go build syscall probe"
    }
} finally {
    $env:GOOS = $OldGoos
    $env:GOARCH = $OldGoarch
    $env:CGO_ENABLED = $OldCgo
    $env:GO111MODULE = $OldGo111Module
    $env:GOTELEMETRY = $OldGoTelemetry
}

Run-Native { & (Join-Path $BuildTools "aapt2.exe") compile --dir (Join-Path $App "res") -o (Join-Path $Out "compiled/res.zip") } "aapt2 compile"
Run-Native { & (Join-Path $BuildTools "aapt2.exe") link -o (Join-Path $Out "unsigned.apk") -I (Join-Path $Sdk "platforms/android-36/android.jar") --version-code ([int]$Descriptor.android.versionCode) --version-name ([string]$Descriptor.android.versionName) --manifest $BuildManifest --java (Join-Path $Out "gen") (Join-Path $Out "compiled/res.zip") } "aapt2 link"

$JavaFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $App "src") -Recurse -Filter *.java
    Get-ChildItem -LiteralPath (Join-Path $Root "prototypes/shared-android-bridge/src") -Recurse -Filter *.java
) | ForEach-Object { $_.FullName }
Run-Native { & javac --release 17 -classpath (Join-Path $Sdk "platforms/android-36/android.jar") -d (Join-Path $Out "classes") $JavaFiles } "javac"

$ClassFiles = Get-ChildItem -LiteralPath (Join-Path $Out "classes") -Recurse -Filter *.class | ForEach-Object { $_.FullName }
$D8ArgFile = Join-Path $Out "d8-inputs.txt"
[IO.File]::WriteAllLines($D8ArgFile, $ClassFiles, [Text.UTF8Encoding]::new($false))
Run-Native { & (Join-Path $BuildTools "d8.bat") --lib (Join-Path $Sdk "platforms/android-36/android.jar") --min-api 23 --output (Join-Path $Out "dex") "@$D8ArgFile" } "d8"

Push-Location (Join-Path $Out "dex")
Run-Native { & jar uf "..\unsigned.apk" classes.dex } "jar add classes"
Pop-Location

Push-Location $App
$NativeFiles = Get-ChildItem -LiteralPath (Join-Path $App "lib/$AndroidAbi") -File | ForEach-Object { "lib\$AndroidAbi\$($_.Name)" }
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
$Key = Join-Path $SigningDir "archpheneos-kcalc-debug.keystore"
if (-not (Test-Path -LiteralPath $Key)) {
    Run-Native { & keytool -genkeypair -keystore $Key -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=KCalc,O=ArchpheneOS,C=US" } "keytool"
}

Run-Native { & (Join-Path $BuildTools "zipalign.exe") -P 16 -f 4 (Join-Path $Out "unsigned.apk") (Join-Path $Out "aligned.apk") } "zipalign"
$ApkName = if ($AndroidAbi -eq "arm64-v8a") { "archpheneos-kcalc-arm64.apk" } else { "archpheneos-kcalc.apk" }
$Apk = Join-Path $Out $ApkName
Run-Native { & (Join-Path $BuildTools "apksigner.bat") sign --ks $Key --ks-pass pass:android --key-pass pass:android --out $Apk (Join-Path $Out "aligned.apk") } "apksigner sign"
Run-Native { & (Join-Path $BuildTools "apksigner.bat") verify --verbose $Apk } "apksigner verify"

$Adb = Join-Path $Sdk "platform-tools/adb.exe"
if (-not $SkipInstall) {
    if (-not $Serial) { throw "-Serial is required when installing with multiple ADB devices" }
    Run-Native { & $Adb -s $Serial install -r $Apk } "adb install"
    Run-Native { & $Adb -s $Serial shell am start -n org.archphene.linux.kcalc/org.archphene.linux.kcalc.MainActivity } "adb launch"
}











