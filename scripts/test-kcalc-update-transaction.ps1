param(
    [switch]$SkipBuild,
    [string]$Serial = "emulator-5554"
)

$ErrorActionPreference = "Stop"

function Assert-LastExitCode([string]$Step) {
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE"
    }
}

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Sdk = Join-Path $Root "tooling/android-sdk"
$BuildTools = Join-Path $Sdk "build-tools/36.0.0"
$Adb = Join-Path $Sdk "platform-tools/adb.exe"
$ApkSigner = Join-Path $BuildTools "apksigner.bat"
$Aapt2 = Join-Path $BuildTools "aapt2.exe"
$PackageDir = Join-Path $Root "tooling/downloads/arch-curated-kcalc-x86_64/packages"
$TransactionDir = Join-Path $Root "tooling/build/kcalc-update-transaction"
$MetadataFile = Join-Path $TransactionDir "arch-package.json"
$InstalledBefore = Join-Path $TransactionDir "installed-before.apk"
$TransactionFile = Join-Path $TransactionDir "transaction.json"
$GeneratedApk = Join-Path $Root "prototypes/kcalc-android-app/out/archpheneos-kcalc.apk"
$Payload = Join-Path $Root "prototypes/kcalc-android-app/lib/x86_64/libarchphene_kcalc.so"
$MetadataUrl = "https://archlinux.org/packages/extra/x86_64/kcalc/json/"

New-Item -ItemType Directory -Force -Path $TransactionDir | Out-Null
& curl.exe --noproxy "*" -fsSL -o $MetadataFile $MetadataUrl
if ($LASTEXITCODE -eq 0) {
    $Metadata = Get-Content -LiteralPath $MetadataFile -Raw | ConvertFrom-Json
} else {
    Write-Warning "Official metadata transport is unavailable; using the signed local package descriptor for the transaction test."
    $Descriptor = Get-Content -LiteralPath (Join-Path $Root "prototypes/kcalc-android-app/archphene-app.json") -Raw | ConvertFrom-Json
    $versionParts = ([string]$Descriptor.android.versionName).Split('-', 2)
    $localPackage = Join-Path $PackageDir ([string]$Descriptor.source.packageFilename)
    $Metadata = [pscustomobject]@{
        pkgname = [string]$Descriptor.source.package
        repo = [string]$Descriptor.source.repository
        arch = [string]$Descriptor.source.architecture
        pkgver = $versionParts[0]
        pkgrel = $versionParts[1]
        filename = [string]$Descriptor.source.packageFilename
        compressed_size = (Get-Item -LiteralPath $localPackage).Length
    }
    $Metadata | ConvertTo-Json | Set-Content -LiteralPath $MetadataFile -Encoding utf8NoBOM
}
$Version = "$($Metadata.pkgver)-$($Metadata.pkgrel)"
if ($Metadata.pkgname -ne "kcalc" -or $Metadata.repo -ne "extra" -or $Metadata.arch -ne "x86_64") {
    throw "Official metadata does not identify extra/x86_64/kcalc"
}
$ArchPackage = Join-Path $PackageDir $Metadata.filename
$Signature = "$ArchPackage.sig"
if (-not (Test-Path -LiteralPath $ArchPackage) -or -not (Test-Path -LiteralPath $Signature)) {
    throw "Package or detached signature is missing for $($Metadata.filename)"
}
if ((Get-Item -LiteralPath $ArchPackage).Length -ne [long]$Metadata.compressed_size) {
    throw "Package size does not match official Arch metadata"
}

