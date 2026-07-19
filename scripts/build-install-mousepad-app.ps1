param(
    [switch]$SkipInstall,
    [string]$DescriptorPath,
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
$ManifestXml = [xml]$ManifestText
$AndroidNamespace = "http://schemas.android.com/apk/res/android"
$DocumentProviders = @($ManifestXml.manifest.application.provider | Where-Object {
    $_.GetAttribute("name", $AndroidNamespace) -eq
        "org.archphene.bridge.LinuxHomeBrokerProvider"
})
if ($DocumentProviders.Count -ne 1) {
    throw "Mousepad manifest must declare exactly one LinuxHomeBrokerProvider; found $($DocumentProviders.Count)"
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
$DesktopHelperDir = Join-Path $Root "tooling/build/wrapper-templates/qt/stage/lib/x86_64"
foreach ($DesktopHelper in @(
        "libarchphene_dbus_daemon.so",
        "libarchphene_portal_service.so",
        "libarchphene_xdg_open.so")) {
    $Source = Join-Path $DesktopHelperDir $DesktopHelper
    if (-not (Test-Path -LiteralPath $Source -PathType Leaf)) {
        throw "Shared desktop helper is missing: $Source"
    }
    Copy-Item -LiteralPath $Source -Destination (Join-Path $NativeLibDir $DesktopHelper) -Force
}
& (Join-Path $PSScriptRoot "build-native-compositor-podman.ps1") `
    -Architecture x86_64 -Release
if ($LASTEXITCODE -ne 0) { throw "Shared native compositor build failed" }
Copy-Item -LiteralPath (Join-Path $Root `
        "native/archphene-compositor/target/x86_64-linux-android/release/libarchphene_compositor.so") `
    -Destination (Join-Path $NativeLibDir "libarchphene_compositor.so") -Force
$NdkBin = Join-Path $Sdk "ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin"
$Clang = Join-Path $NdkBin "x86_64-linux-android35-clang.cmd"
$SvgLoaderPackage = Join-Path $Root "tooling/sources/librsvg-noglycin/librsvg-noglycin-2.62.3-1-x86_64.pkg.tar.zst"
$PixbufCompatPackage = Join-Path $Root "tooling/sources/gdk-pixbuf2-noglycin/gdk-pixbuf2-noglycin-2.44.6-2-x86_64.pkg.tar.zst"
foreach ($CompatPackage in @($SvgLoaderPackage, $PixbufCompatPackage)) {
    if (-not (Test-Path -LiteralPath $CompatPackage)) {
        throw "Arch GTK compatibility package is missing: $CompatPackage"
    }
}
$SvgLoaderStage = Join-Path $Root "tooling/build/mousepad-svg-loader"
if (Test-Path -LiteralPath $SvgLoaderStage) {
    Remove-Item -LiteralPath $SvgLoaderStage -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $SvgLoaderStage | Out-Null
Run-Native { & tar -xf $SvgLoaderPackage -C $SvgLoaderStage "usr/lib/gdk-pixbuf-2.0/2.10.0/loaders/libpixbufloader_svg.so" "usr/lib/librsvg-2.so.2.62.3" } "extract librsvg compatibility runtime"
Run-Native { & tar -xf $PixbufCompatPackage -C $SvgLoaderStage "usr/lib/libgdk_pixbuf-2.0.so.0.4400.6" } "extract GdkPixbuf compatibility runtime"
Copy-Item -LiteralPath (Join-Path $SvgLoaderStage "usr/lib/gdk-pixbuf-2.0/2.10.0/loaders/libpixbufloader_svg.so") -Destination (Join-Path $NativeLibDir "libarchphene_pixbufloader_svg.so") -Force
Get-ChildItem -LiteralPath $NativeLibDir -Filter "libgdk_pixbuf-2.0.so*" | Remove-Item -Force
Get-ChildItem -LiteralPath $NativeLibDir -Filter "libglycin*" | Remove-Item -Force
$PixbufRuntime = Join-Path $SvgLoaderStage "usr/lib/libgdk_pixbuf-2.0.so.0.4400.6"
foreach ($Name in @("libgdk_pixbuf-2.0.so", "libgdk_pixbuf-2.0.so.0", "libgdk_pixbuf-2.0.so.0.4400.6")) {
    Copy-Item -LiteralPath $PixbufRuntime -Destination (Join-Path $NativeLibDir $Name) -Force
}
$RsvgRuntime = Join-Path $SvgLoaderStage "usr/lib/librsvg-2.so.2.62.3"
foreach ($Name in @("librsvg-2.so", "librsvg-2.so.2", "librsvg-2.so.2.62.3")) {
    Copy-Item -LiteralPath $RsvgRuntime -Destination (Join-Path $NativeLibDir $Name) -Force
}
Remove-Item -LiteralPath (Join-Path $NativeLibDir "libarchphene_glycin_svg.so") -Force -ErrorAction SilentlyContinue
$ArchRuntimeRoot = Join-Path $Root "tooling/downloads/arch-curated-mousepad-x86_64/runtime-root"
$ArchInclude = Join-Path $ArchRuntimeRoot "usr/include"
$MousepadDataSource = Join-Path $ArchRuntimeRoot "usr/share"
$GtkPackage = Get-ChildItem -LiteralPath (Join-Path $Root "tooling/downloads/arch-curated-mousepad-x86_64/packages") `
    -File -Filter "gtk3-*.pkg.tar.zst" | Select-Object -First 1
if (-not $GtkPackage) {
    throw "Resolved Arch gtk3 package is missing"
}
$GtkModuleStage = Join-Path $Root "tooling/build/mousepad-gtk-module"
if (Test-Path -LiteralPath $GtkModuleStage) {
    Remove-Item -LiteralPath $GtkModuleStage -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $GtkModuleStage | Out-Null
$PortableGtkPackage = Join-Path $GtkModuleStage "gtk3.pkg.tar.zst"
Copy-Item -LiteralPath $GtkPackage.FullName -Destination $PortableGtkPackage
Run-Native { & tar -xf $PortableGtkPackage -C $GtkModuleStage `
        "usr/lib/gtk-3.0/3.0.0/immodules/im-wayland.so" } "extract GTK Wayland input module"
$GtkWaylandModule = Join-Path $GtkModuleStage "usr/lib/gtk-3.0/3.0.0/immodules/im-wayland.so"
Copy-Item -LiteralPath $GtkWaylandModule `
    -Destination (Join-Path $NativeLibDir "libarchphene_im_wayland.so") -Force
$MousepadDataAsset = Join-Path $App "assets/mousepad-data.zip"
if (-not (Test-Path -LiteralPath $MousepadDataSource)) {
    throw "Arch Mousepad data runtime is missing: $MousepadDataSource"
}
$PortableXkb = Join-Path $MousepadDataSource "xkeyboard-config-2"
$PreservedXkb = Join-Path $Root "tooling/downloads/arch-curated-mousepad-x86_64/runtime-root-ntfs-symlinks/usr/share/xkeyboard-config-2"
if (-not (Test-Path -LiteralPath $PortableXkb)) {
    if (-not (Test-Path -LiteralPath $PreservedXkb)) {
        throw "Arch xkeyboard-config data is missing: $PreservedXkb"
    }
    Copy-Item -LiteralPath $PreservedXkb -Destination $PortableXkb -Recurse
}
$XkbRules = Join-Path $PortableXkb "rules"
foreach ($Alias in @(
        @("xorg", "evdev"),
        @("xorg.lst", "evdev.lst"),
        @("xorg.xml", "evdev.xml"))) {
    $AliasPath = Join-Path $XkbRules $Alias[0]
    $TargetPath = Join-Path $XkbRules $Alias[1]
    $AliasItem = Get-Item -LiteralPath $AliasPath -Force -ErrorAction SilentlyContinue
    if ($AliasItem -and -not ($AliasItem.Attributes -band [IO.FileAttributes]::ReparsePoint) -and $AliasItem.Length -gt 0) {
        continue
    }
    Remove-Item -LiteralPath $AliasPath -Force -ErrorAction SilentlyContinue
    Copy-Item -LiteralPath $TargetPath -Destination $AliasPath
}
$SchemaDirectory = Join-Path $MousepadDataSource "glib-2.0/schemas"
$SchemaCache = Join-Path $SchemaDirectory "gschemas.compiled"
$NewestSchema = Get-ChildItem -LiteralPath $SchemaDirectory -Filter *.xml -File |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if (-not (Test-Path -LiteralPath $SchemaCache) -or
        (Get-Item -LiteralPath $SchemaCache).LastWriteTimeUtc -lt $NewestSchema.LastWriteTimeUtc) {
    podman run --rm -v "${Root}:/workspace" -w /workspace `
        localhost/archphene-android-native:ndk29-rust1.88 `
        glib-compile-schemas /workspace/tooling/downloads/arch-curated-mousepad-x86_64/runtime-root/usr/share/glib-2.0/schemas
    if ($LASTEXITCODE -ne 0) { throw "GSettings schema compilation failed" }
}
if (-not (Test-Path -LiteralPath $SchemaCache)) {
    throw "Compiled GSettings schema is missing"
}
$MimeDirectory = Join-Path $MousepadDataSource "mime"
$MimeSource = Join-Path $MimeDirectory "packages/freedesktop.org.xml"
$MimeCache = Join-Path $MimeDirectory "mime.cache"
if (-not (Test-Path -LiteralPath $MimeCache) -or
        (Get-Item -LiteralPath $MimeCache).LastWriteTimeUtc -lt (Get-Item -LiteralPath $MimeSource).LastWriteTimeUtc) {
    podman run --rm -v "${Root}:/workspace" -w /workspace `
        localhost/archphene-android-native:ndk29-rust1.88 `
        update-mime-database /workspace/tooling/downloads/arch-curated-mousepad-x86_64/runtime-root/usr/share/mime
    if ($LASTEXITCODE -ne 0) { throw "shared MIME database generation failed" }
}
if (-not (Test-Path -LiteralPath $MimeCache)) {
    throw "Shared MIME database cache is missing"
}
$DataAssetStamp = @((Get-Item -LiteralPath $SchemaCache), (Get-Item -LiteralPath $MimeCache)) |
    Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if (-not (Test-Path -LiteralPath $MousepadDataAsset) -or
        (Get-Item -LiteralPath $MousepadDataAsset).LastWriteTimeUtc -lt $DataAssetStamp.LastWriteTimeUtc) {
    Push-Location $ArchRuntimeRoot
    try {
        Run-Native { & jar cf $MousepadDataAsset "usr/share" } "package Mousepad data runtime"
    }
    finally {
        Pop-Location
    }
}
$GconvRuntimeRoot = Join-Path $Root "tooling/downloads/arch-curated-kcalc-x86_64/runtime-root"
$GconvSource = Join-Path $GconvRuntimeRoot "usr/lib/gconv"
$GconvAsset = Join-Path $App "assets/glibc-gconv.zip"
if (-not (Test-Path -LiteralPath $GconvSource)) {
    throw "Arch glibc gconv runtime is missing: $GconvSource"
}
if (-not (Test-Path -LiteralPath $GconvAsset)) {
    Push-Location $GconvRuntimeRoot
    try {
        Run-Native { & jar cf $GconvAsset "usr/lib/gconv" } "package glibc gconv runtime"
    }
    finally {
        Pop-Location
    }
}
$WaylandInclude = Join-Path $App "wayland-include"
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
podman run --rm -v "${Root}:/workspace" -w /workspace `
    -e GOOS=linux -e GOARCH=amd64 -e CGO_ENABLED=0 -e GO111MODULE=off `
    docker.io/library/golang:1.24 `
    go build -trimpath -ldflags "-s -w" `
        -o /workspace/prototypes/mousepad-android-app/lib/x86_64/libarchphene_syscall_probe.so `
        /workspace/prototypes/linux-payloads/syscall-probe/main.go
if ($LASTEXITCODE -ne 0) { throw "container Go syscall probe build failed" }

Run-Native { & (Join-Path $BuildTools "aapt2.exe") compile --dir (Join-Path $App "res") -o (Join-Path $Out "compiled/res.zip") } "aapt2 compile"
Run-Native { & (Join-Path $BuildTools "aapt2.exe") link -o (Join-Path $Out "unsigned.apk") -I (Join-Path $Sdk "platforms/android-36/android.jar") --version-code ([int]$Descriptor.android.versionCode) --version-name ([string]$Descriptor.android.versionName) --manifest (Join-Path $App "AndroidManifest.xml") --java (Join-Path $Out "gen") (Join-Path $Out "compiled/res.zip") } "aapt2 link"

$JavaFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $App "src") -Recurse -Filter *.java
    Get-ChildItem -LiteralPath (Join-Path $Root "prototypes/shared-android-bridge/src") -Recurse -Filter *.java
) | ForEach-Object { $_.FullName }
Run-Native { & javac --release 17 -classpath (Join-Path $Sdk "platforms/android-36/android.jar") -d (Join-Path $Out "classes") $JavaFiles } "javac"

$ClassFiles = Get-ChildItem -LiteralPath (Join-Path $Out "classes") -Recurse -Filter *.class | ForEach-Object { $_.FullName }
$D8ArgFile = Join-Path $Out "d8-inputs.txt"
[IO.File]::WriteAllLines($D8ArgFile, $ClassFiles, [Text.UTF8Encoding]::new($false))
Run-Native { & cmd.exe /d /c call (Join-Path $BuildTools "d8.bat") --lib (Join-Path $Sdk "platforms/android-36/android.jar") --min-api 23 --output (Join-Path $Out "dex") "@$D8ArgFile" } "d8"

Push-Location (Join-Path $Out "dex")
Run-Native { & jar uf "..\unsigned.apk" classes.dex } "jar add classes"
Pop-Location

Push-Location $App
$NativeFiles = Get-ChildItem -LiteralPath (Join-Path $App "lib/x86_64") -File | ForEach-Object { "lib\x86_64\$($_.Name)" }
if ($NativeFiles.Count -gt 0) {
    Run-Native { & jar uf "out\unsigned.apk" $NativeFiles } "jar add native payloads"
}
$AssetFiles = Get-ChildItem -LiteralPath (Join-Path $App "assets") -File | ForEach-Object { "assets\$($_.Name)" }
if ($AssetFiles.Count -gt 0) {
    Run-Native { & jar uf "out\unsigned.apk" $AssetFiles } "jar add assets"
}
Pop-Location

$SigningDir = Join-Path $Root "tooling/signing"
New-Item -ItemType Directory -Force -Path $SigningDir | Out-Null
$Key = Join-Path $SigningDir "archpheneos-mousepad-debug.keystore"
if (-not (Test-Path -LiteralPath $Key)) {
    Run-Native { & keytool -genkeypair -keystore $Key -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Mousepad,O=ArchpheneOS,C=US" } "keytool"
}

Run-Native { & (Join-Path $BuildTools "zipalign.exe") -P 16 -f 4 (Join-Path $Out "unsigned.apk") (Join-Path $Out "aligned.apk") } "zipalign"
Run-Native { & (Join-Path $BuildTools "apksigner.bat") sign --ks $Key --ks-pass pass:android --key-pass pass:android --out (Join-Path $Out "archpheneos-mousepad.apk") (Join-Path $Out "aligned.apk") } "apksigner sign"
Run-Native { & (Join-Path $BuildTools "apksigner.bat") verify --verbose (Join-Path $Out "archpheneos-mousepad.apk") } "apksigner verify"

$Adb = Join-Path $Sdk "platform-tools/adb.exe"
if (-not $SkipInstall) {
    if (-not $Serial) { throw "-Serial is required when installing with multiple ADB devices" }
    Run-Native { & $Adb -s $Serial install --no-incremental -r (Join-Path $Out "archpheneos-mousepad.apk") } "adb install"
    Run-Native { & $Adb -s $Serial shell am start -n org.archphene.linux.mousepad/org.archphene.linux.mousepad.MainActivity } "adb launch"
}











