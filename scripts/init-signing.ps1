param([Parameter(Mandatory=$true)][string]$PrivateDirectory,[Parameter(Mandatory=$true)][string]$Keytool)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$private=[System.IO.Path]::GetFullPath($PrivateDirectory)
if($private.StartsWith($root,[System.StringComparison]::OrdinalIgnoreCase)) {throw 'Signing key must be outside the repository'}
New-Item -ItemType Directory -Force -Path $private | Out-Null
$keyFile=Join-Path $private 'wanjuan-release.p12'
$configFile=Join-Path $private 'signing.properties'
if(-not (Test-Path -LiteralPath $keyFile)) {
    $randomBytes=New-Object byte[] 32
    [System.Security.Cryptography.RandomNumberGenerator]::Fill($randomBytes)
    $password=[Convert]::ToBase64String($randomBytes)
    $env:WANJUAN_SIGNING_PASSWORD=$password
    try {
        & $Keytool -genkeypair -keystore $keyFile -storetype PKCS12 -storepass:env WANJUAN_SIGNING_PASSWORD -keypass:env WANJUAN_SIGNING_PASSWORD -alias wanjuan -keyalg RSA -keysize 4096 -validity 10000 -dname 'CN=WanJuan, OU=Mobile, O=GodBook, C=CN'
        if($LASTEXITCODE -ne 0) {throw 'Signing key generation failed'}
        $config="storeFile=$($keyFile.Replace('\','/'))`nstorePassword=$password`nkeyAlias=wanjuan`nkeyPassword=$password`n"
        [System.IO.File]::WriteAllText($configFile,$config,[System.Text.UTF8Encoding]::new($false))
    } finally { $env:WANJUAN_SIGNING_PASSWORD=$null }
}
if(-not (Test-Path -LiteralPath $configFile)) {throw 'Existing signing key has no matching configuration'}
Copy-Item -LiteralPath $configFile -Destination (Join-Path $root 'signing.properties') -Force
Write-Output 'Signing configuration ready. Private key remains outside the repository.'
