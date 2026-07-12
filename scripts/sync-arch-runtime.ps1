param(
    [string]$Package = "kcalc",
    [string]$Arch = "x86_64",
    [string]$Mirror = "https://geo.mirror.pkgbuild.com",
    [switch]$Refresh
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$Work = Join-Path $Root "tooling/downloads/arch-runtime-$Package-$Arch"
$DbDir = Join-Path $Work "db"
$PkgDir = Join-Path $Work "packages"
$RuntimeDir = Join-Path $Work "runtime-root"
$ManifestPath = Join-Path $Work "runtime-manifest.tsv"
$Repos = @("core", "extra")

New-Item -ItemType Directory -Force -Path $Work, $DbDir, $PkgDir, $RuntimeDir | Out-Null

function Normalize-DepName([string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Name)) { return "" }
    $n = $Name.Trim()
    $idx = $n.IndexOfAny([char[]]@('<','>','='))
    if ($idx -ge 0) { $n = $n.Substring(0, $idx) }
    return $n.Trim()
}

function Read-Desc([string]$Path, [string]$Repo) {
    $fields = @{}
    $current = $null
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line.StartsWith('%') -and $line.EndsWith('%')) {
            $current = $line.Trim('%')
            if (-not $fields.ContainsKey($current)) { $fields[$current] = New-Object System.Collections.Generic.List[string] }
            continue
        }
        if ($null -ne $current -and $line.Length -gt 0) {
            $fields[$current].Add($line)
        }
    }
    if (-not $fields.ContainsKey('NAME')) { return $null }
    [pscustomobject]@{
        Name = $fields['NAME'][0]
        Version = if ($fields.ContainsKey('VERSION')) { $fields['VERSION'][0] } else { "" }
        Repo = $Repo
        Filename = if ($fields.ContainsKey('FILENAME')) { $fields['FILENAME'][0] } else { "" }
        Depends = if ($fields.ContainsKey('DEPENDS')) { @($fields['DEPENDS']) } else { @() }
        Provides = if ($fields.ContainsKey('PROVIDES')) { @($fields['PROVIDES']) } else { @() }
    }
}

function Download-File([string]$Url, [string]$OutFile) {
    if ((Test-Path -LiteralPath $OutFile) -and -not $Refresh) { return }
    Write-Host "download $Url"
    & curl.exe -L --fail --retry 3 --retry-delay 2 -o $OutFile $Url
    if ($LASTEXITCODE -ne 0) { throw "curl failed for $Url" }
}

foreach ($repo in $Repos) {
    $dbFile = Join-Path $DbDir "$repo.db"
    $extractDir = Join-Path $DbDir $repo
    Download-File "$Mirror/$repo/os/$Arch/$repo.db" $dbFile
    if ((Test-Path -LiteralPath $extractDir) -and $Refresh) {
        Remove-Item -LiteralPath $extractDir -Recurse -Force
    }
    if (-not (Test-Path -LiteralPath $extractDir)) {
        New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
        & tar -xf $dbFile -C $extractDir
        if ($LASTEXITCODE -ne 0) { throw "tar failed for $dbFile" }
    }
}

$packages = @{}
$providers = @{}
foreach ($repo in $Repos) {
    $repoDir = Join-Path $DbDir $repo
    foreach ($desc in Get-ChildItem -LiteralPath $repoDir -Recurse -Filter desc) {
        $pkg = Read-Desc $desc.FullName $repo
        if ($null -eq $pkg) { continue }
        if (-not $packages.ContainsKey($pkg.Name)) { $packages[$pkg.Name] = $pkg }
        foreach ($provide in $pkg.Provides) {
            $p = Normalize-DepName $provide
            if ($p.Length -eq 0) { continue }
            if (-not $providers.ContainsKey($p)) { $providers[$p] = New-Object System.Collections.Generic.List[object] }
            $providers[$p].Add($pkg)
        }
    }
}

function Resolve-Package([string]$Dep) {
    $name = Normalize-DepName $Dep
    if ($packages.ContainsKey($name)) { return $packages[$name] }
    if ($providers.ContainsKey($name) -and $providers[$name].Count -gt 0) { return $providers[$name][0] }
    return $null
}

$queue = New-Object System.Collections.Generic.Queue[string]
$resolved = @{}
$missing = New-Object System.Collections.Generic.List[string]
$queue.Enqueue($Package)
while ($queue.Count -gt 0) {
    $dep = $queue.Dequeue()
    $pkg = Resolve-Package $dep
    if ($null -eq $pkg) {
        if (-not $missing.Contains($dep)) { $missing.Add($dep) }
        continue
    }
    if ($resolved.ContainsKey($pkg.Name)) { continue }
    $resolved[$pkg.Name] = $pkg
    foreach ($child in $pkg.Depends) { $queue.Enqueue($child) }
}

$ordered = $resolved.Values | Sort-Object Repo, Name
$ordered | ForEach-Object { "$($_.Name)`t$($_.Version)`t$($_.Repo)`t$($_.Filename)" } | Set-Content -LiteralPath $ManifestPath
if ($missing.Count -gt 0) {
    $missing | Sort-Object -Unique | Set-Content -LiteralPath (Join-Path $Work "missing-deps.txt")
}

foreach ($pkg in $ordered) {
    if ([string]::IsNullOrWhiteSpace($pkg.Filename)) { continue }
    $out = Join-Path $PkgDir $pkg.Filename
    Download-File "$Mirror/$($pkg.Repo)/os/$Arch/$($pkg.Filename)" $out
}

foreach ($pkgFile in Get-ChildItem -LiteralPath $PkgDir -File -Filter "*.pkg.tar.*") {
    Write-Host "extract $($pkgFile.Name)"
    & tar -xf $pkgFile.FullName -C $RuntimeDir
    if ($LASTEXITCODE -ne 0) {
        Write-Warning "tar returned $LASTEXITCODE for $($pkgFile.Name); continuing to keep partial extraction"
    }
}

$summary = [pscustomobject]@{
    Package = $Package
    Architecture = $Arch
    PackageCount = $ordered.Count
    MissingDependencyCount = $missing.Count
    WorkDir = $Work
    Manifest = $ManifestPath
    RuntimeRoot = $RuntimeDir
}
$summary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Work "summary.json")
$summary | Format-List
if ($missing.Count -gt 0) {
    Write-Warning "Missing dependencies: $($missing -join ', ')"
}