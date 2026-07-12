param(
    [switch]$SkipInstall,
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
[IO.File]::WriteAllText($BuildManifest, $ManifestText, [Text.UTF8Encoding]::new($false))
Run-Native { & (Join-Path $BuildTools "aapt2.exe") compile --dir (Join-Path $App "res") -o (Join-Path $Out "compiled/res.zip") } "aapt2 compile"
$Assets = Join-Path $App "assets"
Run-Native { & (Join-Path $BuildTools "aapt2.exe") link -o (Join-Path $Out "unsigned.apk") -I (Join-Path $Sdk "platforms/android-36/android.jar") --version-code $VersionCode --version-name $VersionName --manifest $BuildManifest --java (Join-Path $Out "gen") -A $Assets (Join-Path $Out "compiled/res.zip") } "aapt2 link"

$JavaFiles = @(
    Get-ChildItem -LiteralPath (Join-Path $App "src") -Recurse -Filter *.java
    Get-ChildItem -LiteralPath (Join-Path $Out "gen") -Recurse -Filter *.java
) | ForEach-Object { $_.FullName }
Run-Native { & javac --release 17 -classpath (Join-Path $Sdk "platforms/android-36/android.jar") -d (Join-Path $Out "classes") $JavaFiles } "javac"

$ClassFiles = Get-ChildItem -LiteralPath (Join-Path $Out "classes") -Recurse -Filter *.class | ForEach-Object { $_.FullName }
Run-Native { & (Join-Path $BuildTools "d8.bat") --lib (Join-Path $Sdk "platforms/android-36/android.jar") --min-api 23 --output (Join-Path $Out "dex") $ClassFiles } "d8"

Push-Location (Join-Path $Out "dex")
Run-Native { & jar uf "..\unsigned.apk" classes.dex } "jar update apk"
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
Run-Native { & (Join-Path $BuildTools "zipalign.exe") -f 4 (Join-Path $Out "unsigned.apk") (Join-Path $Out "aligned.apk") } "zipalign"
Run-Native { & (Join-Path $BuildTools "apksigner.bat") sign --ks $Key --ks-key-alias $KeyAlias --ks-pass "pass:$KeystorePassword" --key-pass "pass:$KeyPassword" --out (Join-Path $Out "archpheneos-manager.apk") (Join-Path $Out "aligned.apk") } "apksigner sign"
Run-Native { & (Join-Path $BuildTools "apksigner.bat") verify --verbose (Join-Path $Out "archpheneos-manager.apk") } "apksigner verify"

if (-not $SkipInstall) {
    $Adb = Join-Path $Sdk "platform-tools/adb.exe"
    Run-Native { & $Adb -s $Serial install -r (Join-Path $Out "archpheneos-manager.apk") } "adb install"
    Run-Native { & $Adb -s $Serial shell monkey -p org.archpheneos.manager -c android.intent.category.LAUNCHER 1 } "adb launch"
}
