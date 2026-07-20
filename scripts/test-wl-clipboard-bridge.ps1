param(
    [string]$Serial = "emulator-5554",
    [string]$Manager = "org.archpheneos.manager",
    [string]$Package = "org.archphene.linux.p97eb2a60fdffcfe66758935b730cb3f1",
    [string]$Activity = "org.archphene.linux.kcalc.MainActivity"
)

$ErrorActionPreference = "Stop"

function Invoke-Adb {
    $commandArguments = @($args)
    $output = & adb -s $Serial @commandArguments 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) {
        throw "adb $($commandArguments -join ' ') failed: $output"
    }
    return $output
}

function Runtime-Log {
    return Invoke-Adb logcat -d -v brief -s ArchpheneRuntime ArchpheneLinuxApp `
        ArchpheneInput AndroidRuntime DEBUG linker '*:S'
}

function Wait-ForLog([string]$Pattern, [int]$Seconds = 15) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    do {
        $log = Runtime-Log
        if ($log -match $Pattern) { return $log }
        Start-Sleep -Milliseconds 300
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for log pattern: $Pattern`n$log"
}

function Set-WrapperCommand([string]$Executable) {
    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am force-stop $Manager | Out-Null
    Invoke-Adb shell am start -W -n "$Manager/.MainActivity" `
        --es archphene_test_assemble_qt wl-clipboard `
        --ez archphene_test_stage_transaction true `
        --ez archphene_test_wayland_candidate true `
        --es archphene_test_wayland_executable $Executable `
        --ez archphene_test_install_assembled true | Out-Null

    $deadline = (Get-Date).AddSeconds(90)
    $xml = ""
    do {
        Start-Sleep -Milliseconds 500
        Invoke-Adb shell uiautomator dump /sdcard/archphene-window.xml | Out-Null
        $xml = Invoke-Adb shell cat /sdcard/archphene-window.xml
    } while ($xml -notmatch 'text="(?:Install|Update)"' -and (Get-Date) -lt $deadline)

    if ($xml -notmatch '<node[^>]*text="(?:Install|Update)"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
        throw "Package installer confirmation was not shown for $Executable"
    }
    $x = [int](([int]$matches[1] + [int]$matches[3]) / 2)
    $y = [int](([int]$matches[2] + [int]$matches[4]) / 2)
    Invoke-Adb shell input tap $x $y | Out-Null
    $activation = Wait-ForLog 'activated generated wrapper' 20
    if ($activation -notmatch [regex]::Escape($Package)) {
        throw "Unexpected wrapper was activated for $Executable"
    }
}

function Start-Wrapper([string[]]$ExtraArguments) {
    Invoke-Adb logcat -c | Out-Null
    Invoke-Adb shell am force-stop $Package | Out-Null
    Invoke-Adb shell am start -W -n "$Package/$Activity" @ExtraArguments | Out-Null
}

function Assert-CleanRuntime([string]$Log) {
    if ($Log -match 'CANNOT LINK|SIGSEGV|Permission denied|exec cat|Could not launch') {
        throw "Runtime failure detected:`n$Log"
    }
}

$androidText = "archphene-android-to-linux-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
Set-WrapperCommand wl-paste
Start-Wrapper @('--es', 'archphene_test_android_clipboard', $androidText)
$pasteLog = Wait-ForLog 'Runtime GUI exit=0'
Assert-CleanRuntime $pasteLog
if (($pasteLog -notmatch [regex]::Escape($androidText)) -or
        ($pasteLog -notmatch 'Clipboard Android content reads=1')) {
    throw "Android-to-Linux clipboard assertion failed:`n$pasteLog"
}

$linuxText = "archphene-linux-to-android-$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
Set-WrapperCommand wl-copy
Start-Wrapper @('--esa', 'archphene_test_runtime_args', $linuxText,
        '--ez', 'archphene.wayland_debug', 'true')
Start-Sleep -Seconds 5
$copyLog = Runtime-Log
if ($copyLog -notmatch 'Runtime GUI exit=0') {
    Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
    $copyLog = Wait-ForLog 'Runtime GUI exit=0'
}
Assert-CleanRuntime $copyLog
if (($copyLog -notmatch 'wl_data_device#\d+\.set_selection\(wl_data_source') -or
        ($copyLog -notmatch 'wl_data_source#\d+\.send\(') -or
        ($copyLog -notmatch 'Clipboard Android content reads=0')) {
    throw "Linux clipboard source protocol assertion failed:`n$copyLog"
}

Set-WrapperCommand wl-paste
Start-Wrapper @()
$verifyLog = Wait-ForLog 'Runtime GUI exit=0'
Assert-CleanRuntime $verifyLog
if (($verifyLog -notmatch [regex]::Escape($linuxText)) -or
        ($verifyLog -notmatch 'Clipboard Android content reads=1')) {
    throw "Linux-to-Android clipboard assertion failed:`n$verifyLog"
}

Write-Host "wl-clipboard bridge passed on $Serial"
Write-Host "Android-to-Linux, Linux-to-Android, focus serials, and lazy reads verified"