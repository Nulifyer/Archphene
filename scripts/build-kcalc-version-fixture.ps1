param(
    [string]$Version = "26.04.0-1",
    [int]$VersionCode = 26040001,
    [string]$OutputPath = "tooling/build/kcalc-version-fixtures/kcalc-26.04.0-1.apk"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$PackageName = "kcalc-$Version-x86_64.pkg.tar.zst"
$Package = Join-Path $Root "tooling/downloads/arch-curated-kcalc-x86_64/packages/$PackageName"
$Signature = "$Package.sig"
if (-not (Test-Path -LiteralPath $Package) -or -not (Test-Path -LiteralPath $Signature)) {
    throw "Archived KCalc package fixture is missing: $PackageName"
}
$Work = Join-Path $Root "tooling/build/kcalc-version-fixtures/$Version"
New-Item -ItemType Directory -Force -Path $Work | Out-Null
$Extracted = Join-Path $Work "libarchphene_kcalc.so"
$DescriptorPath = Join-Path $Work "archphene-app.json"
$CurrentDescriptorPath = Join-Path $Root "prototypes/kcalc-android-app/archphene-app.json"
$Payload = Join-Path $Root "prototypes/kcalc-android-app/lib/x86_64/libarchphene_kcalc.so"
$Backup = Join-Path $Work "current-libarchphene_kcalc.so"
$ContainerRoot = $Root.Path.Replace('\', '/')
$PackageRelative = "tooling/downloads/arch-curated-kcalc-x86_64/packages/$PackageName"
$ExtractedRelative = "tooling/build/kcalc-version-fixtures/$Version/libarchphene_kcalc.so"

& podman run --rm -v "${ContainerRoot}:/workspace" -w /workspace `
    localhost/archphene-qt-probe-builder:6.11 bash -lc `
    "pacman-key --init >/dev/null && pacman-key --populate archlinux >/dev/null && pacman-key --verify '$PackageRelative.sig' '$PackageRelative' && bsdtar -xOf '$PackageRelative' usr/bin/kcalc > '$ExtractedRelative'"
if ($LASTEXITCODE -ne 0) { throw "Archived KCalc signature verification or extraction failed" }

$Descriptor = Get-Content -LiteralPath $CurrentDescriptorPath -Raw | ConvertFrom-Json
$Descriptor.android.versionName = $Version
$Descriptor.android.versionCode = $VersionCode
$Descriptor.source.packageFilename = $PackageName
$Descriptor.source.signatureUrl = "https://archive.archlinux.org/packages/k/kcalc/$PackageName.sig"
$Descriptor.payload.sha256 = (Get-FileHash -LiteralPath $Extracted -Algorithm SHA256).Hash
$Descriptor | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $DescriptorPath -Encoding utf8NoBOM

Copy-Item -LiteralPath $Payload -Destination $Backup -Force
try {
    Copy-Item -LiteralPath $Extracted -Destination $Payload -Force
    & (Join-Path $PSScriptRoot "build-install-kcalc-app.ps1") -SkipInstall -DescriptorPath $DescriptorPath
    if ($LASTEXITCODE -ne 0) { throw "Archived KCalc wrapper build failed" }
    $ResolvedOutput = Join-Path $Root $OutputPath
    New-Item -ItemType Directory -Force -Path (Split-Path $ResolvedOutput) | Out-Null
    Copy-Item -LiteralPath (Join-Path $Root "prototypes/kcalc-android-app/out/archpheneos-kcalc.apk") `
        -Destination $ResolvedOutput -Force
    Write-Host "Built signed KCalc $Version wrapper fixture: $ResolvedOutput"
}
finally {
    Copy-Item -LiteralPath $Backup -Destination $Payload -Force
}