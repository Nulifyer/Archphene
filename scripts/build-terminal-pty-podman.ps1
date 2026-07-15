param(
    [ValidateSet("x86_64", "aarch64")]
    [string]$Architecture = "x86_64"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Image = "localhost/archphene-android-native:ndk29-rust1.88"
$Target = if ($Architecture -eq "aarch64") {
    "aarch64-linux-android"
} else {
    "x86_64-linux-android"
}
$Output = "native/archphene-terminal/out/$Architecture/libtermux.so"
$Command = "mkdir -p 'native/archphene-terminal/out/$Architecture' && " +
    "`$ANDROID_SDK_ROOT/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/${Target}29-clang " +
    "-shared -fPIC -O2 -Wall -Wextra -Werror -Wl,-z,relro,-z,now " +
    "-o '$Output' native/archphene-terminal/terminal_pty.c"

podman run --rm -v "${Root}:/workspace" -w /workspace $Image bash -lc $Command
if ($LASTEXITCODE -ne 0) { throw "Terminal PTY native build failed" }
$Library = Join-Path $Root $Output
if (-not (Test-Path -LiteralPath $Library -PathType Leaf)) {
    throw "Terminal PTY library missing: $Library"
}
Write-Host "Terminal PTY library: $Library"