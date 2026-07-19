param(
    [string]$Serial = "emulator-5556",
    [string]$Package = "org.archpheneos.manager",
    [string]$RuntimeDirectory = "",
    [string]$Probe = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
if (-not $RuntimeDirectory) {
    $RuntimeDirectory = Join-Path $Root "tooling/build/glibc-archphene-runtime-x86_64"
}
if (-not $Probe) {
    $Probe = Join-Path $Root "tooling/build/16kb-probe/runtime-probe-dynamic"
}
$RuntimeDirectory = (Resolve-Path $RuntimeDirectory).Path
$Probe = (Resolve-Path $Probe).Path
$Remote = "/data/local/tmp/archphene-ps16k-runtime"

function Invoke-Adb {
    $output = & $Adb -s $Serial @args 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($args -join ' ')`n$($output -join "`n")"
    }
    return $output
}

$pageSize = ((Invoke-Adb shell getconf PAGE_SIZE) -join "").Trim()
if ($pageSize -ne "16384") {
    throw "$Serial reports page size $pageSize, expected 16384"
}
$dataPath = ((Invoke-Adb shell run-as $Package pwd) -join "").Trim()
if (-not $dataPath.StartsWith("/data/")) {
    throw "$Package is not installed as a debuggable app on $Serial"
}
$Target = "$dataPath/files/ps16k-runtime"

try {
    Invoke-Adb shell rm -rf $Remote | Out-Null
    Invoke-Adb shell mkdir -p $Remote | Out-Null
    foreach ($file in Get-ChildItem $RuntimeDirectory -File) {
        if ($file.Name -in @("SHA256SUMS", "source-commit.txt")) { continue }
        Invoke-Adb push $file.FullName "$Remote/$($file.Name)" | Out-Null
    }
    Invoke-Adb push $Probe "$Remote/runtime-probe-dynamic" | Out-Null
    Invoke-Adb shell chmod -R 755 $Remote | Out-Null

    Invoke-Adb shell run-as $Package rm -rf $Target | Out-Null
    Invoke-Adb shell run-as $Package mkdir -p $Target | Out-Null
    $runtimeFiles = Get-ChildItem $RuntimeDirectory -File |
        Where-Object Name -NotIn @("SHA256SUMS", "source-commit.txt")
    foreach ($file in @($runtimeFiles) + @(Get-Item $Probe)) {
        $name = if ($file.FullName -eq $Probe) { "runtime-probe-dynamic" } else { $file.Name }
        Invoke-Adb shell run-as $Package cp "$Remote/$name" "$Target/$name" | Out-Null
        Invoke-Adb shell run-as $Package chmod 700 "$Target/$name" | Out-Null
    }
    $result = (Invoke-Adb shell run-as $Package "$Target/ld-linux-x86-64.so.2" --library-path $Target "$Target/runtime-probe-dynamic") -join "`n"
    if ($result.Trim() -ne "hello from shared glibc closure") {
        throw "Unexpected glibc probe output: $result"
    }
} finally {
    Invoke-Adb shell run-as $Package rm -rf $Target | Out-Null
    Invoke-Adb shell rm -rf $Remote | Out-Null
}

Write-Host "16 KB x86_64 glibc runtime passed inside $Package on $Serial."
