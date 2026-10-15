param([string]$Version='0.1.0',[int]$VersionCode=100)
$ErrorActionPreference='Stop'
if($Version -notmatch '^\d+\.\d+\.\d+$') { throw 'Invalid version' }
$root=Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if(-not (Test-Path -LiteralPath 'signing.properties')) { throw 'Missing private signing.properties' }
    & .\gradlew.bat :app:assembleRelease "-PappVersionName=$Version" "-PappVersionCode=$VersionCode" --console plain
    if($LASTEXITCODE -ne 0) { throw 'Release build failed' }
    $output=Join-Path $root "artifacts/release-$Version"
    New-Item -ItemType Directory -Force -Path $output | Out-Null
    $apk=Join-Path $output "wanjuan-$Version.apk"
    Copy-Item -LiteralPath 'app/build/outputs/apk/release/app-release.apk' -Destination $apk -Force
    $digest=(Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash.ToLowerInvariant()
    $manifest=@{packageName='io.github.godbook.wanjuan';versionName=$Version;versionCode=$VersionCode;apkUrl="https://github.com/GodBook/wanjuan/releases/download/v$Version/wanjuan-$Version.apk";sha256=$digest;notes="万卷 $Version：离线备份、书签保护、下载继续与存储管理。"}
    $manifest | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $output 'update.json') -Encoding utf8NoBOM
    "$digest  wanjuan-$Version.apk" | Set-Content -LiteralPath (Join-Path $output 'SHA256SUMS.txt') -Encoding utf8NoBOM
    Write-Output "Release artifacts: $output"
} finally { Pop-Location }
