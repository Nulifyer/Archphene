param(
    [switch]$RebuildImage,
    [ValidateRange(1, 16)]
    [int]$Jobs = 2
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Image = "localhost/archphene-qt-platform-theme:qt6.11.1"
$ExpectedQtVersion = "6.11.1"

podman image exists $Image
$ImageExists = $LASTEXITCODE -eq 0
if ($RebuildImage -or -not $ImageExists) {
    podman build -f (Join-Path $Root "containers/qt-platform-theme.Containerfile") `
        -t $Image (Join-Path $Root "containers")
    if ($LASTEXITCODE -ne 0) { throw "Qt platform-theme build image failed" }
}

$QtVersion = (& podman run --rm $Image pkg-config --modversion Qt6Core).Trim()
if ($LASTEXITCODE -ne 0 -or $QtVersion -ne $ExpectedQtVersion) {
    throw "Qt private ABI mismatch: expected $ExpectedQtVersion, got $QtVersion"
}

$command = @"
set -euo pipefail
rm -rf /tmp/archphene-qt-platform-theme
mkdir -p /tmp/archphene-qt-platform-theme/platform /tmp/archphene-qt-platform-theme/style
cd /tmp/archphene-qt-platform-theme/platform
qmake6 /workspace/native/archphene-qt-platform-theme/archphene-qt-platform-theme.pro
make -j$Jobs
install -Dm755 libarchphene_qt_platform_theme.so /workspace/prebuilt/qt-bridge/x86_64/libarchphene_qt_platform_theme.so
cd /tmp/archphene-qt-platform-theme/style
qmake6 /workspace/native/archphene-qt-platform-theme/archphene-qt-style.pro
make -j$Jobs
install -Dm755 libarchphene_qt_style.so /workspace/prebuilt/qt-bridge/x86_64/libarchphene_qt_style.so
"@
podman run --rm -v "${Root}:/workspace" -w /workspace $Image bash -lc $command
if ($LASTEXITCODE -ne 0) { throw "Qt platform-theme plugin build failed" }

$Prebuilt = Join-Path $Root "prebuilt/qt-bridge"
$Files = Get-ChildItem -LiteralPath (Join-Path $Prebuilt "x86_64") -Filter "*.so" |
    Sort-Object Name
$Manifest = [ordered]@{
    schema = "org.archphene.prebuilt-bridge.v1"
    architecture = "x86_64"
    qtVersion = $ExpectedQtVersion
    purpose = "Qt Wayland Android bridge template"
    files = @($Files | ForEach-Object {
        [ordered]@{
            name = $_.Name
            bytes = $_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    })
}
$Manifest | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath `
    (Join-Path $Prebuilt "manifest.json") -Encoding utf8NoBOM
$Checksums = $Files | ForEach-Object {
    "{0}  x86_64/{1}" -f `
        (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $_.Name
}
$Checksums | Set-Content -LiteralPath (Join-Path $Prebuilt "SHA256SUMS") -Encoding utf8NoBOM

Write-Host "Qt $QtVersion appearance plugins: prebuilt/qt-bridge/x86_64/libarchphene_qt_{platform_theme,style}.so"