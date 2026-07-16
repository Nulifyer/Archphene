param(
    [ValidateSet("x86_64", "arm64-v8a")]
    [string]$AndroidAbi = "x86_64",
    [string]$Serial = "emulator-5554"
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Apk = Join-Path $Root "prototypes/native-compositor-probe/out-$AndroidAbi/archphene-compositor-probe.apk"
$ReceiverApk = Join-Path $Root "prototypes/uri-grant-receiver-probe/out-$AndroidAbi/archphene-uri-grant-receiver-probe.apk"
if ((-not (Test-Path -LiteralPath $Apk -PathType Leaf)) -or
        (-not (Test-Path -LiteralPath $ReceiverApk -PathType Leaf))) {
    throw "Prebuilt probe APKs missing. Build them in Podman first: ./scripts/build-native-compositor-probe-podman.ps1 -AndroidAbi $AndroidAbi"
}

function Invoke-Adb([Parameter(ValueFromRemainingArguments)][string[]]$Arguments) {
    & adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "adb failed: $($Arguments -join ' ')"
    }
}

function Wait-ForProbe([string]$Passed, [string]$Failed, [string]$Label) {
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        Start-Sleep -Milliseconds 250
        $output = (& adb -s $Serial logcat -d -s "ArchpheneCompositorProbe:I" "*:S") -join [Environment]::NewLine
        if ($output.Contains($Passed)) { return }
        if ($output.Contains($Failed)) {
            throw ("$Label reported failure:" + [Environment]::NewLine + $output)
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for $Label on $Serial ($AndroidAbi)."
}

function Wait-ForUriRead([bool]$ShouldPass) {
    $deadline = [DateTime]::UtcNow.AddSeconds(15)
    do {
        Start-Sleep -Milliseconds 250
        $output = (& adb -s $Serial logcat -d -s "ArchpheneUriGrantProbe:I" "*:S") `
            -join [Environment]::NewLine
        if ($ShouldPass -and $output.Contains(
                "URI read passed ARCHPHENE_OUTBOUND_URI_GRANT")) { return }
        if (-not $ShouldPass -and $output.Contains("URI read denied")) { return }
        if ($ShouldPass -and $output.Contains("URI read denied")) {
            throw ("Granted URI read was denied:" + [Environment]::NewLine + $output)
        }
        if (-not $ShouldPass -and $output.Contains("URI read passed")) {
            throw "Linux home provider allowed an external read without a URI grant."
        }
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Timed out waiting for URI receiver probe on $Serial ($AndroidAbi)."
}

Invoke-Adb get-state | Out-Null
Invoke-Adb install -r $Apk | Out-Null
Invoke-Adb install -r $ReceiverApk | Out-Null

Invoke-Adb logcat -c
Invoke-Adb shell am force-stop org.archphene.compositorprobe
Invoke-Adb shell am start -n org.archphene.compositorprobe/.MainActivity --ez drag_only true | Out-Null
Wait-ForProbe "Native drag-and-drop probe passed" `
    "Native drag-and-drop probe failed" "Wayland drag-and-drop probe"

Invoke-Adb logcat -c
Invoke-Adb shell am force-stop org.archphene.compositorprobe
Invoke-Adb shell am start -n org.archphene.compositorprobe/.MainActivity `
    --ez document_drag_only true | Out-Null
Wait-ForProbe "Document drag broker probe passed" `
    "Document drag broker probe failed" "document drag broker probe"

$providerUri = "content://org.archphene.compositorprobe.documents/document/home%2FDocuments%2Foutbound-grant.txt"
Invoke-Adb logcat -c
Invoke-Adb shell am force-stop org.archphene.compositorprobe
Invoke-Adb shell am start -n org.archphene.compositorprobe/.MainActivity `
    --ez provider_grant_only true --ez grant_provider_uri false | Out-Null
Wait-ForProbe "Provider URI grant probe prepared" `
    "Provider URI grant probe failed" "provider URI preparation probe"
Wait-ForUriRead $false

Invoke-Adb logcat -c
Invoke-Adb shell am force-stop org.archphene.compositorprobe
Invoke-Adb shell am start -n org.archphene.compositorprobe/.MainActivity `
    --ez provider_grant_only true --ez grant_provider_uri true | Out-Null
Wait-ForProbe "Provider URI grant probe ready" `
    "Provider URI grant probe failed" "provider URI grant probe"
Wait-ForUriRead $true

Write-Host "Android/Wayland text and document drag-and-drop passed on $Serial ($AndroidAbi)."