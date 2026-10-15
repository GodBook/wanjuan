param([Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    & .\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest --console plain
    if($LASTEXITCODE -ne 0) { throw 'Build failed' }
    & adb -s $Serial install -r app/build/outputs/apk/debug/app-debug.apk
    if($LASTEXITCODE -ne 0) { throw 'App install failed' }
    & adb -s $Serial install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
    if($LASTEXITCODE -ne 0) { throw 'Test install failed' }
    & adb -s $Serial shell am instrument -w -r io.github.godbook.wanjuan.debug.test/androidx.test.runner.AndroidJUnitRunner
    if($LASTEXITCODE -ne 0) { throw 'Device test command failed' }
} finally { Pop-Location }
