param(
    [ValidateSet("x86_64", "arm64-v8a")]
    [string]$AndroidAbi = "x86_64"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Image = "localhost/archphene-android-native:ndk29-rust1.88"
podman image exists $Image
if ($LASTEXITCODE -ne 0) {
    podman build -f (Join-Path $Root "containers/android-native.Containerfile") -t $Image $Root
    if ($LASTEXITCODE -ne 0) { throw "Android native build image failed" }
}
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
    podman run --rm -v "${Root}:/workspace" -w /workspace `
        -e "ANDROID_ABI=$AndroidAbi" -e KEYSTORE_PATH -e KEYSTORE_PASSWORD `
        -e KEY_ALIAS -e KEY_PASSWORD $Image `
        bash scripts/build-camera-capability-probe.sh
    if ($LASTEXITCODE -ne 0) { throw "Camera capability probe build failed" }
} finally {
    foreach ($entry in $previous.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "Process")
    }
}

$apk = Join-Path $Root "prototypes/camera-capability-probe/out-$AndroidAbi/archphene-camera-probe.apk"
Write-Host "Container-built camera capability probe: $apk"
