param(
    [string]$Repository = "Nulifyer/Archphene",
    [string]$KeystorePath = "tooling/signing/archphene-release.keystore",
    [string]$CredentialsPath = "tooling/signing/archphene-release-credentials.json",
    [string]$KeyAlias = "archphene"
)

$ErrorActionPreference = "Stop"

function New-RandomSecret {
    $bytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(36)
    return [Convert]::ToBase64String($bytes).TrimEnd("=")
}

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$keyPath = Join-Path $root $KeystorePath
$credentialsFile = Join-Path $root $CredentialsPath
New-Item -ItemType Directory -Force -Path (Split-Path $keyPath) | Out-Null

if ((Test-Path -LiteralPath $keyPath) -xor (Test-Path -LiteralPath $credentialsFile)) {
    throw "Keystore and credentials backup must either both exist or both be absent"
}

if (Test-Path -LiteralPath $keyPath) {
    $credentials = Get-Content -LiteralPath $credentialsFile -Raw | ConvertFrom-Json
} else {
    $storePassword = New-RandomSecret
    $credentials = [pscustomobject]@{
        storePassword = $storePassword
        keyPassword = $storePassword
        keyAlias = $KeyAlias
    }
    & keytool -genkeypair -v `
        -keystore $keyPath `
        -storetype PKCS12 `
        -storepass $credentials.storePassword `
        -keypass $credentials.keyPassword `
        -alias $credentials.keyAlias `
        -keyalg RSA `
        -keysize 4096 `
        -validity 10000 `
        -dname "CN=Archphene Release,O=Archphene,C=US"
    if ($LASTEXITCODE -ne 0) { throw "Could not generate release keystore" }
    $credentials | ConvertTo-Json | Set-Content -LiteralPath $credentialsFile -Encoding utf8
}

Remove-Item Env:ALL_PROXY,Env:HTTP_PROXY,Env:HTTPS_PROXY,Env:GIT_HTTP_PROXY,Env:GIT_HTTPS_PROXY `
    -ErrorAction SilentlyContinue
& gh api user --silent
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI authentication is required" }

$secrets = @{
    ARCHPHENE_RELEASE_KEYSTORE_BASE64 = [Convert]::ToBase64String(
        [IO.File]::ReadAllBytes($keyPath))
    ARCHPHENE_RELEASE_STORE_PASSWORD = [string]$credentials.storePassword
    ARCHPHENE_RELEASE_KEY_ALIAS = [string]$credentials.keyAlias
    ARCHPHENE_RELEASE_KEY_PASSWORD = [string]$credentials.keyPassword
}
foreach ($entry in $secrets.GetEnumerator()) {
    & gh secret set $entry.Key --repo $Repository --body $entry.Value
    if ($LASTEXITCODE -ne 0) { throw "Could not set GitHub secret $($entry.Key)" }
}

Write-Host "GitHub release signing configured for $Repository."
Write-Host "Back up both files outside this computer:"
Write-Host "  $keyPath"
Write-Host "  $credentialsFile"