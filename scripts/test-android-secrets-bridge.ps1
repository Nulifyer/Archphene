param(
    [ValidateSet("x86_64", "arm64-v8a")]
    [string]$AndroidAbi = "x86_64",
    [string]$Serial = "emulator-5554",
    [int]$TimeoutSeconds = 30
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Adb = Join-Path $Root "tooling/android-sdk/platform-tools/adb.exe"
$Package = "org.archphene.secretsprobe"
$Activity = "org.archphene.bridge.SecretsProbeActivity"
$Apk = Join-Path $Root "prototypes/secrets-capability-probe/out-$AndroidAbi/archphene-secrets-probe.apk"
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
$logs = (Invoke-Adb @("logcat", "-d", "-v", "brief", "-s",
        "ArchpheneCapabilities:I", "AndroidRuntime:E", "*:S") "read secrets logs") -join "`n"
if ($logs -match "FATAL EXCEPTION") { throw "Secrets probe crashed: $logs" }
if ($logs.Contains($Secret) -or $logs.Contains($UpdatedSecret)) {
    throw "Secret plaintext was written to Android logs"
}
Write-Host ("Android secrets bridge passed on $Serial ($AndroidAbi): encrypted storage, " +
        "metadata, overwrite, restart persistence, bounds, delete, lifecycle, and standard D-Bus adapter validated.")
