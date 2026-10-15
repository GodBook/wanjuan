param([Parameter(Mandatory=$true)][string]$Serial,[switch]$LiveSources,[switch]$SkipBuild)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    if(-not $SkipBuild) {
        & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console plain
        if($LASTEXITCODE -ne 0) { throw 'Build failed' }
    }
    & adb -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
    if($LASTEXITCODE -ne 0) { throw 'App install failed' }
    & adb -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if($LASTEXITCODE -ne 0) { throw 'Test install failed' }
    $instrumentArgs=@('-s',$Serial,'shell','am','instrument','-w','-r')
    if($LiveSources) {$instrumentArgs+=@('-e','liveSources','true')}
    $instrumentArgs+='io.github.godbook.wanjuan.debug.test/androidx.test.runner.AndroidJUnitRunner'
    & adb @instrumentArgs | Tee-Object -FilePath (Join-Path $root "artifacts/013-$Serial-tests.log") | Tee-Object -Variable testOutput
    if($LASTEXITCODE -ne 0 -or ($testOutput -join "`n") -notmatch 'OK \(\d+ tests?\)') { throw 'Device tests did not pass' }
} finally { Pop-Location }
