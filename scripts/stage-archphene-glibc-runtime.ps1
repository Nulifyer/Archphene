param(
    [string]$RuntimeDir = "tooling/build/glibc-archphene-runtime-x86_64",
    [string]$AppLibDir = "prototypes/kcalc-android-app/lib/x86_64",
    [string]$BackupDir = "tooling/backups/kcalc-arch-stock-glibc-x86_64"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$RuntimeDir = (Resolve-Path (Join-Path $Root $RuntimeDir)).Path
$AppLibDir = (Resolve-Path (Join-Path $Root $AppLibDir)).Path
$BackupDir = Join-Path $Root $BackupDir
New-Item -ItemType Directory -Force -Path $BackupDir | Out-Null

$runtimeFiles = @(
    "libc.so.6",
    "libm.so.6",
    "libdl.so.2",
    "libpthread.so.0",
    "librt.so.1",
    "libresolv.so.2",
    "libutil.so.1",
    "libanl.so.1",
    "libnss_dns.so.2",
    "libnss_files.so.2"
)

$loader = Join-Path $RuntimeDir "ld-linux-x86-64.so.2"
if (-not (Test-Path -LiteralPath $loader -PathType Leaf)) {
    throw "Bridge loader missing: $loader"
}

$loaderTargets = @(
    "libarchphene_ld.so",
    "libld.so.2",
    "ld-linux-x86-64.so.2"
)

foreach ($name in $loaderTargets + $runtimeFiles) {
    $destination = Join-Path $AppLibDir $name
    $backup = Join-Path $BackupDir $name
    if ((Test-Path -LiteralPath $destination -PathType Leaf) -and
        -not (Test-Path -LiteralPath $backup -PathType Leaf)) {
        Copy-Item -LiteralPath $destination -Destination $backup
    }
}

foreach ($name in $loaderTargets) {
    Copy-Item -LiteralPath $loader -Destination (Join-Path $AppLibDir $name) -Force
}

foreach ($name in $runtimeFiles) {
    $source = Join-Path $RuntimeDir $name
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
        throw "Bridge runtime library missing: $source"
    }
    Copy-Item -LiteralPath $source -Destination (Join-Path $AppLibDir $name) -Force
}

$hashes = foreach ($name in $loaderTargets + $runtimeFiles) {
    $file = Join-Path $AppLibDir $name
    $hash = Get-FileHash -LiteralPath $file -Algorithm SHA256
    [pscustomobject]@{
        Name = $name
        Bytes = (Get-Item -LiteralPath $file).Length
        Sha256 = $hash.Hash
    }
}

$manifest = Join-Path $RuntimeDir "runtime-manifest.tsv"
$tab = [string][char]9
$hashes | ForEach-Object { $_.Name + $tab + $_.Bytes + $tab + $_.Sha256 } |
    Set-Content -LiteralPath $manifest -Encoding utf8NoBOM

$hashes | Format-Table -AutoSize
Write-Host "Runtime manifest: $manifest"
Write-Host "Stock backup: $BackupDir"