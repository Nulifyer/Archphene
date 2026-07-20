param(
    [string]$Serial = "RFCT90AEEFA",
    [string]$Package = "org.archphene.linux.pade746c2b5b03d64e570dc2d1e5ee546",
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

function Wait-ForLog([string]$Pattern, [int]$Seconds = 12) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $log = Invoke-Adb logcat -d -v brief -s ArchpheneInput ArchpheneRuntime '*:S'
        if ($log -match $Pattern) { return $log }
        Start-Sleep -Milliseconds 250
    }
    throw "Timed out waiting for log pattern: $Pattern"
}

$component = "$Package/$Activity"
Invoke-Adb shell pm path $Package | Out-Null
Invoke-Adb logcat -c | Out-Null
Invoke-Adb shell am force-stop $Package | Out-Null
Invoke-Adb shell am start -W -n $component | Out-Null
Wait-ForLog 'mapped=true' | Out-Null

Invoke-Adb shell input mouse motionevent MOVE 300 500 | Out-Null
Invoke-Adb shell input mouse tap 300 500 | Out-Null
Invoke-Adb shell input mouse scroll 300 500 --axis 'VSCROLL,2' --axis 'HSCROLL,1' | Out-Null
Invoke-Adb shell input touchscreen swipe 200 700 260 730 80 | Out-Null
Invoke-Adb shell input keyboard keyevent KEYCODE_A | Out-Null
Invoke-Adb shell input keyboard keycombination -t 120 KEYCODE_SHIFT_LEFT KEYCODE_B | Out-Null
Invoke-Adb shell input keyevent KEYCODE_HOME | Out-Null
Start-Sleep -Milliseconds 800
Invoke-Adb shell am start -W -n $component | Out-Null
Start-Sleep -Milliseconds 800
Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
$log = Wait-ForLog 'Runtime GUI exit=0'

$checks = [ordered]@{
    mapped = 'mapped=true'
    pointer_enter = 'wl_pointer\] enter:'
    pointer_button = 'wl_pointer\] button:'
    wheel_vertical = 'axis: 0 \(vertical\)'
    wheel_horizontal = 'axis: 1 \(horizontal\)'
    touch_down = 'wl_touch\] down:'
    touch_motion = 'wl_touch\] motion:'
    touch_up = 'wl_touch\] up:'
    keyboard_key = 'wl_keyboard\] key:'
    keyboard_modifiers = 'depressed: 00000001'
    repeat_info = 'repeat_info: rate: 25 keys/sec; delay: 400 ms'
    focus_leave = 'wl_keyboard\] leave:'
    graceful_close = 'xdg_toplevel\] close'
}
foreach ($check in $checks.GetEnumerator()) {
    if ($log -notmatch $check.Value) {
        throw "Missing wev assertion $($check.Key): $($check.Value)"
    }
}
$enterCount = ([regex]::Matches($log, 'wl_keyboard\] enter:')).Count
if ($enterCount -lt 2) {
    throw "Expected keyboard enter on initial focus and resume, found $enterCount"
}
if ($log -match 'Could not launch|Runtime GUI exit=(?!0)') {
    throw "wev runtime reported an error: $log"
}

Write-Host "wev Wayland input passed on $Serial"
Write-Host "pointer, wheel, touch, keyboard, modifiers, repeat metadata, focus, and close verified"
