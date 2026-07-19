param(
    [switch]$RebuildImage
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Image = "localhost/archphene-qt-platform-theme:qt6.11.1"
$Containerfile = Join-Path $Root "containers/qt-platform-theme.Containerfile"

podman image exists $Image
if ($RebuildImage -or $LASTEXITCODE -ne 0) {
    podman build -f $Containerfile -t $Image (Join-Path $Root "containers")
    if ($LASTEXITCODE -ne 0) { throw "GTK settings build image failed" }
}

$command = @'
set -euo pipefail
source=/workspace/native/archphene-gtk3-settings/archphene_gtk3_settings.c
arm_root=/workspace/tooling/downloads/arch-curated-kcalc-aarch64/runtime-root
mkdir -p /workspace/prebuilt/gtk3-compat/x86_64 /workspace/prebuilt/gtk3-compat/aarch64
gcc -shared -fPIC -O2 -Wall -Wextra -Werror $(pkg-config --cflags glib-2.0 gobject-2.0 gmodule-2.0) \
  -Wl,--allow-shlib-undefined -o /workspace/prebuilt/gtk3-compat/x86_64/libarchphene_gtk3_settings.so "$source"
aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -Wextra -Werror \
  -I"$arm_root/usr/include/glib-2.0" -I"$arm_root/usr/lib/glib-2.0/include" \
  -Wl,--allow-shlib-undefined -o /workspace/prebuilt/gtk3-compat/aarch64/libarchphene_gtk3_settings.so "$source"
strip --strip-unneeded /workspace/prebuilt/gtk3-compat/x86_64/libarchphene_gtk3_settings.so
aarch64-linux-gnu-strip --strip-unneeded /workspace/prebuilt/gtk3-compat/aarch64/libarchphene_gtk3_settings.so
readelf -h /workspace/prebuilt/gtk3-compat/x86_64/libarchphene_gtk3_settings.so | grep -F 'Advanced Micro Devices X86-64'
aarch64-linux-gnu-readelf -h /workspace/prebuilt/gtk3-compat/aarch64/libarchphene_gtk3_settings.so | grep -F 'AArch64'
'@
podman run --rm -v "${Root}:/workspace" -w /workspace $Image bash -lc $command
if ($LASTEXITCODE -ne 0) { throw "GTK settings module build failed" }

$Prebuilt = Join-Path $Root "prebuilt/gtk3-compat"
$X86Files = Get-ChildItem -LiteralPath (Join-Path $Prebuilt "x86_64") -Filter "*.so" |
    Sort-Object Name
$ArmFiles = Get-ChildItem -LiteralPath (Join-Path $Prebuilt "aarch64") -Filter "*.so" |
    Sort-Object Name
$ManifestPath = Join-Path $Prebuilt "manifest.json"
$Manifest = Get-Content -LiteralPath $ManifestPath -Raw | ConvertFrom-Json -AsHashtable
$Manifest.files = @($X86Files | ForEach-Object {
    [ordered]@{
        name = $_.Name
        bytes = $_.Length
        sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
})
$Manifest.additionalArchitectures[0].files = @($ArmFiles | ForEach-Object {
    [ordered]@{
        name = $_.Name
        bytes = $_.Length
        sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
})
$Manifest | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath $ManifestPath -Encoding utf8NoBOM
$Checksums = $X86Files | ForEach-Object {
    "{0}  x86_64/{1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $_.Name
}
$Checksums += $ArmFiles | ForEach-Object {
    "{0}  aarch64/{1}" -f (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant(), $_.Name
}
[IO.File]::WriteAllText((Join-Path $Prebuilt "SHA256SUMS"),
        (($Checksums -join "`n") + "`n"), [Text.UTF8Encoding]::new($false))
Write-Host "GTK 3 live-settings module built for x86_64 and AArch64."
