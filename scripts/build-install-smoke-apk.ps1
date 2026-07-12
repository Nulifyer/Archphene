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
$App = Join-Path $Root "prototypes/android-smoke-apk"
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

Run-Native { & (Join-Path $BuildTools "aapt2.exe") compile --dir (Join-Path $App "res") -o (Join-Path $Out "compiled/res.zip") } "aapt2 compile"
Run-Native { & (Join-Path $BuildTools "aapt2.exe") link -o (Join-Path $Out "unsigned.apk") -I (Join-Path $Sdk "platforms/android-36/android.jar") --manifest (Join-Path $App "AndroidManifest.xml") --java (Join-Path $Out "gen") (Join-Path $Out "compiled/res.zip") } "aapt2 link"

$JavaFiles = Get-ChildItem -LiteralPath (Join-Path $App "src") -Recurse -Filter *.java | ForEach-Object { $_.FullName }
Run-Native { & javac --release 17 -classpath (Join-Path $Sdk "platforms/android-36/android.jar") -d (Join-Path $Out "classes") $JavaFiles } "javac"
Run-Native { & (Join-Path $BuildTools "d8.bat") --min-api 23 --output (Join-Path $Out "dex") (Join-Path $Out "classes/com/archpheneos/smoke/MainActivity.class") } "d8"

Push-Location (Join-Path $Out "dex")
Run-Native { & jar uf "..\unsigned.apk" classes.dex } "jar update apk"
Pop-Location

$Key = Join-Path $Out "debug.keystore"
Run-Native { & keytool -genkeypair -keystore $Key -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=ArchpheneOS Smoke,O=ArchpheneOS,C=US" } "keytool"
Run-Native { & (Join-Path $BuildTools "zipalign.exe") -f 4 (Join-Path $Out "unsigned.apk") (Join-Path $Out "aligned.apk") } "zipalign"
Run-Native { & (Join-Path $BuildTools "apksigner.bat") sign --ks $Key --ks-pass pass:android --key-pass pass:android --out (Join-Path $Out "archpheneos-smoke.apk") (Join-Path $Out "aligned.apk") } "apksigner sign"
Run-Native { & (Join-Path $BuildTools "apksigner.bat") verify --verbose (Join-Path $Out "archpheneos-smoke.apk") } "apksigner verify"

$Adb = Join-Path $Sdk "platform-tools/adb.exe"
Run-Native { & $Adb install -r (Join-Path $Out "archpheneos-smoke.apk") } "adb install"
Run-Native { & $Adb shell monkey -p com.archpheneos.smoke -c android.intent.category.LAUNCHER 1 } "adb launch"
