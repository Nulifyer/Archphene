param(
    [switch]$SkipInstall,
    [switch]$ReleaseBuild,
    [switch]$IncludePackageRuntime,
    [string]$TerminalApk = "",
    [string]$Serial = "emulator-5554",
    [int]$VersionCode = 10000,
    [string]$VersionName = "1.0.0",
    [string]$AndroidSdk = "",
    [string]$KeystorePath = "",
    [string]$KeystorePassword = "",
    [string]$KeyAlias = "androiddebugkey",
    [string]$KeyPassword = ""
)

$ErrorActionPreference = "Stop"

function Run-Native([scriptblock]$Command, [string]$Step) {
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE"
    }
}

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$LocalSdk = Join-Path $Root "tooling/android-sdk"
$SdkCandidate = if ($AndroidSdk) { $AndroidSdk }
    elseif (Test-Path -LiteralPath $LocalSdk) { $LocalSdk }
    elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
    elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME }
    else { "" }
if (-not $SdkCandidate -or -not (Test-Path -LiteralPath $SdkCandidate)) {
    throw "Android SDK not found; pass -AndroidSdk or set ANDROID_SDK_ROOT"
}
$Sdk = Resolve-Path $SdkCandidate
$BuildTools = Join-Path $Sdk "build-tools/36.0.0"
$ApkSignerJar = Join-Path $BuildTools "lib/apksigner.jar"
if (-not (Test-Path -LiteralPath $ApkSignerJar -PathType Leaf)) {
    throw "Android apksig library not found: $ApkSignerJar"
}
$App = Join-Path $Root "prototypes/linux-app-manager-stub"
$Out = Join-Path $App "out"

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
$ManifestText = Get-Content -LiteralPath (Join-Path $App "AndroidManifest.xml") -Raw
$ManifestText = [regex]::Replace($ManifestText, 'android:versionCode="[^"]+"',
        "android:versionCode=`"$VersionCode`"")
$ManifestText = [regex]::Replace($ManifestText, 'android:versionName="[^"]+"',
        "android:versionName=`"$VersionName`"")
if ($ReleaseBuild) {
    $ManifestText = $ManifestText.Replace('android:debuggable="true"',
            'android:debuggable="false"')
}
[IO.File]::WriteAllText($BuildManifest, $ManifestText, [Text.UTF8Encoding]::new($false))
Run-Native { & (Join-Path $BuildTools "aapt2.exe") compile --dir (Join-Path $App "res") -o (Join-Path $Out "compiled/res.zip") } "aapt2 compile"
$PackageRuntimeStage = Join-Path $Out "package-runtime"
$PackageLibDir = Join-Path $PackageRuntimeStage "lib/x86_64"
$PackageAssetDir = Join-Path $PackageRuntimeStage "assets/package-runtime"
New-Item -ItemType Directory -Force -Path $PackageLibDir, $PackageAssetDir | Out-Null
if ($TerminalApk) {
    $TerminalApk = Resolve-Path $TerminalApk
    Copy-Item -LiteralPath $TerminalApk `
        -Destination (Join-Path $PackageAssetDir "archphene-terminal.apk") -Force
}
Copy-Item -LiteralPath (Join-Path $App "assets/payload-hello-linux-amd64") `
    -Destination (Join-Path $PackageLibDir "libarchphene_runtime_probe.so") -Force
Copy-Item -LiteralPath (Join-Path $App "assets/payload-hello-dynamic-amd64") `
    -Destination (Join-Path $PackageLibDir "libarchphene_dynamic_probe.so") -Force
Copy-Item -LiteralPath (Join-Path $App "assets/payload-hello-transitive-amd64") `
    -Destination (Join-Path $PackageLibDir "libarchphene_transitive_probe.so") -Force
Copy-Item -LiteralPath (Join-Path $App "assets/payload-runtime-dependency-amd64") `
    -Destination (Join-Path $PackageLibDir "libarchphene_probe_dependency.so") -Force
if ($IncludePackageRuntime) {
    $RuntimeWork = Join-Path $Root "tooling/downloads/arch-runtime-pacman-x86_64"
    $RuntimeRoot = Join-Path $RuntimeWork "runtime-root"
    $ResolvedManifest = Join-Path $RuntimeWork "elf-needed-resolved.tsv"
    $PatchedGlibc = Join-Path $Root "tooling/build/glibc-archphene-runtime-x86_64"
    foreach ($required in @($RuntimeRoot, $ResolvedManifest, $PatchedGlibc)) {
        if (-not (Test-Path -LiteralPath $required)) {
            throw "Package runtime input missing: $required"
        }
    }
    $KeyringDir = Join-Path $Root "tooling/downloads/arch-runtime-archlinux-keyring-x86_64/runtime-root/usr/share/pacman/keyrings"
    $KeyringAssets = @{
        "archlinux.gpg" = "archlinux-x86_64.gpg"
        "archlinux-revoked" = "archlinux-x86_64-revoked"
        "archlinux-trusted" = "archlinux-x86_64-trusted"
    }
    foreach ($name in $KeyringAssets.Keys) {
        $source = Join-Path $KeyringDir $name
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Arch keyring input missing: $source"
        }
        Copy-Item -LiteralPath $source -Destination `
            (Join-Path $PackageAssetDir $KeyringAssets[$name]) -Force
    }
    $QtTemplateDirectory = Join-Path $Root "tooling/build/wrapper-templates/qt"
    $QtTemplate = Join-Path $QtTemplateDirectory "qt-wrapper-template.apk"
    if (-not (Test-Path -LiteralPath $QtTemplate -PathType Leaf)) {
        & (Join-Path $PSScriptRoot "build-qt-wrapper-template.ps1") -AndroidSdk $Sdk
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $QtTemplate -PathType Leaf)) {
            throw "Qt wrapper template build failed"
        }
    }
    $ManifestVariants = foreach ($profile in @("generic", "document")) {
        foreach ($permissions in @("none", "audio-input", "camera", "audio-input-camera")) {
            Join-Path $QtTemplateDirectory "qt-$profile-manifest-$permissions.bin"
        }
    }
    if ($ManifestVariants.Where({ -not (Test-Path -LiteralPath $_ -PathType Leaf) }).Count -gt 0) {
        & (Join-Path $PSScriptRoot "build-wrapper-manifest-variants.ps1") -AndroidSdk $Sdk -OutputDirectory $QtTemplateDirectory
        if ($LASTEXITCODE -ne 0) { throw "Wrapper manifest variant build failed" }
    }
    Copy-Item -LiteralPath $QtTemplate -Destination (Join-Path $PackageAssetDir "qt-wrapper-template.apk") -Force
    foreach ($variant in $ManifestVariants) {
        if (-not (Test-Path -LiteralPath $variant -PathType Leaf)) {
            throw "Wrapper manifest variant is missing: $variant"
        }
        Copy-Item -LiteralPath $variant -Destination (Join-Path $PackageAssetDir ([IO.Path]::GetFileName($variant))) -Force
    }
    $tools = @{
        "usr/bin/pacman" = "libarchphene_pacman.so"
        "usr/bin/gpg" = "libarchphene_gpg.so"
        "usr/bin/gpgv" = "libarchphene_gpgv.so"
        "usr/bin/bsdtar" = "libarchphene_bsdtar.so"
    }
    foreach ($entry in $tools.GetEnumerator()) {
        $source = Join-Path $RuntimeRoot $entry.Key
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Package runtime tool missing: $source"
        }
        Copy-Item -LiteralPath $source -Destination (Join-Path $PackageLibDir $entry.Value) -Force
    }
    foreach ($line in Get-Content -LiteralPath $ResolvedManifest) {
        $parts = $line -split "`t", 2
        if ($parts.Count -ne 2) { continue }
        $source = Join-Path $RuntimeRoot $parts[1]
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Resolved package runtime library missing: $source"
        }
        Copy-Item -LiteralPath $source -Destination (Join-Path $PackageLibDir $parts[0]) -Force
    }
    Copy-Item -LiteralPath (Join-Path $PatchedGlibc "ld-linux-x86-64.so.2") `
        -Destination (Join-Path $PackageLibDir "libarchphene_ld.so") -Force
    Copy-Item -LiteralPath (Join-Path $PatchedGlibc "libc.so.6") `
        -Destination (Join-Path $PackageLibDir "libarchphene_runtime_libc.so") -Force
    Get-ChildItem -LiteralPath $PatchedGlibc -File | Where-Object {
        $_.Name -notin @("runtime-manifest.tsv", "source-commit.txt", "ld-linux-x86-64.so.2")
    } | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $PackageLibDir $_.Name) -Force
    }
}

