param(
    [string]$AndroidSdk = "",
    [string]$OutputDirectory = ""
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
    throw "Android SDK not found"
}
$Sdk = Resolve-Path $SdkCandidate
$BuildTools = Join-Path $Sdk "build-tools/36.0.0"
$Platform = Join-Path $Sdk "platforms/android-36/android.jar"
$Aapt2 = Join-Path $BuildTools "aapt2.exe"
if (-not (Test-Path -LiteralPath $Aapt2 -PathType Leaf) -or
        -not (Test-Path -LiteralPath $Platform -PathType Leaf)) {
    throw "Android API 36 build inputs are missing"
}
$Python = (Get-Command python -ErrorAction Stop).Source
$App = Join-Path $Root "prototypes/kcalc-android-app"
$Out = if ($OutputDirectory) { [IO.Path]::GetFullPath($OutputDirectory) }
    else { Join-Path $Root "tooling/build/wrapper-templates/qt" }
$Work = Join-Path $Out "manifest-variant-work"
if (Test-Path -LiteralPath $Work) {
    Remove-Item -LiteralPath $Work -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $Out,$Work,(Join-Path $Work "compiled") | Out-Null

$Placeholder = "org.archphene.linux.p00000000000000000000000000000000"
$PlaceholderAuthority = "$Placeholder.documents"
$Manifest = [IO.File]::ReadAllText((Join-Path $App "AndroidManifest.xml"))
$Manifest = $Manifest.Replace('package="org.archphene.linux.kcalc"', 'package="' + $Placeholder + '"')
$Manifest = $Manifest.Replace('org.archphene.linux.kcalc.documents', $PlaceholderAuthority)
$Manifest = $Manifest.Replace('@drawable/kcalc_icon', '@drawable/linux_app_icon_png')
if (-not $Manifest.Contains($PlaceholderAuthority)) {
    throw "Wrapper authority placeholder was not applied"
}
$BaseManifest = Join-Path $Work "base-manifest.xml"
[IO.File]::WriteAllText($BaseManifest, $Manifest, [Text.UTF8Encoding]::new($false))
Run-Native { & $Aapt2 compile --dir (Join-Path $App "res") -o (Join-Path $Work "compiled/res.zip") } "aapt2 compile wrapper resources"

foreach ($Profile in @("generic", "document")) {
    foreach ($PermissionProfile in @("none", "audio-input", "camera", "audio-input-camera")) {
        $PermissionArgs = @()
        if ($PermissionProfile.Contains("audio-input")) {
            $PermissionArgs += @("--permission", "audio-input")
        }
        if ($PermissionProfile.Contains("camera")) {
            $PermissionArgs += @("--permission", "camera")
        }
        $Rendered = Join-Path $Work "$Profile-$PermissionProfile.xml"
        Run-Native { & $Python (Join-Path $Root "scripts/render-wrapper-manifest.py") --profile $Profile @PermissionArgs $BaseManifest $Rendered } "render $Profile/$PermissionProfile wrapper manifest"
        $Apk = Join-Path $Work "$Profile-$PermissionProfile.apk"
        Run-Native { & $Aapt2 link -o $Apk -I $Platform --manifest $Rendered (Join-Path $Work "compiled/res.zip") } "compile $Profile/$PermissionProfile wrapper manifest"

        $Archive = [IO.Compression.ZipFile]::OpenRead($Apk)
        try {
            $Entry = $Archive.GetEntry("AndroidManifest.xml")
            if ($null -eq $Entry -or $Entry.Length -le 0) {
                throw "Compiled wrapper manifest is missing"
            }
            $Target = Join-Path $Out "qt-$Profile-manifest-$PermissionProfile.bin"
            $Input = $Entry.Open()
            $Output = [IO.File]::Create($Target)
            try { $Input.CopyTo($Output) }
            finally { $Output.Dispose(); $Input.Dispose() }
        } finally {
            $Archive.Dispose()
        }

        $Dump = (& $Aapt2 dump xmltree $Apk --file AndroidManifest.xml) -join [Environment]::NewLine
        if ($LASTEXITCODE -ne 0) { throw "Could not inspect $Profile/$PermissionProfile manifest" }
        $HasAudio = $Dump.Contains("android.permission.RECORD_AUDIO")
        $HasCamera = $Dump.Contains("android.permission.CAMERA")
        if ($HasAudio -ne $PermissionProfile.Contains("audio-input") -or
                $HasCamera -ne $PermissionProfile.Contains("camera")) {
            throw "Dangerous permission mismatch in $Profile/$PermissionProfile manifest"
        }
        $HasDocuments = $Dump.Contains("application/x-archphene-mime-00")
        if ($HasDocuments -ne ($Profile -eq "document")) {
            throw "Document intent mismatch in $Profile/$PermissionProfile manifest"
        }
    }
}
Write-Host "Wrapper manifest variants: $Out"