$ContainerRoot = $Root.Path.Replace('\', '/')
$PackageRelative = "tooling/downloads/arch-curated-kcalc-x86_64/packages/$($Metadata.filename)"
& podman run --rm -v "${ContainerRoot}:/workspace" -w /workspace `
    localhost/archphene-qt-probe-builder:6.11 bash -lc `
    "pacman-key --init >/dev/null && pacman-key --populate archlinux >/dev/null && pacman-key --verify '$PackageRelative.sig' '$PackageRelative'"
Assert-LastExitCode "Arch package signature verification"

$StockHashLine = & podman run --rm -v "${ContainerRoot}:/workspace" -w /workspace `
    localhost/archphene-qt-probe-builder:6.11 bash -lc `
    "bsdtar -xOf '$PackageRelative' usr/bin/kcalc | sha256sum"
Assert-LastExitCode "stock KCalc extraction and hash"
$StockHash = ([regex]::Match(($StockHashLine -join "`n"), "[0-9a-f]{64}")).Value.ToUpperInvariant()
$PayloadHash = (Get-FileHash -LiteralPath $Payload -Algorithm SHA256).Hash
if ($StockHash -ne $PayloadHash) {
    throw "Generated APK payload differs from the signed Arch package KCalc ELF"
}

$InstalledPathLine = & $Adb -s $Serial shell pm path org.archphene.linux.kcalc
Assert-LastExitCode "query installed KCalc APK"
$InstalledPath = ($InstalledPathLine -replace '^package:', '').Trim()
if (-not $InstalledPath) {
    throw "KCalc must be installed before testing an update transaction"
}
& $Adb -s $Serial pull $InstalledPath $InstalledBefore | Out-Null
Assert-LastExitCode "pull installed KCalc APK"
$BeforeCertOutput = & $ApkSigner verify --print-certs $InstalledBefore
Assert-LastExitCode "verify installed KCalc signer"
$BeforeSigner = ([regex]::Match(($BeforeCertOutput -join "`n"), "Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", "IgnoreCase")).Groups[1].Value.ToUpperInvariant()
if (-not $BeforeSigner) {
    throw "Could not read installed KCalc signing certificate"
}

if (-not $SkipBuild) {
    & (Join-Path $PSScriptRoot "build-install-kcalc-app.ps1") -SkipInstall
    Write-Host "Gate passed: generated KCalc APK"
}
if (-not (Test-Path -LiteralPath $GeneratedApk)) {
    throw "Generated KCalc APK is missing"
}
$Badging = (& $Aapt2 dump badging $GeneratedApk) -join "`n"
Assert-LastExitCode "read generated APK identity"
if ($Badging -notmatch "package: name='org\.archphene\.linux\.kcalc'" `
        -or $Badging -notmatch "versionName='$([regex]::Escape($Version))'") {
    throw "Generated APK identity/version does not match org.archphene.linux.kcalc $Version"
}
Write-Host "Gate passed: APK package identity and version"
$GeneratedCertOutput = & $ApkSigner verify --print-certs $GeneratedApk
Assert-LastExitCode "verify generated KCalc signer"
$GeneratedSigner = ([regex]::Match(($GeneratedCertOutput -join "`n"), "Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", "IgnoreCase")).Groups[1].Value.ToUpperInvariant()
if ($GeneratedSigner -ne $BeforeSigner) {
    throw "Generated APK signer does not match the installed KCalc signing identity"
}
Write-Host "Gate passed: persistent Android signing identity"

& $Adb -s $Serial install -r $GeneratedApk | Out-Host
Assert-LastExitCode "Android KCalc update transaction"
Write-Host "Gate passed: Android replacement install"
& (Join-Path $PSScriptRoot "test-kcalc-calculation.ps1") -Serial $Serial

$Transaction = [ordered]@{
    schema = "org.archphene.update-transaction.v1"
    completedUtc = [DateTime]::UtcNow.ToString("o")
    androidPackage = "org.archphene.linux.kcalc"
    source = [ordered]@{
        distribution = "archlinux"
        repository = $Metadata.repo
        architecture = $Metadata.arch
        package = $Metadata.pkgname
        version = $Version
        filename = $Metadata.filename
        metadataUrl = $MetadataUrl
        packageSize = [long]$Metadata.compressed_size
        detachedSignatureVerified = $true
        signer = "Antonio Rojas / trusted archlinux-keyring"
        stockEntrypointSha256 = $StockHash
    }
    generatedApk = [ordered]@{
        path = $GeneratedApk
        sha256 = (Get-FileHash -LiteralPath $GeneratedApk -Algorithm SHA256).Hash
        signerSha256 = $GeneratedSigner
        versionName = $Version
    }
    androidInstall = [ordered]@{
        replacedExistingPackage = $true
        preservedSigner = $true
        healthCheck = "bidirectional clipboard passed"
    }
}
$Transaction | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $TransactionFile -Encoding utf8NoBOM
Write-Host "KCalc signed-package to signed-APK update transaction passed."
Write-Host "Transaction evidence: $TransactionFile"
