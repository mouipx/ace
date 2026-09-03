# zombies-watchdog.ps1
# Background loop: checks the REAL driver state every 15s and re-applies the
# profile only if the driver actually drifted. Logs to Desktop\ZombiesSession.log.
# Launched by StartZombies.ps1 (do not run manually).

param(
    [string]$Cli,
    [string]$Profile
)

$log = Join-Path ([Environment]::GetFolderPath("Desktop")) "ZombiesSession.log"
$drop = 0
$check = 0

Add-Content $log "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') watchdog started"

while ($true) {
    $check++

    & $Cli verify $Profile 2>&1 | Out-Null
    $code = $LASTEXITCODE

    if ($code -eq 0) {
        # in sync - nothing to do
    }
    elseif ($code -eq 3) {
        $drop++
        Add-Content $log "$(Get-Date -Format 'HH:mm:ss') DRIFT #$drop detected - re-applying"
        & $Cli apply $Profile 2>&1 | Out-Null
        Add-Content $log "$(Get-Date -Format 'HH:mm:ss') re-applied (exit $LASTEXITCODE)"
    }
    else {
        Add-Content $log "$(Get-Date -Format 'HH:mm:ss') verify error (exit $code)"
    }

    Start-Sleep -Seconds 15
}
