param(
    [string]$Serial = "emulator-5554",
    [string]$AndroidSdk = "",
    [string[]]$Packages = @(),
    [hashtable]$Labels = @{},
    [string]$OutputJson = "",
    [string]$OutputMarkdown = ""
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$SdkCandidate = if ($AndroidSdk) { $AndroidSdk }
    elseif (Test-Path -LiteralPath (Join-Path $Root "tooling/android-sdk")) {
        Join-Path $Root "tooling/android-sdk"
    } elseif ($env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT }
    elseif ($env:ANDROID_HOME) { $env:ANDROID_HOME }
    else { "" }
$Adb = if ($SdkCandidate -and (Test-Path -LiteralPath $SdkCandidate)) {
    Join-Path (Resolve-Path $SdkCandidate) "platform-tools/adb.exe"
} else {
    (Get-Command adb -ErrorAction Stop).Source
}

function Invoke-Adb([string[]]$Arguments, [switch]$AllowFailure) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb failed ($exitCode): $($output -join [Environment]::NewLine)"
    }
    return [pscustomobject]@{ Output = @($output); ExitCode = $exitCode }
}

$AdbIsRoot = (((Invoke-Adb @("shell", "id", "-u") -AllowFailure).Output -join "").Trim() -eq "0")

function Invoke-Private([string]$Package, [string[]]$Arguments) {
    $runAs = Invoke-Adb (@("shell", "run-as", $Package) + $Arguments) -AllowFailure
    if ($runAs.ExitCode -eq 0 -or -not $AdbIsRoot) { return $runAs }
    $root = "/data/user/0/$Package"
    $mapped = @($Arguments | ForEach-Object {
        $value = [string]$_
        if ($value -eq ".") {
            $root
        } elseif ($value -match '^(files|cache|code_cache)(/|$)') {
            "$root/$value"
        } else {
            $value
        }
    })
    return Invoke-Adb (@("shell") + $mapped) -AllowFailure
}

function Parse-KiB([object]$Result) {
    if ($Result.ExitCode -ne 0 -or $Result.Output.Count -eq 0) { return $null }
    $match = [regex]::Match([string]$Result.Output[0], '^\s*(\d+)\s+')
    if (-not $match.Success) { return $null }
    return [int64]$match.Groups[1].Value
}

function Get-CodeKiB([string]$Package) {
    $paths = @((Invoke-Adb @("shell", "pm", "path", $Package) -AllowFailure).Output |
        ForEach-Object { ([string]$_) -replace '^package:', '' } |
        Where-Object { $_ })
    if ($paths.Count -eq 0) { return $null }
    $directory = $paths[0].Substring(0, $paths[0].LastIndexOf('/'))
    return Parse-KiB (Invoke-Adb @("shell", "du", "-sk", $directory) -AllowFailure)
}

function Get-ApkBytes([string]$Package) {
    $total = [int64]0
    $paths = @((Invoke-Adb @("shell", "pm", "path", $Package) -AllowFailure).Output |
        ForEach-Object { ([string]$_) -replace '^package:', '' } |
        Where-Object { $_ })
    foreach ($path in $paths) {
        $result = Invoke-Adb @("shell", "stat", "-c", "%s", $path) -AllowFailure
        if ($result.ExitCode -ne 0 -or
                $result.Output.Count -eq 0 -or
                [string]$result.Output[0] -notmatch '^\d+$') {
            return $null
        }
        $total += [int64]$result.Output[0]
    }
    return $total
}

function Get-PrivateKiB([string]$Package, [string]$Path) {
    return Parse-KiB (Invoke-Private -Package $Package -Arguments @("du", "-sk", $Path))
}

function Format-MiB([Nullable[int64]]$KiB) {
    if ($null -eq $KiB) { return "n/a" }
    return ([double]$KiB / 1024).ToString("0.0", [Globalization.CultureInfo]::InvariantCulture)
}

Invoke-Adb @("get-state") | Out-Null
$installed = (Invoke-Adb @("shell", "pm", "list", "packages")).Output |
    ForEach-Object { ([string]$_) -replace '^package:', '' }
if ($Packages.Count -eq 0) {
    $Packages = @("org.archpheneos.manager", "org.archpheneos.terminal")
    $Packages += $installed | Where-Object {
        $_ -match '^org\.archphene\.linux\.p[0-9a-f]{32}$'
    }
    foreach ($legacy in @("org.archphene.linux.kcalc", "org.archphene.linux.mousepad")) {
        if ($installed -contains $legacy) { $Packages += $legacy }
    }
}
$Packages = @($Packages | Select-Object -Unique)

