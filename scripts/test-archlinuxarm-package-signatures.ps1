param(
    [string]$PackageDir = "tooling/downloads/arch-curated-kcalc-aarch64/packages",
    [string]$Keyring = "tooling/downloads/archlinuxarm-aarch64/archlinuxarm.gpg",
    [string]$Container = "archphene-glibc-incremental"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$PackageDir = (Resolve-Path (Join-Path $Root $PackageDir)).Path
$Keyring = (Resolve-Path (Join-Path $Root $Keyring)).Path
$ExpectedFingerprint = "68B3537F39A313B3E574D06777193F152BDBE6A6"
$Token = [guid]::NewGuid().ToString('N')
$Remote = "/tmp/archphene-signatures-$Token"
$RemotePackages = "$Remote/packages"
$RemoteHome = "$Remote/gnupg"

& podman exec $Container mkdir -p $RemotePackages $RemoteHome
if ($LASTEXITCODE -ne 0) { throw "Could not create signature workspace in $Container" }
& podman cp $Keyring "${Container}:$Remote/archlinuxarm.gpg"
if ($LASTEXITCODE -ne 0) { throw "Could not stage Arch Linux ARM keyring" }
& podman cp "$PackageDir/." "${Container}:$RemotePackages"
if ($LASTEXITCODE -ne 0) { throw "Could not stage AArch64 packages" }

$keys = & podman exec $Container gpg --show-keys --with-colons "$Remote/archlinuxarm.gpg"
if (($keys -join "`n") -notmatch "fpr:::::::::${ExpectedFingerprint}:") {
    throw "Keyring does not contain published Arch Linux ARM build fingerprint $ExpectedFingerprint"
}
& podman exec $Container gpg --homedir $RemoteHome --batch --import "$Remote/archlinuxarm.gpg" | Out-Null

$packages = Get-ChildItem $PackageDir -File | Where-Object {
    $_.Name -like "*.pkg.tar.*" -and $_.Name -notlike "*.sig"
}
$verified = 0
foreach ($package in $packages) {
    $signature = "$($package.Name).sig"
    if (-not (Test-Path (Join-Path $PackageDir $signature))) {
        throw "Missing detached signature for $($package.Name)"
    }
    $status = & podman exec $Container gpg --homedir $RemoteHome --batch --status-fd 1 `
            --verify "$RemotePackages/$signature" "$RemotePackages/$($package.Name)" 2>&1
    if (($status -join "`n") -notmatch "VALIDSIG $ExpectedFingerprint ") {
        throw "Invalid Arch Linux ARM package signature: $($package.Name)`n$($status -join "`n")"
    }
    $verified++
}

Write-Host "Arch Linux ARM signature gate passed for $verified AArch64 packages with $ExpectedFingerprint."