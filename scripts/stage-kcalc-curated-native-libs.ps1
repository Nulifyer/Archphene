param(
    [string]$RuntimeRoot = "tooling/downloads/arch-curated-kcalc-x86_64/runtime-root",
    [string]$ResolvedFile = "tooling/downloads/arch-curated-kcalc-x86_64/elf-needed-resolved.tsv",
    [string]$AppLibDir = "prototypes/kcalc-android-app/lib/x86_64",
    [string]$LoaderPath = "",
    [string]$CompatLibcPath = ""
)

$ErrorActionPreference = "Stop"
$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$RuntimeRoot = (Resolve-Path (Join-Path $Root $RuntimeRoot)).Path
$ResolvedFile = (Resolve-Path (Join-Path $Root $ResolvedFile)).Path
$AppLibDir = Join-Path $Root $AppLibDir
New-Item -ItemType Directory -Force -Path $AppLibDir | Out-Null

$preserve = @(
    "libarchphene_wayland_jni.so",
    "libarchphene_wayland_socket_probe.so",
    "libarchphene_frame_client.so",
    "libarchphene_shm_frame_client.so",
    "libarchphene_wayland_shm_client.so",
    "libarchphene_wayland_evented_client.so",
    "libarchphene_wayland_xdg_client.so",
    "libarchphene_wayland_api_client.so",
    "libarchphene_wayland_client_android.so",
    "libarchphene_wayland_android_api_client.so",
    "libarchphene_wayland_android_api_render_client.so",
    "libarchphene_wayland_android_api_xdg_client.so"
)

$copied = 0
$totalBytes = 0L
foreach ($line in Get-Content -LiteralPath $ResolvedFile) {
    if ([string]::IsNullOrWhiteSpace($line)) { continue }
    $parts = $line -split "`t", 2
    if ($parts.Count -ne 2) { continue }
    $name = $parts[0]
    $relative = $parts[1]
    $source = Join-Path $RuntimeRoot $relative
    if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw "resolved source missing: $source" }
    Copy-Item -LiteralPath $source -Destination (Join-Path $AppLibDir $name) -Force
    $copied++
    $totalBytes += (Get-Item -LiteralPath $source).Length
}

$loader = if ($LoaderPath) { (Resolve-Path (Join-Path $Root $LoaderPath)).Path } else {
    Join-Path $RuntimeRoot "usr/lib/ld-linux-x86-64.so.2"
}
if (-not (Test-Path -LiteralPath $loader -PathType Leaf)) { throw "loader missing: $loader" }
Copy-Item -LiteralPath $loader -Destination (Join-Path $AppLibDir "libld.so.2") -Force
Copy-Item -LiteralPath $loader -Destination (Join-Path $AppLibDir "libarchphene_ld.so") -Force
if ($CompatLibcPath) {
    $compatLibc = (Resolve-Path (Join-Path $Root $CompatLibcPath)).Path
    Copy-Item -LiteralPath $compatLibc -Destination (Join-Path $AppLibDir "libc.so.6") -Force
}

$kcalc = Join-Path $RuntimeRoot "usr/bin/kcalc"
if (-not (Test-Path -LiteralPath $kcalc -PathType Leaf)) { throw "kcalc missing: $kcalc" }
Copy-Item -LiteralPath $kcalc -Destination (Join-Path $AppLibDir "libarchphene_kcalc.so") -Force

$waylandPlatform = Join-Path $RuntimeRoot "usr/lib/qt6/plugins/platforms/libqwayland.so"
if (-not (Test-Path -LiteralPath $waylandPlatform -PathType Leaf)) { throw "Qt Wayland platform plugin missing: $waylandPlatform" }
Copy-Item -LiteralPath $waylandPlatform -Destination (Join-Path $AppLibDir "libqwayland.so") -Force

$shellIntegration = Join-Path $RuntimeRoot "usr/lib/qt6/plugins/wayland-shell-integration/libxdg-shell.so"
if (-not (Test-Path -LiteralPath $shellIntegration -PathType Leaf)) { throw "Qt xdg-shell integration plugin missing: $shellIntegration" }
Copy-Item -LiteralPath $shellIntegration -Destination (Join-Path $AppLibDir "libarchphene_xdg_shell.so") -Force

foreach ($name in $preserve) {
    $path = Join-Path $AppLibDir $name
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Write-Warning "expected preserved bridge library is missing: $name"
    }
}

[pscustomobject]@{
    AppLibDir = $AppLibDir
    CopiedResolvedLibraries = $copied
    CopiedResolvedMegabytes = [math]::Round($totalBytes / 1MB, 1)
    LoaderAlias = "libld.so.2, libarchphene_ld.so"
    PayloadAlias = "libarchphene_kcalc.so, libqwayland.so"
    NativeFiles = (Get-ChildItem -LiteralPath $AppLibDir -File).Count
} | Format-List





