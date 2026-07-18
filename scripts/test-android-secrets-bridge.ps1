param(
    [ValidateSet("x86_64", "arm64-v8a")]
    [string]$AndroidAbi = "x86_64",
    [string]$Serial = "emulator-5554",
    [int]$TimeoutSeconds = 30,
    [string]$Apk = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.secretsprobe"
$Activity = "org.archphene.bridge.SecretsProbeActivity"
$Apk = if ($Apk) { (Resolve-Path $Apk).Path } else {
    Join-Path $Root "prototypes/secrets-capability-probe/out-$AndroidAbi/archphene-secrets-probe.apk"
}
$Secret = "archphene-secret-value-284917"
$UpdatedSecret = "archphene-updated-value-592641"

function Invoke-Adb([string[]]$Arguments, [string]$Step) {
    $output = & $Adb -s $Serial @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "$Step failed with exit code ${LASTEXITCODE}: $($output -join "`n")"
    }
    return $output
}

function Start-Probe {
    Invoke-Adb @("shell", "am", "force-stop", $Package) "stop secrets probe" | Out-Null
    Invoke-Adb @("shell", "am", "start", "-W", "-n", "$Package/$Activity") `
            "start secrets probe" | Out-Null
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
        $value = & $Adb -s $Serial shell run-as $Package cat files/secrets-broker-name 2>$null
        if ($LASTEXITCODE -eq 0 -and $value) { return ($value -join "").Trim() }
        Start-Sleep -Milliseconds 200
    } while ((Get-Date) -lt $deadline)
    $logs = & $Adb -s $Serial logcat -d -v brief -s ArchpheneCapabilities AndroidRuntime
    throw "Secrets broker did not start: $($logs -join "`n")"
}

function Native-Path {
    $dump = (Invoke-Adb @("shell", "dumpsys", "package", $Package) `
            "read secrets probe package") -join "`n"
    $native = [regex]::Match($dump, "legacyNativeLibraryDir=(\S+)").Groups[1].Value
    if (-not $native) { throw "Secrets probe native library directory is unavailable" }
    $subdirectory = if ($AndroidAbi -eq "arm64-v8a") { "arm64" } else { "x86_64" }
    return "$native/$subdirectory/libarchphene_secrets_probe.so"
}

function Invoke-Probe([string]$Socket, [string[]]$Arguments, [switch]$AllowFailure) {
    $native = Native-Path
    $output = & $Adb -s $Serial shell run-as $Package $native `
            --socket "@$Socket" @Arguments 2>&1
    if (-not $AllowFailure -and $LASTEXITCODE -ne 0) {
        throw "Secrets capability request failed: $($output -join "`n")"
    }
    return ($output -join "`n").Trim()
}

function Read-PrivateFile([string]$Path) {
    return ((Invoke-Adb @("shell", "run-as", $Package, "cat", $Path) `
            "read private secrets fixture") -join "`n")
}

if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) {
    throw "Secrets probe APK is missing: $Apk"
}
Invoke-Adb @("wait-for-device") "wait for secrets device" | Out-Null
Invoke-Adb @("install", "-r", $Apk) "install secrets probe" | Out-Null
Invoke-Adb @("shell", "pm", "clear", $Package) "clear secrets probe" | Out-Null
Invoke-Adb @("logcat", "-c") "clear secrets logs" | Out-Null
$socket = Start-Probe
$attributes = "'{`"application`":`"archphene-probe`",`"scope`":`"test`"}'"

$stored = Invoke-Probe $socket @("store-secret", "files/secret-input", "probe-login",
        "Probe-login", $attributes)
if ($stored -ne "OK") { throw "Secret store response is invalid: $stored" }

$records = @(Invoke-Adb @("shell", "run-as", $Package, "ls", "files/secret-store") `
        "list encrypted secret records")
