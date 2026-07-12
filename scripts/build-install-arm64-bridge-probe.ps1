param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [switch]$SkipInstall
)

$ErrorActionPreference = "Stop"

function Run-Native([scriptblock]$Command, [string]$Step) {
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Step failed with exit code $LASTEXITCODE" }
}

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Sdk = Join-Path $Root "tooling/android-sdk"
$BuildTools = Join-Path $Sdk "build-tools/36.0.0"
$AndroidJar = Join-Path $Sdk "platforms/android-36/android.jar"
$NdkBin = Join-Path $Sdk "ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin"
$App = Join-Path $Root "prototypes/arm64-bridge-probe"
$Shared = Join-Path $Root "prototypes/shared-android-bridge/src"
$Out = Join-Path $App "out"
$Adb = Join-Path $Sdk "platform-tools/adb.exe"

$deviceState = (& $Adb -s $Serial get-state 2>$null)
if ($LASTEXITCODE -ne 0 -or $deviceState.Trim() -ne "device") {
    throw "ADB device $Serial is not authorized and online"
}
$abis = (& $Adb -s $Serial shell getprop ro.product.cpu.abilist).Trim()
if ($abis -notmatch '(^|,)arm64-v8a(,|$)') {
    throw "Device $Serial does not advertise arm64-v8a: $abis"
}

if (Test-Path $Out) { Remove-Item $Out -Recurse -Force }
$Compiled = Join-Path $Out "compiled"
$Generated = Join-Path $Out "gen"
$Classes = Join-Path $Out "classes"
$Dex = Join-Path $Out "dex"
$Lib = Join-Path $Out "apk/lib/arm64-v8a"
$GlibcRoot = Join-Path $Out "glibc-root"
New-Item -ItemType Directory -Force -Path $Compiled,$Generated,$Classes,$Dex,$Lib,$GlibcRoot | Out-Null

$Clang = Join-Path $NdkBin "aarch64-linux-android35-clang.cmd"
$Include = Join-Path $Root "prototypes/mousepad-android-app/wayland-include"
Run-Native { & $Clang -shared -fPIC -O2 -Wall -Wextra -o (Join-Path $Lib "libarchphene_arm64_probe.so") (Join-Path $App "arm64_probe.c") -ldl } "compile ARM64 JNI probe"
Run-Native { & $Clang -shared -fPIC -O2 -Wall -Wextra -I $Include "-Wl,-soname,libarchphene_wayland_client_android.so" -o (Join-Path $Lib "libarchphene_wayland_client_android.so") (Join-Path $Root "prototypes/mousepad-android-app/archphene_wayland_client_android.c") } "compile ARM64 Wayland shim"
$GlibcPackage = Join-Path $Root "tooling/downloads/archlinuxarm-aarch64/glibc-2.43+r22+g8362e8ce10b2-2-aarch64.pkg.tar.xz"
if (-not (Test-Path $GlibcPackage)) { throw "Missing signed Arch Linux ARM glibc input: $GlibcPackage" }
Run-Native { & tar -xf $GlibcPackage -C $GlibcRoot usr/include usr/lib/Scrt1.o usr/lib/crti.o usr/lib/crtn.o usr/lib/libc.so.6 usr/lib/libc_nonshared.a usr/lib/ld-linux-aarch64.so.1 } "extract ARM64 glibc build subset"
$Compiler = Join-Path $NdkBin "clang.exe"
$Linker = Join-Path $NdkBin "ld.lld.exe"
$Object = Join-Path $Out "glibc_probe.o"
$GlibcProbe = Join-Path $Lib "libarchphene_glibc_probe.so"
Run-Native { & $Compiler --target=aarch64-linux-gnu --sysroot=$GlibcRoot -fPIE -fno-stack-protector -O2 -Wall -Wextra -c (Join-Path $App "glibc_probe.c") -o $Object } "compile GNU/Linux ARM64 probe"
Run-Native { & $Linker -pie -dynamic-linker ./ld-linux-aarch64.so.1 -o $GlibcProbe (Join-Path $GlibcRoot "usr/lib/Scrt1.o") (Join-Path $GlibcRoot "usr/lib/crti.o") $Object (Join-Path $GlibcRoot "usr/lib/libc.so.6") (Join-Path $GlibcRoot "usr/lib/libc_nonshared.a") (Join-Path $GlibcRoot "usr/lib/ld-linux-aarch64.so.1") (Join-Path $GlibcRoot "usr/lib/crtn.o") } "link GNU/Linux ARM64 probe"
$CompatRuntime = Join-Path $Root "tooling/build/glibc-archphene-runtime-aarch64"
$CompatLoader = Join-Path $CompatRuntime "ld-linux-aarch64.so.1"
$CompatLibc = Join-Path $CompatRuntime "libc.so.6"
if (-not (Test-Path $CompatLoader) -or -not (Test-Path $CompatLibc)) {
    throw "Missing source-built ARM64 Android-app glibc runtime: $CompatRuntime"
}
Copy-Item $CompatLoader (Join-Path $Lib "libarchphene_glibc_loader.so")
Copy-Item $CompatLibc (Join-Path $Lib "libarchphene_glibc_libc.so")

