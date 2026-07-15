param(
    [switch]$SkipRuntime,
    [switch]$ReleaseBuild,
    [switch]$SkipGpuHelperBuild,
    [int]$VersionCode = 10000,
    [string]$VersionName = "1.0.0",
    [ValidateRange(1, 16)]
    [int]$Jobs = 2
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

function Invoke-Native([string]$Step, [scriptblock]$Command) {
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code $LASTEXITCODE"
    }
}

Invoke-Native "Podman availability check" { podman info --format "{{.Host.OS}}/{{.Host.Arch}}" }
& (Join-Path $PSScriptRoot "build-native-compositor-podman.ps1") -Architecture x86_64 -Release
if ($LASTEXITCODE -ne 0) { throw "Shared native compositor build failed" }
& (Join-Path $PSScriptRoot "build-terminal-pty-podman.ps1") -Architecture x86_64
if ($LASTEXITCODE -ne 0) { throw "Terminal PTY build failed" }
if (-not $SkipGpuHelperBuild) {
    & (Join-Path $PSScriptRoot "build-android-gpu-helper-podman.ps1") -Architecture x86_64
    if ($LASTEXITCODE -ne 0) { throw "Android GPU helper build failed" }
} elseif (-not (Test-Path -LiteralPath (Join-Path $Root "tooling/build/android-gpu/x86_64/virgl_test_server_android") -PathType Leaf)) {
    throw "Android GPU helper output is missing"
}

$drive = $Root.Substring(0, 1).ToLowerInvariant()
$rest = $Root.Substring(2).Replace("\", "/")
$LinuxRoot = "/mnt/$drive$rest"
if ($LinuxRoot.Contains("'")) {
    throw "Workspace paths containing a single quote are not supported"
}

if (-not $SkipRuntime) {
    $runtimeCommand = "cd '$LinuxRoot' && CONTAINER_CLI=podman SKIP_CHOWN=1 JOBS=$Jobs bash scripts/build-ci-package-runtime.sh"
    Invoke-Native "Linux package runtime build" { podman machine ssh $runtimeCommand }
}

if ($ReleaseBuild) {
    $credentialsPath = Join-Path $Root "tooling/signing/archphene-release-credentials.json"
    $keystorePath = Join-Path $Root "tooling/signing/archphene-release.keystore"
    if (-not (Test-Path -LiteralPath $credentialsPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath $keystorePath -PathType Leaf)) {
        throw "Release signing files are missing; run scripts/setup-github-release-signing.ps1"
    }
    $credentials = Get-Content -LiteralPath $credentialsPath -Raw | ConvertFrom-Json
    $containerKeystore = "/workspace/tooling/signing/archphene-release.keystore"
    $keystorePassword = $credentials.storePassword
    $keyAlias = $credentials.keyAlias
    $keyPassword = $credentials.keyPassword
} else {
    $keystorePath = Join-Path $Root "tooling/signing/archpheneos-manager-debug.keystore"
    if (-not (Test-Path -LiteralPath $keystorePath -PathType Leaf)) {
        throw "Manager debug keystore is missing: $keystorePath"
    }
    $containerKeystore = "/workspace/tooling/signing/archpheneos-manager-debug.keystore"
    $keystorePassword = "android"
    $keyAlias = "androiddebugkey"
    $keyPassword = "android"
}

$signing = @{
    KEYSTORE_PATH = $containerKeystore
    KEYSTORE_PASSWORD = $keystorePassword
    KEY_ALIAS = $keyAlias
    KEY_PASSWORD = $keyPassword
}
foreach ($entry in $signing.GetEnumerator()) {
    if ([string]::IsNullOrWhiteSpace([string]$entry.Value)) {
        throw "Signing value $($entry.Key) is missing"
    }
}

$previousEnvironment = @{}
try {
    foreach ($entry in $signing.GetEnumerator()) {
        $previousEnvironment[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, "Process")
        [Environment]::SetEnvironmentVariable($entry.Key, [string]$entry.Value, "Process")
    }
    $debuggable = if ($ReleaseBuild) { "false" } else { "true" }
    $containerArgs = @(
        "run", "--rm",
        "-v", "${Root}:/workspace",
        "-w", "/workspace",
        "-e", "VERSION_CODE=$VersionCode",
        "-e", "VERSION_NAME=$VersionName",
        "-e", "DEBUGGABLE=$debuggable",
        "-e", "KEYSTORE_PATH",
        "-e", "KEYSTORE_PASSWORD",
        "-e", "KEY_ALIAS",
        "-e", "KEY_PASSWORD",
        "ghcr.io/cirruslabs/android-sdk:36",
        "bash", "scripts/build-linux-manager-apk.sh"
    )
    Invoke-Native "Linux Android APK build" { podman @containerArgs }
} finally {
    foreach ($entry in $previousEnvironment.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
}

$Apk = Join-Path $Root "prototypes/linux-app-manager-stub/out-linux/archphene.apk"
Write-Host "Container-built APK: $Apk"