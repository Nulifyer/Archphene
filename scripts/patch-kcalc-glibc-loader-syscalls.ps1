$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
$LibDir = Join-Path $Root "prototypes/kcalc-android-app/lib/x86_64"
$Targets = @(
    "libarchphene_ld.so",
    "ld-linux-x86-64.so.2",
    "libld.so.2"
)

$Patches = @(
    [pscustomobject]@{
        Name = "startup set_robust_list syscall -> success"
        Offset = 0x140d8
        Expected = [byte[]](0x0f, 0x05)
        Replacement = [byte[]](0x31, 0xc0) # xor eax,eax
    },
    [pscustomobject]@{
        Name = "startup rseq syscall -> failure path"
        Offset = 0x1416d
        Expected = [byte[]](0x0f, 0x05)
        Replacement = [byte[]](0xf7, 0xd8) # neg eax; glibc compares as syscall failure
    }
)

function Test-Bytes([byte[]]$Data, [int]$Offset, [byte[]]$Expected) {
    for ($i = 0; $i -lt $Expected.Length; $i++) {
        if ($Data[$Offset + $i] -ne $Expected[$i]) {
            return $false
        }
    }
    return $true
}

foreach ($Target in $Targets) {
    $Path = Join-Path $LibDir $Target
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "Missing loader target: $Path"
    }

    $Backup = "$Path.orig"
    if (-not (Test-Path -LiteralPath $Backup)) {
        Copy-Item -LiteralPath $Path -Destination $Backup -Force
    }

    [byte[]]$Bytes = [System.IO.File]::ReadAllBytes($Path)
    foreach ($Patch in $Patches) {
        if (Test-Bytes $Bytes $Patch.Offset $Patch.Replacement) {
            Write-Host "$Target already patched: $($Patch.Name)"
            continue
        }
        if (-not (Test-Bytes $Bytes $Patch.Offset $Patch.Expected)) {
            $Found = ($Bytes[$Patch.Offset..($Patch.Offset + $Patch.Expected.Length - 1)] | ForEach-Object { $_.ToString("X2") }) -join " "
            $Expected = ($Patch.Expected | ForEach-Object { $_.ToString("X2") }) -join " "
            throw "$Target unexpected bytes at 0x$($Patch.Offset.ToString('x')): found $Found expected $Expected"
        }
        for ($i = 0; $i -lt $Patch.Replacement.Length; $i++) {
            $Bytes[$Patch.Offset + $i] = $Patch.Replacement[$i]
        }
        Write-Host "$Target patched: $($Patch.Name) at 0x$($Patch.Offset.ToString('x'))"
    }
    [System.IO.File]::WriteAllBytes($Path, $Bytes)
}

# Patch the libc startup signal-mask syscall used by __libc_init_first. Android's
# app seccomp profile kills raw rt_sigprocmask from this Linux ELF context.
$LibcPath = Join-Path $LibDir "libc.so.6"
$LibcBackup = "$LibcPath.orig"
if (-not (Test-Path -LiteralPath $LibcBackup)) {
    Copy-Item -LiteralPath $LibcPath -Destination $LibcBackup -Force
}
[byte[]]$LibcBytes = [System.IO.File]::ReadAllBytes($LibcPath)
$LibcOffset = 0x27765
$LibcExpected = [byte[]](0x0f, 0x05)
$LibcReplacement = [byte[]](0x31, 0xc0) # xor eax,eax
if (Test-Bytes $LibcBytes $LibcOffset $LibcReplacement) {
    Write-Host "libc.so.6 already patched: startup rt_sigprocmask syscall -> success"
} elseif (Test-Bytes $LibcBytes $LibcOffset $LibcExpected) {
    $LibcBytes[$LibcOffset] = $LibcReplacement[0]
    $LibcBytes[$LibcOffset + 1] = $LibcReplacement[1]
    [System.IO.File]::WriteAllBytes($LibcPath, $LibcBytes)
    Write-Host "libc.so.6 patched: startup rt_sigprocmask syscall -> success at 0x$($LibcOffset.ToString('x'))"
} else {
    $Found = ($LibcBytes[$LibcOffset..($LibcOffset + 1)] | ForEach-Object { $_.ToString("X2") }) -join " "
    throw "libc.so.6 unexpected bytes at 0x$($LibcOffset.ToString('x')): found $Found expected 0F 05"
}

$LibcRuntimePatches = @(
    [pscustomobject]@{
        Name = "libc pthread startup set_robust_list syscall -> success"
        Offset = 0x974cd
        Expected = [byte[]](0x0f, 0x05)
        Replacement = [byte[]](0x31, 0xc0)
    },
    [pscustomobject]@{
        Name = "libc pthread startup rseq syscall -> failure path"
        Offset = 0x977b3
        Expected = [byte[]](0x0f, 0x05)
        Replacement = [byte[]](0xf7, 0xd8)
    },
    [pscustomobject]@{
        Name = "libc fork child set_robust_list syscall -> success"
        Offset = 0xe56dc
        Expected = [byte[]](0x0f, 0x05)
        Replacement = [byte[]](0x31, 0xc0)
    }
)

[byte[]]$LibcBytes = [System.IO.File]::ReadAllBytes($LibcPath)
foreach ($Patch in $LibcRuntimePatches) {
    if (Test-Bytes $LibcBytes $Patch.Offset $Patch.Replacement) {
        Write-Host "libc.so.6 already patched: $($Patch.Name)"
        continue
    }
    if (-not (Test-Bytes $LibcBytes $Patch.Offset $Patch.Expected)) {
        $Found = ($LibcBytes[$Patch.Offset..($Patch.Offset + 1)] | ForEach-Object { $_.ToString("X2") }) -join " "
        throw "libc.so.6 unexpected bytes at 0x$($Patch.Offset.ToString('x')): found $Found expected 0F 05"
    }
    $LibcBytes[$Patch.Offset] = $Patch.Replacement[0]
    $LibcBytes[$Patch.Offset + 1] = $Patch.Replacement[1]
    Write-Host "libc.so.6 patched: $($Patch.Name) at 0x$($Patch.Offset.ToString('x'))"
}
[System.IO.File]::WriteAllBytes($LibcPath, $LibcBytes)