Run-Native { & (Join-Path $BuildTools "aapt2.exe") compile --dir (Join-Path $App "res") -o (Join-Path $Compiled "res.zip") } "aapt2 compile"
Run-Native { & (Join-Path $BuildTools "aapt2.exe") link -o (Join-Path $Out "unsigned.apk") -I $AndroidJar --manifest (Join-Path $App "AndroidManifest.xml") --java $Generated (Join-Path $Compiled "res.zip") } "aapt2 link"

$JavaFiles = @(
    Get-ChildItem (Join-Path $App "src") -Recurse -Filter *.java
    Get-ChildItem $Shared -Recurse -Filter *.java
) | ForEach-Object FullName
Run-Native { & javac --release 17 -classpath $AndroidJar -d $Classes $JavaFiles } "javac"
$ClassFiles = Get-ChildItem $Classes -Recurse -Filter *.class | ForEach-Object FullName
Run-Native { & cmd.exe /d /c call (Join-Path $BuildTools "d8.bat") --lib $AndroidJar --min-api 23 --output $Dex $ClassFiles } "d8"

Push-Location $Dex
Run-Native { & jar uf (Join-Path $Out "unsigned.apk") classes.dex } "add classes.dex"
Pop-Location
Push-Location (Join-Path $Out "apk")
$NativeFiles = Get-ChildItem "lib/arm64-v8a" -File | ForEach-Object { "lib/arm64-v8a/$($_.Name)" }
Run-Native { & jar uf (Join-Path $Out "unsigned.apk") $NativeFiles } "add ARM64 libraries"
Pop-Location

$Signing = Join-Path $Root "tooling/signing"
New-Item -ItemType Directory -Force -Path $Signing | Out-Null
$Key = Join-Path $Signing "archpheneos-arm64-probe.keystore"
if (-not (Test-Path $Key)) {
    Run-Native { & keytool -genkeypair -keystore $Key -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Archphene ARM64 Probe,O=ArchpheneOS,C=US" } "keytool"
}
$Apk = Join-Path $Out "archphene-arm64-bridge-probe.apk"
Run-Native { & (Join-Path $BuildTools "zipalign.exe") -f 4 (Join-Path $Out "unsigned.apk") (Join-Path $Out "aligned.apk") } "zipalign"
Run-Native { & (Join-Path $BuildTools "apksigner.bat") sign --ks $Key --ks-pass pass:android --key-pass pass:android --out $Apk (Join-Path $Out "aligned.apk") } "sign"
Run-Native { & (Join-Path $BuildTools "apksigner.bat") verify --verbose $Apk } "verify"

if (-not $SkipInstall) {
    Run-Native { & $Adb -s $Serial install -r $Apk } "install on $Serial"
    Run-Native { & $Adb -s $Serial shell am start -W -n org.archphene.bridgeprobe/.MainActivity } "launch on $Serial"
}
Write-Host "ARM64 bridge probe built for $Serial ($abis): $Apk"