$runtimeSources = @{}
if ($installed -contains "org.archpheneos.manager" -and
        (Invoke-Private -Package "org.archpheneos.manager" -Arguments @("pwd")).ExitCode -eq 0) {
    $bindings = (Invoke-Private -Package "org.archpheneos.manager" -Arguments @("find", "files/runtime-packs/bindings", "-type", "f")).Output
    foreach ($binding in $bindings) {
        $bindingText = (Invoke-Private -Package "org.archpheneos.manager" -Arguments @("cat", [string]$binding)).Output -join "`n"
        $packageMatch = [regex]::Match($bindingText, '(?m)^package\t([^\r\n]+)$')
        $packMatch = [regex]::Match($bindingText, '(?m)^pack\t([0-9a-f]{64})$')
        if (-not $packageMatch.Success -or -not $packMatch.Success) { continue }
        $androidPackage = $packageMatch.Groups[1].Value
        $manifestResult = Invoke-Private -Package "org.archpheneos.manager" -Arguments @("cat",
                "files/runtime-packs/packs/$($packMatch.Groups[1].Value)/manifest.tsv")
        $manifest = $manifestResult.Output -join "`n"
        $source = [regex]::Match($manifest, '(?m)^source\t([^\r\n]+)$')
        if ($source.Success) { $runtimeSources[$androidPackage] = $source.Groups[1].Value }
    }
}

$rows = foreach ($package in $Packages) {
    if ($installed -notcontains $package) { continue }
    $dump = (Invoke-Adb @("shell", "dumpsys", "package", $package)).Output -join "`n"
    $versionCode = if ($dump -match 'versionCode=(\d+)') { [int64]$matches[1] } else { $null }
    $versionName = if ($dump -match 'versionName=([^\s]+)') { $matches[1] } else { "" }
    $abi = if ($dump -match 'primaryCpuAbi=([^\s]+)') { $matches[1] } else { "" }
    $dataAccessible = (Invoke-Private -Package $package -Arguments @("pwd")).ExitCode -eq 0
    $totalData = if ($dataAccessible) { Get-PrivateKiB -Package $package -Path "." } else { $null }
    $files = if ($dataAccessible) { Get-PrivateKiB -Package $package -Path "files" } else { $null }
    $cache = if ($dataAccessible) { Get-PrivateKiB -Package $package -Path "cache" } else { $null }
    $codeCache = if ($dataAccessible) { Get-PrivateKiB -Package $package -Path "code_cache" } else { $null }
    $kind = if ($package -eq "org.archpheneos.manager") { "manager" }
        elseif ($package -eq "org.archpheneos.terminal") { "terminal" }
        else { "generated-wrapper" }
    $sourcePackage = if ($runtimeSources.ContainsKey($package)) {
        [string]$runtimeSources[$package]
    } else { "" }
    $label = if ($Labels.ContainsKey($package)) { [string]$Labels[$package] }
        elseif ($package -eq "org.archpheneos.manager") { "Archphene" }
        elseif ($package -eq "org.archpheneos.terminal") { "Archphene Terminal" }
        elseif ($sourcePackage) {
            (Get-Culture).TextInfo.ToTitleCase($sourcePackage.Replace('-', ' '))
        } else { $package }
    $persistentData = if ($null -eq $totalData) { $null } else {
        [Math]::Max(0, $totalData - [int64]($cache ?? 0) - [int64]($codeCache ?? 0))
    }
    [pscustomobject]@{
        Label = $label
        Package = $package
        Kind = $kind
        SourcePackage = $sourcePackage
        VersionName = $versionName
        VersionCode = $versionCode
        Abi = $abi
        ApkBytes = Get-ApkBytes $package
        CodeKiB = Get-CodeKiB $package
        DataKiB = $totalData
        PersistentDataKiB = $persistentData
        FilesKiB = $files
        CacheKiB = $cache
        CodeCacheKiB = $codeCache
        DataAccessible = $dataAccessible
    }
}