if ($records.Count -ne 1 -or $records[0] -notmatch '^[0-9a-f]{64}\.secret$') {
    throw "Secret store did not create one hashed record: $($records -join ', ')"
}
foreach ($plaintext in @($Secret, "probe-login", "Probe-login", "archphene-probe")) {
    $grep = & $Adb -s $Serial shell run-as $Package grep -a -F $plaintext `
            "files/secret-store/$($records[0])" 2>&1
    if ($LASTEXITCODE -eq 0) {
        throw "Plaintext metadata or payload is present in the encrypted record: " +
                "$($grep -join "`n")"
    }
    if ($LASTEXITCODE -ne 1) {
        throw "Ciphertext plaintext scan failed: $($grep -join "`n")"
    }
}

$staleName = "files/secret-store/$('0' * 64).secret.tmp-deadbeef"
Invoke-Adb @("shell", "run-as", $Package, "touch", $staleName) `
        "create stale encrypted-record fixture" | Out-Null
$listed = Invoke-Probe $socket @("list-secrets", "files/secret-index.json")
$staleCheck = & $Adb -s $Serial shell run-as $Package test -e $staleName 2>&1
if ($LASTEXITCODE -eq 0) { throw "Stale encrypted-record fixture was not reclaimed" }
if ($LASTEXITCODE -ne 1) {
    throw "Stale encrypted-record cleanup check failed: $($staleCheck -join "`n")"
}
if ($listed -ne "OK`t1") { throw "Secret list response is invalid: $listed" }
$index = Read-PrivateFile "files/secret-index.json" | ConvertFrom-Json
if (($index.Count -ne 1) -or
        ($index[0].id -ne "probe-login") -or
        ($index[0].label -ne "Probe-login") -or
        ($index[0].attributes.application -ne "archphene-probe") -or
        ($index[0].attributes.scope -ne "test")) {
    throw "Secret index metadata is invalid: $($index | ConvertTo-Json -Compress -Depth 5)"
}

$read = Invoke-Probe $socket @("read-secret", "files/secret-output", "probe-login")
if ($read -notmatch '^OK\t[^\t]*\t[^\t]+\t(\d+)$') {
    throw "Secret read metadata is invalid: $read"
}
if ((Read-PrivateFile "files/secret-output") -ne $Secret) {
    throw "Secret read did not reproduce the exact payload"
}

$updated = Invoke-Probe $socket @("store-secret", "files/secret-updated", "probe-login",
        "Updated-login", $attributes)
if ($updated -ne "OK") { throw "Secret overwrite failed: $updated" }
Invoke-Probe $socket @("read-secret", "files/secret-output", "probe-login") | Out-Null
if ((Read-PrivateFile "files/secret-output") -ne $UpdatedSecret) {
    throw "Secret overwrite did not replace the payload"
}

$oldSocket = $socket
Invoke-Adb @("shell", "am", "force-stop", $Package) "stop secrets persistence fixture" | Out-Null
$stale = Invoke-Probe $oldSocket @("list-secrets", "files/stale-index.json") -AllowFailure
if ($stale -eq "OK`t1") { throw "Stopped secrets broker accepted a stale socket request" }
$socket = Start-Probe
Invoke-Probe $socket @("read-secret", "files/persisted-output", "probe-login") | Out-Null
if ((Read-PrivateFile "files/persisted-output") -ne $UpdatedSecret) {
    throw "Encrypted secret did not persist across process death"
}

$malformed = Invoke-Probe $socket @("store-secret", "files/secret-input", "bad-attrs",
        "Bad-attributes", "[]") -AllowFailure
if ($malformed -ne "ERROR`tINVALID_REQUEST") {
    throw "Malformed secret attributes were not rejected: $malformed"
}
$oversized = Invoke-Probe $socket @("store-secret", "files/secret-oversized", "too-large",
        "Too-large", "{}") -AllowFailure
if ($oversized -ne "ERROR`tINVALID_REQUEST") {
    throw "Oversized secret payload was not rejected: $oversized"
}

$deleted = Invoke-Probe $socket @("delete-secret", "probe-login")
if ($deleted -ne "OK") { throw "Secret delete failed: $deleted" }
$missing = Invoke-Probe $socket @("read-secret", "files/missing-output", "probe-login") `
        -AllowFailure
if ($missing -ne "ERROR`tNOT_FOUND") { throw "Deleted secret remained readable: $missing" }
$emptyList = Invoke-Probe $socket @("list-secrets", "files/empty-index.json")
if ($emptyList -ne "OK`t0" -or (Read-PrivateFile "files/empty-index.json") -ne "[]") {
    throw "Deleted secret remained in the metadata index"
}

$busAddress = (Read-PrivateFile "files/secrets-bus-address").Trim()
$serviceProbe = (Native-Path).Replace(
        "libarchphene_secrets_probe.so", "libarchphene_secret_service_probe.so")
$serviceCommand = "export DBUS_SESSION_BUS_ADDRESS='$busAddress'; exec '$serviceProbe'"
$serviceOutput = & $Adb -s $Serial shell run-as $Package sh -c "'$serviceCommand'" 2>&1
if ($LASTEXITCODE -ne 0 -or ($serviceOutput -join "`n") -notmatch
        '^PASS Secret Service:') {
    throw "Secret Service D-Bus adapter failed: $($serviceOutput -join "`n")"
}
$pageSize = [int](((Invoke-Adb @("shell", "getconf", "PAGESIZE") `
        "read Android page size") -join "").Trim())
