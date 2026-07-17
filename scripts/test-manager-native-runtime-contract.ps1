param(
    [Parameter(Mandatory = $true)]
    [string]$Apk,
    [ValidateSet("x86_64", "arm64-v8a")]
    [string]$Abi,
    [string]$AndroidSdk = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Apk = (Resolve-Path $Apk).Path
$Sdk = if ($AndroidSdk) { (Resolve-Path $AndroidSdk).Path }
    elseif (Test-Path -LiteralPath (Join-Path $Root "tooling/android-sdk")) {
        (Resolve-Path (Join-Path $Root "tooling/android-sdk")).Path
    } else { throw "Android SDK not found" }
$Aapt2 = Join-Path $Sdk "build-tools/36.0.0/aapt2.exe"
if (-not (Test-Path -LiteralPath $Aapt2 -PathType Leaf)) {
    throw "aapt2 not found: $Aapt2"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($Apk)
try {
    $prefix = "lib/$Abi/"
    $native = @($archive.Entries | Where-Object {
        $_.FullName.StartsWith($prefix, [StringComparison]::Ordinal) -and
        -not $_.FullName.EndsWith("/", [StringComparison]::Ordinal)
    })
    if ($native.Count -eq 0) { throw "APK has no $Abi native libraries" }
    $invalid = @($native | Where-Object {
        $_.FullName.Substring($prefix.Length) -notmatch "^lib[A-Za-z0-9_.+-]+[.]so$"
    })
    if ($invalid.Count -gt 0) {
        throw "APK contains native names Android will not extract: $($invalid.FullName -join ', ')"
    }

    $architecture = if ($Abi -eq "arm64-v8a") { "aarch64" } else { "x86_64" }
    $catalogName = "assets/package-runtime/manager-native-$architecture.tsv"
    $catalogEntry = $archive.GetEntry($catalogName)
    if ($null -eq $catalogEntry) { throw "Manager native catalog is missing: $catalogName" }
    $reader = [IO.StreamReader]::new($catalogEntry.Open(), [Text.Encoding]::UTF8, $true)
    try { $lines = @($reader.ReadToEnd() -split "\r?\n") } finally { $reader.Dispose() }

    $logicalNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $packagedNames = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $count = 0
    foreach ($line in $lines) {
        if (-not $line -or $line.StartsWith("#", [StringComparison]::Ordinal)) { continue }
        $fields = $line -split "	"
        if ($fields.Count -ne 4 -or
                $fields[0] -notmatch "^[A-Za-z0-9@._+-]{1,128}$" -or
                $fields[1] -notmatch "^lib[A-Za-z0-9_.+-]+[.]so$" -or
                $fields[2] -notmatch "^[0-9a-f]{64}$") {
            throw "Invalid manager native catalog row: $line"
        }
        [long]$size = 0
        if (-not [long]::TryParse($fields[3], [ref]$size) -or $size -le 0 -or
                -not $logicalNames.Add($fields[0]) -or
                -not $packagedNames.Add($fields[1])) {
            throw "Duplicate or invalid manager native catalog row: $line"
        }
        $entry = $archive.GetEntry($prefix + $fields[1])
        if ($null -eq $entry -or $entry.Length -ne $size) {
            throw "Catalog payload is missing or has the wrong size: $($fields[1])"
        }
        $sha = [Security.Cryptography.SHA256]::Create()
        $stream = $entry.Open()
        try { $hash = [Convert]::ToHexString($sha.ComputeHash($stream)).ToLowerInvariant() }
        finally { $stream.Dispose(); $sha.Dispose() }
        if ($hash -ne $fields[2]) {
            throw "Catalog payload hash mismatch: $($fields[1])"
        }
        $count++
    }
    if ($count -eq 0 -or -not $logicalNames.Contains("libc.so.6") -or
            -not $logicalNames.Contains("libalpm.so.16")) {
        throw "Manager native catalog lacks required glibc or libalpm sonames"
    }
    if ($null -eq $archive.GetEntry($prefix + "libarchphene_pacman.so")) {
        throw "Manager pacman executable is missing"
    }
} finally {
    $archive.Dispose()
}

$manifest = (& $Aapt2 dump xmltree $Apk --file AndroidManifest.xml) -join [Environment]::NewLine
if ($LASTEXITCODE -ne 0 -or
        $manifest -notmatch "android:extractNativeLibs[^\r\n]*=true") {
    throw "Manager APK does not explicitly enable native library extraction"
}

Write-Host "Manager native runtime contract passed: $Abi, $count soname aliases, $($native.Count) extractable libraries."
