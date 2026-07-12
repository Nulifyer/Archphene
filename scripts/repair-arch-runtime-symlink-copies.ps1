param(
    [string]$RuntimeRoot = "tooling/downloads/arch-curated-kcalc-x86_64/runtime-root",
    [string]$PackageDir = "tooling/downloads/arch-curated-kcalc-x86_64/packages",
    [string[]]$IncludeArchivePrefix = @("usr/lib/")
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$RuntimeRoot = (Resolve-Path (Join-Path $Root $RuntimeRoot)).Path
$PackageDir = (Resolve-Path (Join-Path $Root $PackageDir)).Path

function Convert-ArchivePath([string]$Path) {
    return $Path.TrimStart('/').Replace('/', [IO.Path]::DirectorySeparatorChar)
}

function Get-LinkTargetFullName([string]$LinkFullName, [string]$Target) {
    if ($Target.StartsWith('/')) {
        return Join-Path $RuntimeRoot (Convert-ArchivePath $Target)
    }
    return Join-Path (Split-Path -Parent $LinkFullName) (Convert-ArchivePath $Target)
}

$links = New-Object System.Collections.Generic.List[object]
foreach ($pkg in Get-ChildItem -LiteralPath $PackageDir -File -Filter "*.pkg.tar.*") {
    $listing = & tar -tvf $pkg.FullName
    if ($LASTEXITCODE -ne 0) { throw "tar listing failed for $($pkg.FullName)" }
    foreach ($line in $listing) {
        if ($line -notmatch '^l') { continue }
        if ($line -notmatch '^l.+?\s+\w{3}\s+\d{1,2}\s+(?:\d{2}:\d{2}|\d{4})\s+(.+?)\s+->\s+(.+)$') { continue }
        $linkArchivePath = $matches[1]
        if ($IncludeArchivePrefix.Count -gt 0) {
            $included = $false
            foreach ($prefix in $IncludeArchivePrefix) {
                if ($linkArchivePath.StartsWith($prefix)) { $included = $true; break }
            }
            if (-not $included) { continue }
        }
        $target = $matches[2]
        $linkFull = Join-Path $RuntimeRoot (Convert-ArchivePath $linkArchivePath)
        $targetFull = Get-LinkTargetFullName $linkFull $target
        $links.Add([pscustomobject]@{
            Package = $pkg.Name
            Link = $linkFull
            Target = $targetFull
            ArchiveLink = $linkArchivePath
            ArchiveTarget = $target
        })
    }
}

$linkMap = @{}
foreach ($link in $links) { $linkMap[$link.Link] = $link }

function Resolve-CopySource([string]$Candidate) {
    $seen = @{}
    $current = $Candidate
    for ($i = 0; $i -lt 32; $i++) {
        if (Test-Path -LiteralPath $current -PathType Leaf) { return $current }
        if (-not $linkMap.ContainsKey($current)) { return $null }
        if ($seen.ContainsKey($current)) { return $null }
        $seen[$current] = $true
        $current = $linkMap[$current].Target
    }
    return $null
}

$created = 0
$missing = New-Object System.Collections.Generic.List[object]
foreach ($link in $links) {
    if (Test-Path -LiteralPath $link.Link) { continue }
    $source = Resolve-CopySource $link.Target
    if ($null -eq $source) {
        $missing.Add($link)
        continue
    }
    $dir = Split-Path -Parent $link.Link
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Copy-Item -LiteralPath $source -Destination $link.Link -Force
    $created++
}

$missingPath = Join-Path (Split-Path -Parent $RuntimeRoot) "missing-symlink-copy-targets.tsv"
if ($missing.Count -gt 0) {
    $missing | ForEach-Object { "$($_.ArchiveLink)`t$($_.ArchiveTarget)`t$($_.Package)" } | Set-Content -LiteralPath $missingPath
}

[pscustomobject]@{
    RuntimeRoot = $RuntimeRoot
    PackageDir = $PackageDir
    IncludeArchivePrefix = $IncludeArchivePrefix -join ', '
    LinkEntries = $links.Count
    CreatedCopies = $created
    MissingTargets = $missing.Count
    MissingTargetsFile = if ($missing.Count -gt 0) { $missingPath } else { $null }
} | Format-List
