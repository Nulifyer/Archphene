param(
    [string]$Classification = "prototypes/kcalc-bridge-test/kcalc.runtime-classification.json",
    [string]$Arch = "x86_64",
    [string]$Mirror = "https://geo.mirror.pkgbuild.com",
    [switch]$Refresh
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$ClassPath = Resolve-Path (Join-Path $Root $Classification)
$Class = Get-Content -Raw -LiteralPath $ClassPath | ConvertFrom-Json
$Package = $Class.linuxPackage
$Work = Join-Path $Root "tooling/downloads/arch-curated-$Package-$Arch"
$DbDir = Join-Path $Work "db"
$PkgDir = Join-Path $Work "packages"
$RuntimeDir = Join-Path $Work "runtime-root"
$ManifestPath = Join-Path $Work "curated-manifest.tsv"
$Repos = @("core", "extra")

New-Item -ItemType Directory -Force -Path $Work, $DbDir, $PkgDir, $RuntimeDir | Out-Null

function Read-Desc([string]$Path, [string]$Repo) {
    $fields = @{}
    $current = $null
    foreach ($line in Get-Content -LiteralPath $Path) {
        if ($line.StartsWith('%') -and $line.EndsWith('%')) {
            $current = $line.Trim('%')
            if (-not $fields.ContainsKey($current)) { $fields[$current] = New-Object System.Collections.Generic.List[string] }
            continue
        }
        if ($null -ne $current -and $line.Length -gt 0) { $fields[$current].Add($line) }
    }
    if (-not $fields.ContainsKey('NAME')) { return $null }
    [pscustomobject]@{
        Name = $fields['NAME'][0]
        Version = if ($fields.ContainsKey('VERSION')) { $fields['VERSION'][0] } else { "" }
        Repo = $Repo
        Filename = if ($fields.ContainsKey('FILENAME')) { $fields['FILENAME'][0] } else { "" }
        Depends = if ($fields.ContainsKey('DEPENDS')) { @($fields['DEPENDS']) } else { @() }
    }
}

function Download-File([string]$Url, [string]$OutFile) {
    if ((Test-Path -LiteralPath $OutFile) -and -not $Refresh) { return }
    Write-Host "download $Url"
    & curl.exe -L --fail --retry 3 --retry-delay 2 -o $OutFile $Url
    if ($LASTEXITCODE -ne 0) { throw "curl failed for $Url" }
}

function Get-LocalPackageFileName([string]$PackageFileName) {
    # Arch epoch separators are ':'; Windows treats ':' as an alternate stream separator.
    return $PackageFileName.Replace(':', '_')
}

foreach ($repo in $Repos) {
    $dbFile = Join-Path $DbDir "$repo.db"
    $extractDir = Join-Path $DbDir $repo
    Download-File "$Mirror/$repo/os/$Arch/$repo.db" $dbFile
    if ((Test-Path -LiteralPath $extractDir) -and $Refresh) { Remove-Item -LiteralPath $extractDir -Recurse -Force }
    if (-not (Test-Path -LiteralPath $extractDir)) {
        New-Item -ItemType Directory -Force -Path $extractDir | Out-Null
        & tar -xf $dbFile -C $extractDir
        if ($LASTEXITCODE -ne 0) { throw "tar failed for $dbFile" }
    }
}

function Find-Package([string]$Name) {
    foreach ($repo in $Repos) {
        $repoDir = Join-Path $DbDir $repo
        foreach ($candidate in Get-ChildItem -LiteralPath $repoDir -Directory -Filter "$Name-*") {
            $desc = Join-Path $candidate.FullName "desc"
            if (-not (Test-Path -LiteralPath $desc)) { continue }
            $pkg = Read-Desc $desc $repo
            if ($null -ne $pkg -and $pkg.Name -eq $Name) { return $pkg }
        }
    }
    return $null
}

$wanted = New-Object System.Collections.Generic.List[string]
$wanted.Add($Class.linuxPackage)
foreach ($name in $Class.shipRequired) { $wanted.Add([string]$name) }
foreach ($asset in $Class.shipAssets) {
    if ($asset -match '^breeze-icons') { $wanted.Add('breeze-icons') }
}
$wanted = $wanted | Sort-Object -Unique

$missing = New-Object System.Collections.Generic.List[string]
$selected = New-Object System.Collections.Generic.List[object]
foreach ($name in $wanted) {
    $pkg = Find-Package $name
    if ($null -ne $pkg) { $selected.Add($pkg) } else { $missing.Add($name) }
}

$selected | Sort-Object Repo,Name | ForEach-Object { "$($_.Name)`t$($_.Version)`t$($_.Repo)`t$($_.Filename)`t$($_.Depends -join ', ')" } | Set-Content -LiteralPath $ManifestPath
if ($missing.Count -gt 0) { $missing | Sort-Object -Unique | Set-Content -LiteralPath (Join-Path $Work "missing-curated.txt") }

foreach ($pkg in ($selected | Sort-Object Repo,Name)) {
    if ([string]::IsNullOrWhiteSpace($pkg.Filename)) { continue }
    Download-File "$Mirror/$($pkg.Repo)/os/$Arch/$($pkg.Filename)" (Join-Path $PkgDir (Get-LocalPackageFileName $pkg.Filename))
}

if ((Test-Path -LiteralPath $RuntimeDir) -and $Refresh) {
    Remove-Item -LiteralPath $RuntimeDir -Recurse -Force
    New-Item -ItemType Directory -Force -Path $RuntimeDir | Out-Null
}
foreach ($pkgFile in Get-ChildItem -LiteralPath $PkgDir -File -Filter "*.pkg.tar.*") {
    Write-Host "extract $($pkgFile.Name)"
    & tar -xf $pkgFile.FullName -C $RuntimeDir
    if ($LASTEXITCODE -ne 0) { Write-Warning "tar returned $LASTEXITCODE for $($pkgFile.Name); continuing" }
}

[pscustomobject]@{
    Package = $Package
    Architecture = $Arch
    SelectedPackageCount = $selected.Count
    MissingPackageCount = $missing.Count
    WorkDir = $Work
    Manifest = $ManifestPath
    RuntimeRoot = $RuntimeDir
} | Tee-Object -Variable Summary | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $Work "summary.json")
$Summary | Format-List
if ($missing.Count -gt 0) { Write-Warning "Missing curated packages: $($missing -join ', ')" }
