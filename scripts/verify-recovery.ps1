param([Parameter(Mandatory=$true)][string]$Serial)
$ErrorActionPreference='Stop'
$root=Split-Path -Parent $PSScriptRoot
$runner='io.github.godbook.wanjuan.debug.test/androidx.test.runner.AndroidJUnitRunner'
foreach($phase in @('journal','old-moved','files-installed','settings-written','committed')) {
    & adb -s $Serial shell am instrument -w -r -e class io.github.godbook.wanjuan.AcceptanceProbeTest#prepareRealProcessDeath -e restoreDeathPhase $phase $runner 2>&1 |
        Tee-Object -FilePath (Join-Path $root "artifacts/013-$Serial-death-$phase.log")
    # This command intentionally kills the debug app. A new instrumentation process triggers startup recovery.
    $verification=& adb -s $Serial shell am instrument -w -r -e class io.github.godbook.wanjuan.AcceptanceProbeTest#verifyRealProcessRestart -e verifyDeath true $runner 2>&1
    $verification | Tee-Object -FilePath (Join-Path $root "artifacts/013-$Serial-restart-$phase.log")
    if(($verification -join "`n") -notmatch 'OK \(1 test\)') {throw "Restart verification failed at $phase"}
}