$RuntimeModules = @(
    @{ Role = "static-probe"; Library = "libarchphene_runtime_probe.so"; Link = "program" }
    @{ Role = "dynamic-probe"; Library = "libarchphene_dynamic_probe.so"; Link = "program" }
    @{ Role = "transitive-probe"; Library = "libarchphene_transitive_probe.so"; Link = "program" }
    @{ Role = "transitive-probe-library"; Library = "libarchphene_probe_dependency.so"; Link = "libarchphene_probe_dependency.so" }
)
if ($IncludePackageRuntime) {
    $RuntimeModules += @(
        @{ Role = "glibc-loader"; Library = "libarchphene_ld.so"; Link = "ld-linux-x86-64.so.2" }
        @{ Role = "glibc-libc"; Library = "libarchphene_runtime_libc.so"; Link = "libc.so.6" }
    )
}
$RuntimeCatalog = foreach ($module in $RuntimeModules) {
    $file = Join-Path $PackageLibDir $module.Library
    if (-not (Test-Path -LiteralPath $file -PathType Leaf)) {
        throw "Runtime module missing: $($module.Library)"
    }
    $hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    $size = (Get-Item -LiteralPath $file).Length
    "$($module.Role)`t$hash`t$size`t$($module.Library)`t$($module.Link)"
}
[IO.File]::WriteAllText((Join-Path $PackageAssetDir "runtime-modules.tsv"),
        "# org.archphene.runtime-modules.v1`n" + ($RuntimeCatalog -join "`n") + "`n",
        [Text.UTF8Encoding]::new($false))