$managerBreakdown = [ordered]@{}
if ($installed -contains "org.archpheneos.manager" -and
        (Invoke-Private -Package "org.archpheneos.manager" -Arguments @("pwd")).ExitCode -eq 0) {
    foreach ($entry in ([ordered]@{
        RuntimeTotal = "files/runtime-packs"
        RuntimeBlobs = "files/runtime-packs/blobs"
        PackMetadata = "files/runtime-packs/packs"
        RuntimeBindings = "files/runtime-packs/bindings"
        PackageRuntime = "files/package-runtime"
        PackageDownloads = "files/package-runtime/downloads"
        TransactionStaging = "files/package-runtime/staging"
        ManagerNativeLinks = "files/package-runtime/manager-native"
    }).GetEnumerator()) {
        $managerBreakdown[$entry.Key] = Get-PrivateKiB -Package "org.archpheneos.manager" -Path $entry.Value
    }
}

$report = [pscustomobject]@{
    Schema = "org.archphene.storage-report.v1"
    CapturedAtUtc = [DateTime]::UtcNow.ToString("o")
    Serial = $Serial
    Manufacturer = ((Invoke-Adb @("shell", "getprop", "ro.product.manufacturer")).Output -join "").Trim()
    Model = ((Invoke-Adb @("shell", "getprop", "ro.product.model")).Output -join "").Trim()
    AndroidRelease = ((Invoke-Adb @("shell", "getprop", "ro.build.version.release")).Output -join "").Trim()
    DeviceAbi = ((Invoke-Adb @("shell", "getprop", "ro.product.cpu.abi")).Output -join "").Trim()
    PageSize = [int64](((Invoke-Adb @("shell", "getconf", "PAGESIZE")).Output -join "").Trim())
    Packages = @($rows)
    ManagerBreakdownKiB = [pscustomobject]$managerBreakdown
}

if ($OutputJson) {
    $target = [IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputJson))
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
    [IO.File]::WriteAllText($target, ($report | ConvertTo-Json -Depth 6),
            [Text.UTF8Encoding]::new($false))
}
if ($OutputMarkdown) {
    $target = [IO.Path]::GetFullPath((Join-Path (Get-Location) $OutputMarkdown))
    [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($target)) | Out-Null
    $lines = [Collections.Generic.List[string]]::new()
    $lines.Add("# Archphene Android storage report")
    $lines.Add("")
    $lines.Add("Captured: $($report.CapturedAtUtc)")
    $lines.Add("")
    $lines.Add("Device: $($report.Manufacturer) $($report.Model); Android $($report.AndroidRelease); $($report.DeviceAbi); $($report.PageSize)-byte pages")
    $lines.Add("")
    $lines.Add("This is a device snapshot. Publish release costs only from a documented clean install and workload.")
    $lines.Add("")
    $lines.Add("| Component | Kind | Version | APK MiB | Installed code MiB | Persistent data MiB | Runtime cache MiB |")
    $lines.Add("| --- | --- | --- | ---: | ---: | ---: | ---: |")
    foreach ($row in $rows) {
        $apkMiB = if ($null -eq $row.ApkBytes) { "n/a" } else {
            ([double]$row.ApkBytes / 1MB).ToString("0.0", [Globalization.CultureInfo]::InvariantCulture)
        }
        $lines.Add("| $($row.Label) | $($row.Kind) | $($row.VersionName) | $apkMiB | $(Format-MiB $row.CodeKiB) | $(Format-MiB $row.PersistentDataKiB) | $(Format-MiB $row.CacheKiB) |")
    }
    if ($managerBreakdown.Count -gt 0) {
        $lines.Add("")
        $lines.Add("## Manager private data")
        $lines.Add("")
        $lines.Add("| Category | MiB |")
        $lines.Add("| --- | ---: |")
        foreach ($entry in $managerBreakdown.GetEnumerator()) {
            $lines.Add("| $($entry.Key) | $(Format-MiB $entry.Value) |")
        }
    }
    [IO.File]::WriteAllLines($target, $lines, [Text.UTF8Encoding]::new($false))
}

$rows | Select-Object Label, Kind, VersionName,
    @{Name="ApkMiB"; Expression={ if ($null -eq $_.ApkBytes) { $null } else { [math]::Round($_.ApkBytes / 1MB, 1) } }},
    @{Name="CodeMiB"; Expression={ if ($null -eq $_.CodeKiB) { $null } else { [math]::Round($_.CodeKiB / 1024, 1) } }},
    @{Name="PersistentMiB"; Expression={ if ($null -eq $_.PersistentDataKiB) { $null } else { [math]::Round($_.PersistentDataKiB / 1024, 1) } }},
    @{Name="CacheMiB"; Expression={ if ($null -eq $_.CacheKiB) { $null } else { [math]::Round($_.CacheKiB / 1024, 1) } }} |
    Format-Table -AutoSize
