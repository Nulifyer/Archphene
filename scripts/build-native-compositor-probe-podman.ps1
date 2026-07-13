param(
    [ValidateSet("x86_64", "arm64-v8a")]
    [string]$AndroidAbi = "x86_64"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Architecture = if ($AndroidAbi -eq "arm64-v8a") { "aarch64" } else { "x86_64" }

& (Join-Path $PSScriptRoot "build-native-compositor-podman.ps1") -Architecture $Architecture -Release
if ($LASTEXITCODE -ne 0) { throw "Native compositor build failed" }

$signing = @{
    KEYSTORE_PATH = "/workspace/tooling/signing/archpheneos-manager-debug.keystore"
    KEYSTORE_PASSWORD = "android"
    KEY_ALIAS = "androiddebugkey"
    KEY_PASSWORD = "android"
}
$previous = @{}
try {
    foreach ($entry in $signing.GetEnumerator()) {
        $previous[$entry.Key] = [Environment]::GetEnvironmentVariable($entry.Key, "Process")
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
    $arguments = @(
        "run", "--rm", "-v", ($Root + ':/workspace'), "-w", "/workspace",
        "-e", "ANDROID_ABI=$AndroidAbi", "-e", "KEYSTORE_PATH",
        "-e", "KEYSTORE_PASSWORD", "-e", "KEY_ALIAS", "-e", "KEY_PASSWORD",
        "ghcr.io/cirruslabs/android-sdk:36",
        "bash", "scripts/build-native-compositor-probe.sh"
    )
    podman @arguments
    if ($LASTEXITCODE -ne 0) { throw "Native compositor probe APK build failed" }
} finally {
    foreach ($entry in $previous.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
}

$apk = Join-Path $Root "prototypes/native-compositor-probe/out-$AndroidAbi/archphene-compositor-probe.apk"
Write-Host "Container-built native compositor probe: $apk"