$libsecretValidated = $false
$kwalletValidated = $false
$kwalletDirectValue = "archphene-kwallet-direct-68413"
$kwalletQueryValue = "archphene-kwallet-query-39752"
$fixtureCheck = & $Adb -s $Serial shell run-as $Package test -f files/libsecret-runtime-root 2>&1
$hasLibsecretFixture = $LASTEXITCODE -eq 0
if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 1) {
    throw "Could not inspect packaged libsecret fixture: $($fixtureCheck -join "`n")"
}
if ($hasLibsecretFixture -and ($AndroidAbi -eq "arm64-v8a" -or
        ($AndroidAbi -eq "x86_64" -and $pageSize -eq 4096))) {
    $dump = (Invoke-Adb @("shell", "dumpsys", "package", $Package) `
            "read packaged libsecret paths") -join "`n"
    $nativeSubdirectory = if ($AndroidAbi -eq "arm64-v8a") { "arm64" } else { "x86_64" }
    $nativeDirectory = [regex]::Match(
            $dump, "legacyNativeLibraryDir=(\S+)").Groups[1].Value + "/" +
            $nativeSubdirectory
    $libsecretRoot = (Read-PrivateFile "files/libsecret-runtime-root").Trim()
    $libsecretLoader = "$nativeDirectory/libarchphene_libsecret_loader.so"
    $secretTool = "$libsecretRoot/secret-tool"
    $libsecretValue = "archphene-real-libsecret-52941"

    function Invoke-Libsecret([string]$Command, [int[]]$AllowedExitCodes = @(0)) {
        $remote = "export DBUS_SESSION_BUS_ADDRESS=$busAddress; " +
                "export LD_LIBRARY_PATH=$libsecretRoot/lib; $Command"
        $output = & $Adb -s $Serial shell run-as $Package sh -c "'$remote'" 2>&1
        $exitCode = $LASTEXITCODE
        if ($AllowedExitCodes -notcontains $exitCode) {
            throw "Packaged Arch libsecret command failed with exit code " +
                    "${exitCode}: $($output -join "`n")"
        }
        return [pscustomobject]@{
            ExitCode = $exitCode
            Output = ($output -join "`n").Trim()
        }
    }

    Invoke-Libsecret ("printf %s\\n $libsecretValue | $libsecretLoader " +
            "$secretTool store --label=Archphene-libsecret-probe " +
            "application archphene-libsecret scope encrypted-dh") | Out-Null
    $lookup = Invoke-Libsecret ("$libsecretLoader $secretTool lookup " +
            "application archphene-libsecret scope encrypted-dh")
    if ($lookup.Output -ne $libsecretValue) {
        throw "Packaged Arch libsecret lookup mismatch: $($lookup.Output)"
    }
    Invoke-Libsecret ("$libsecretLoader $secretTool clear " +
            "application archphene-libsecret scope encrypted-dh") | Out-Null
    $missing = Invoke-Libsecret ("$libsecretLoader $secretTool lookup " +
            "application archphene-libsecret scope encrypted-dh") @(1)
    if ($missing.ExitCode -ne 1 -or $missing.Output) {
        throw "Packaged Arch libsecret clear did not remove the secret"
    }
    $libsecretValidated = $true

    $kwalletFixtureCheck = & $Adb -s $Serial shell run-as $Package test -x files/libsecret-runtime/kwalletd6 2>&1
    $hasKWalletFixture = $LASTEXITCODE -eq 0
    if ($LASTEXITCODE -ne 0 -and $LASTEXITCODE -ne 1) {
        throw "Could not inspect packaged KWallet fixture: " +
                ($kwalletFixtureCheck -join "`n")
    }
    if ($hasKWalletFixture) {
        $kwalletHome = "$libsecretRoot/kwallet-home"
        $kwalletRuntime = "$libsecretRoot/kwallet-runtime"
        $kwalletConfig = "[KSecretD]`nEnabled=false`n`n[Wallet]`nDefault Wallet=Login`n"
        $kwalletConfigBase64 = [Convert]::ToBase64String(
                [Text.Encoding]::UTF8.GetBytes($kwalletConfig))
        Invoke-Libsecret ("mkdir -p $kwalletHome/.config $kwalletRuntime; " +
                "echo $kwalletConfigBase64 | base64 -d > $kwalletHome/.config/kwalletrc; " +
                "chmod 700 $libsecretRoot/gdbus $libsecretRoot/kwalletd6 " +
                "$libsecretRoot/kwallet-query") | Out-Null
        $kwalletEnvironment = "export HOME=$kwalletHome; " +
                "export XDG_CONFIG_HOME=$kwalletHome/.config; " +
                "export XDG_DATA_HOME=$kwalletHome/.local/share; " +
                "export XDG_RUNTIME_DIR=$kwalletRuntime; " +
                "export QT_QPA_PLATFORM=minimal; " +
                "export QT_QPA_PLATFORM_PLUGIN_PATH=$libsecretRoot/qt/plugins/platforms"

        function Invoke-KWallet([string]$Command, [int[]]$AllowedExitCodes = @(0)) {
            return Invoke-Libsecret "$kwalletEnvironment; $Command" $AllowedExitCodes
        }

        $kwalletPid = $null
        try {
            $started = Invoke-KWallet ("nohup $libsecretLoader $libsecretRoot/kwalletd6 " +
                    "</dev/null >$kwalletHome/kwalletd.log 2>&1 & echo `$!")
            $kwalletPid = [int]$started.Output
            Start-Sleep -Milliseconds 750

            $gdbus = "$libsecretLoader $libsecretRoot/gdbus call --session " +
                    "--dest org.kde.kwalletd6 --object-path /modules/kwalletd6 " +
                    "--method org.kde.KWallet."
            $wallets = Invoke-KWallet "${gdbus}wallets"
            if ($wallets.Output -notmatch "Login") {
                throw "KWallet daemon did not expose the Secret Service login collection: " +
                        $wallets.Output
            }
            $opened = Invoke-KWallet "${gdbus}open Login 0 archphene-probe"
            $handleMatch = [regex]::Match($opened.Output, "-?\d+")
            if (-not $handleMatch.Success -or [int]$handleMatch.Value -le 0) {
                throw "KWallet daemon returned an invalid handle: $($opened.Output)"
            }
            $handle = [int]$handleMatch.Value
            $created = Invoke-KWallet (
                    "${gdbus}createFolder $handle Archphene archphene-probe")
            if ($created.Output -ne "(true,)") {
                throw "KWallet folder creation failed: $($created.Output)"
            }
            $written = Invoke-KWallet (
                    "${gdbus}writePassword $handle Archphene bridge-entry " +
                    "$kwalletDirectValue archphene-probe")
            if ($written.Output -ne "(0,)") {
                throw "KWallet direct password write failed: $($written.Output)"
            }
            $directRead = Invoke-KWallet (
                    "${gdbus}readPassword $handle Archphene bridge-entry archphene-probe")
            if (-not $directRead.Output.Contains($kwalletDirectValue)) {
                throw "KWallet direct password read mismatch: $($directRead.Output)"
            }

            Invoke-KWallet ("printf %s\\n $kwalletQueryValue > " +
                    "$kwalletHome/query-value") | Out-Null
            Invoke-KWallet ("$libsecretLoader $libsecretRoot/kwallet-query " +
                    "--write-password bridge-entry --folder Archphene Login " +
                    "< $kwalletHome/query-value") | Out-Null
            $queryRead = Invoke-KWallet (
                    "$libsecretLoader $libsecretRoot/kwallet-query " +
                    "--read-password bridge-entry --folder Archphene Login")
            if ($queryRead.Output -ne $kwalletQueryValue) {
                throw "Packaged Arch kwallet-query read/write mismatch: $($queryRead.Output)"
            }

            Invoke-KWallet "kill $kwalletPid" | Out-Null
            $kwalletPid = $null
            Start-Sleep -Milliseconds 300
            $restarted = Invoke-KWallet (
                    "nohup $libsecretLoader $libsecretRoot/kwalletd6 " +
                    "</dev/null >>$kwalletHome/kwalletd.log 2>&1 & echo `$!")
            $kwalletPid = [int]$restarted.Output
            Start-Sleep -Milliseconds 750
            $restartRead = Invoke-KWallet (
                    "$libsecretLoader $libsecretRoot/kwallet-query " +
                    "--read-password bridge-entry --folder Archphene Login")
            if ($restartRead.Output -ne $kwalletQueryValue) {
                throw "KWallet secret did not persist across daemon restart: " +
                        $restartRead.Output
            }

            $reopened = Invoke-KWallet "${gdbus}open Login 0 archphene-probe"
            $handle = [int][regex]::Match($reopened.Output, "-?\d+").Value
            $removed = Invoke-KWallet (
                    "${gdbus}removeEntry $handle Archphene bridge-entry archphene-probe")
            if ($removed.Output -ne "(0,)") {
                throw "KWallet cleanup failed: $($removed.Output)"
            }
            $kwalletValidated = $true
        } finally {
            if ($kwalletPid) {
                Invoke-KWallet "kill $kwalletPid" @(0, 1) | Out-Null
            }
        }
    }
}
$logs = (Invoke-Adb @("logcat", "-d", "-v", "brief", "-s",
        "ArchpheneCapabilities:I", "AndroidRuntime:E", "*:S") "read secrets logs") -join "`n"
if ($logs -match "FATAL EXCEPTION") { throw "Secrets probe crashed: $logs" }
if ($logs.Contains($Secret) -or $logs.Contains($UpdatedSecret) -or
        $logs.Contains($kwalletDirectValue) -or $logs.Contains($kwalletQueryValue)) {
    throw "Secret plaintext was written to Android logs"
}
$libsecretResult = if ($libsecretValidated -and $kwalletValidated) {
    ", and packaged Arch libsecret and KWallet clients validated"
} elseif ($libsecretValidated) {
    ", and packaged Arch libsecret client validated; KWallet client not included"
} elseif ($AndroidAbi -eq "x86_64") {
    "; packaged Arch libsecret skipped because upstream Arch ELF files are 4 KB-aligned"
} else {
    "; packaged Arch libsecret and KWallet clients are not included in this ABI probe"
}
Write-Host ("Android secrets bridge passed on $Serial ($AndroidAbi, ${pageSize}-byte pages): " +
        "encrypted storage, metadata, overwrite, restart persistence, bounds, delete, lifecycle, " +
        "and standard D-Bus adapter validated$libsecretResult.")