$Assets = Join-Path $App "assets"
Run-Native { & (Join-Path $BuildTools "aapt2.exe") link -o (Join-Path $Out "unsigned.apk") -I (Join-Path $Sdk "platforms/android-36/android.jar") --version-code $VersionCode --version-name $VersionName --manifest $BuildManifest --java (Join-Path $Out "gen") -A $Assets (Join-Path $Out "compiled/res.zip") } "aapt2 link"

$JavaFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $App "src") -Recurse -Filter *.java
    Get-ChildItem -LiteralPath (Join-Path $Out "gen") -Recurse -Filter *.java
) | ForEach-Object { $_.FullName }
$CompileClasspath = (Join-Path $Sdk "platforms/android-36/android.jar") + [IO.Path]::PathSeparator + $ApkSignerJar
Run-Native { & javac --release 17 -classpath $CompileClasspath -d (Join-Path $Out "classes") $JavaFiles } "javac"

$ClassesJar = Join-Path $Out "classes.jar"
Run-Native { & jar --create --file $ClassesJar -C (Join-Path $Out "classes") . } "archive classes"
Run-Native { & (Join-Path $BuildTools "d8.bat") --lib (Join-Path $Sdk "platforms/android-36/android.jar") --min-api 23 --output (Join-Path $Out "dex") $ClassesJar $ApkSignerJar } "d8"

Push-Location (Join-Path $Out "dex")
Run-Native { & jar uf "..\unsigned.apk" classes.dex } "jar update apk"
Pop-Location

Push-Location $PackageRuntimeStage
$PackageRuntimeFiles = Get-ChildItem -LiteralPath $PackageRuntimeStage -Recurse -File |
    ForEach-Object { $_.FullName.Substring($PackageRuntimeStage.Length + 1) }
Run-Native { & jar uf "..\unsigned.apk" $PackageRuntimeFiles } "jar add package runtime"
Pop-Location

$SigningDir = Join-Path $Root "tooling/signing"
New-Item -ItemType Directory -Force -Path $SigningDir | Out-Null
if ($KeystorePath) {
    if (-not (Test-Path -LiteralPath $KeystorePath)) { throw "Release keystore not found" }
    if (-not $KeystorePassword -or -not $KeyPassword -or -not $KeyAlias) {
        throw "Release signing requires keystore password, key password, and alias"
    }
    $Key = Resolve-Path $KeystorePath
} else {
    $Key = Join-Path $SigningDir "archpheneos-manager-debug.keystore"
    $KeystorePassword = "android"
    $KeyPassword = "android"
    $KeyAlias = "androiddebugkey"
    if (-not (Test-Path -LiteralPath $Key)) {
        Run-Native { & keytool -genkeypair -keystore $Key -storepass $KeystorePassword -keypass $KeyPassword -alias $KeyAlias -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Archphene,O=Archphene,C=US" } "keytool"
    }
}
Run-Native { & (Join-Path $BuildTools "zipalign.exe") -P 16 -f 4 (Join-Path $Out "unsigned.apk") (Join-Path $Out "aligned.apk") } "zipalign"
Run-Native { & (Join-Path $BuildTools "apksigner.bat") sign --ks $Key --ks-key-alias $KeyAlias --ks-pass "pass:$KeystorePassword" --key-pass "pass:$KeyPassword" --out (Join-Path $Out "archpheneos-manager.apk") (Join-Path $Out "aligned.apk") } "apksigner sign"
Run-Native { & (Join-Path $BuildTools "apksigner.bat") verify --verbose (Join-Path $Out "archpheneos-manager.apk") } "apksigner verify"

if (-not $SkipInstall) {
    $Adb = Join-Path $Sdk "platform-tools/adb.exe"
    Run-Native { & $Adb -s $Serial install -r (Join-Path $Out "archpheneos-manager.apk") } "adb install"
    Run-Native { & $Adb -s $Serial shell monkey -p org.archpheneos.manager -c android.intent.category.LAUNCHER 1 } "adb launch"
}
