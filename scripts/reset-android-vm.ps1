param(
    [string]$AvdName = "ArchpheneOS_x86_64_api36",
    [string]$SystemImage = "system-images;android-36;google_apis;x86_64",
    [string]$Device = "pixel_8",
    [switch]$NoLaunch
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Sdk = Join-Path $Root "tooling/android-sdk"
$Adb = Join-Path $Sdk "platform-tools/adb.exe"
$AvdRoot = [IO.Path]::GetFullPath((Join-Path $Root "tooling/avd"))
$AvdDir = [IO.Path]::GetFullPath((Join-Path $AvdRoot "$AvdName.avd"))
$AvdIni = [IO.Path]::GetFullPath((Join-Path $AvdRoot "$AvdName.ini"))
$EmulatorHome = [IO.Path]::GetFullPath((Join-Path $Root "tooling/emulator-home"))
foreach ($Path in @($AvdDir, $AvdIni, $EmulatorHome)) {
    if (-not $Path.StartsWith(
            $Root + [IO.Path]::DirectorySeparatorChar,
            [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to remove path outside workspace: $Path"
    }
}

& $Adb -s emulator-5554 emu kill 2>$null | Out-Null
Start-Sleep -Seconds 3
Get-Process emulator,qemu-system-x86_64 -ErrorAction SilentlyContinue | Stop-Process -Force
if (Test-Path -LiteralPath $AvdDir) { Remove-Item -LiteralPath $AvdDir -Recurse -Force }
if (Test-Path -LiteralPath $AvdIni) { Remove-Item -LiteralPath $AvdIni -Force }
if (Test-Path -LiteralPath $EmulatorHome) {
    Remove-Item -LiteralPath $EmulatorHome -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $AvdRoot,$EmulatorHome | Out-Null

$env:ANDROID_SDK_ROOT = $Sdk
$env:ANDROID_AVD_HOME = $AvdRoot
$env:ANDROID_EMULATOR_HOME = $EmulatorHome
$env:ANDROID_PREFS_ROOT = $EmulatorHome
$AvdManager = Join-Path $Sdk "cmdline-tools/latest/bin/avdmanager.bat"
"no" | & $AvdManager create avd --force --name $AvdName --package $SystemImage --device $Device --path $AvdDir
if ($LASTEXITCODE -ne 0) { throw "avdmanager failed with exit $LASTEXITCODE" }

$Config = Join-Path $AvdDir "config.ini"
$Text = [IO.File]::ReadAllText($Config)
$Overrides = [ordered]@{
    "disk.dataPartition.size" = "16G"
    "fastboot.forceColdBoot" = "no"
    "fastboot.forceFastBoot" = "yes"
    "firstboot.saveToLocalSnapshot" = "no"
    "hw.cpu.ncore" = "6"
    "hw.gpu.enabled" = "yes"
    "hw.gpu.mode" = "host"
    "hw.keyboard" = "no"
    "hw.ramSize" = "8192"
    "showDeviceFrame" = "yes"
}
foreach ($Pair in $Overrides.GetEnumerator()) {
    $Pattern = "(?m)^" + [regex]::Escape($Pair.Key) + "=.*$"
    $Line = $Pair.Key + "=" + $Pair.Value
    if ([regex]::IsMatch($Text, $Pattern)) {
        $Text = [regex]::Replace($Text, $Pattern, $Line)
    } else {
        $Text += [Environment]::NewLine + $Line
    }
}
[IO.File]::WriteAllText($Config, $Text)
Write-Output "Recreated AVD at $AvdDir"
if (-not $NoLaunch) { & (Join-Path $PSScriptRoot "start-android-vm.ps1") -AvdName $AvdName }
