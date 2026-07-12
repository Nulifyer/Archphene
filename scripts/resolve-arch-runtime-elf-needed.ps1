param(
    [string]$RuntimeRoot = "tooling/downloads/arch-curated-kcalc-x86_64/runtime-root",
    [string[]]$Start = @("usr/bin/kcalc"),
    [string]$ReadElf = "tooling/android-sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-readelf.exe"
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$RuntimeRoot = (Resolve-Path (Join-Path $Root $RuntimeRoot)).Path
$ReadElf = (Resolve-Path (Join-Path $Root $ReadElf)).Path
$LibDirs = @(
    (Join-Path $RuntimeRoot "usr/lib")
)

$provided = @{}
foreach ($dir in $LibDirs) {
    if (-not (Test-Path -LiteralPath $dir)) { continue }
    foreach ($file in Get-ChildItem -LiteralPath $dir -File) {
        if (-not $provided.ContainsKey($file.Name)) { $provided[$file.Name] = $file.FullName }
    }
}

$queue = New-Object System.Collections.Generic.Queue[string]
foreach ($entry in $Start) {
    $path = Join-Path $RuntimeRoot ($entry.TrimStart('/').Replace('/', [IO.Path]::DirectorySeparatorChar))
    if (-not (Test-Path -LiteralPath $path)) { throw "start object not found: $entry" }
    $queue.Enqueue((Resolve-Path $path).Path)
}

$visitedObjects = @{}
$resolved = @{}
$missing = New-Object System.Collections.Generic.SortedSet[string]
$edges = New-Object System.Collections.Generic.List[object]

while ($queue.Count -gt 0) {
    $object = $queue.Dequeue()
    if ($visitedObjects.ContainsKey($object)) { continue }
    $visitedObjects[$object] = $true

    $lines = & $ReadElf -d $object 2>$null
    if ($LASTEXITCODE -ne 0) { continue }
    foreach ($line in $lines) {
        if ($line -notmatch 'Shared library:\s+\[(.+?)\]') { continue }
        $name = $matches[1]
        if ($provided.ContainsKey($name)) {
            $target = $provided[$name]
            $resolved[$name] = $target
            $queue.Enqueue($target)
            $edges.Add([pscustomobject]@{ From = $object.Substring($RuntimeRoot.Length + 1); Needed = $name; Resolved = $target.Substring($RuntimeRoot.Length + 1) })
        } else {
            [void]$missing.Add($name)
            $edges.Add([pscustomobject]@{ From = $object.Substring($RuntimeRoot.Length + 1); Needed = $name; Resolved = "" })
        }
    }
}

$outDir = Split-Path -Parent $RuntimeRoot
$resolvedPath = Join-Path $outDir "elf-needed-resolved.tsv"
$missingPath = Join-Path $outDir "elf-needed-missing.txt"
$edgesPath = Join-Path $outDir "elf-needed-edges.tsv"
$resolved.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Name)`t$($_.Value.Substring($RuntimeRoot.Length + 1))" } | Set-Content -LiteralPath $resolvedPath
$missing | Set-Content -LiteralPath $missingPath
$edges | ForEach-Object { "$($_.From)`t$($_.Needed)`t$($_.Resolved)" } | Set-Content -LiteralPath $edgesPath

[pscustomobject]@{
    RuntimeRoot = $RuntimeRoot
    Start = $Start -join ', '
    VisitedObjects = $visitedObjects.Count
    ResolvedLibraries = $resolved.Count
    MissingLibraries = $missing.Count
    ResolvedFile = $resolvedPath
    MissingFile = $missingPath
    EdgesFile = $edgesPath
} | Format-List
