# =============================================================
# DEAD END ZOMBIES - LAUNCHER (PowerShell, v2)
# G Pro Wireless | 1200 DPI | 1000Hz
#
# Same job as the old .bat, but:
#   - applies the profile with rawaccel-cli (no hidden pop-ups)
#   - watchdog checks REAL driver state (rawaccel-cli verify),
#     not fragile `fc /b` file compares
# =============================================================
#Requires -Version 5.1

# ---------- CONFIG (edit paths if needed) ----------
$RawAccelDir = "C:\Users\sarah\Downloads\RawAccel_v1.7.1\RawAccel"
$ProfileSrc  = "C:\Users\sarah\OneDrive\Desktop\deadend\zombies_deadend_v2.json"
$SettingsDst = Join-Path $RawAccelDir "settings.json"
$BackupDst   = Join-Path $RawAccelDir "settings_backup_default.json"
$Writer      = Join-Path $RawAccelDir "writer.exe"
$Cli         = Join-Path $RawAccelDir "rawaccel-cli.exe"
$GHubExe     = "C:\Program Files\LGHUB\lghub.exe"
$Watchdog    = Join-Path $PSScriptRoot "zombies-watchdog.ps1"

# ---------- feature toggles ----------
$EnableWatchdog    = $true
$OptimizePower     = $true
$DisableUsbSuspend = $true

# ---------- self-elevate ----------
$isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
           ).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "Requesting admin privileges..."
    Start-Process powershell -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`""
    exit
}

$ErrorActionPreference = "Continue"

Write-Host ""
Write-Host " ============================================================"
Write-Host "   DEAD END ZOMBIES - LAUNCHER  (v2)"
Write-Host "   G Pro Wireless | 1200 DPI | 1000Hz"
Write-Host " ============================================================"
Write-Host ""

function Fail($msg) {
    Write-Host "   ERROR: $msg" -ForegroundColor Red
    Write-Host " ============================================================"
    Write-Host "   RESULT: FAILED - see errors above"
    Write-Host " ============================================================"
    Read-Host "Press Enter to close"
    exit 1
}

# ---------- 1. checks ----------
Write-Host "[1/6] System checks..."

$svc = Get-Service rawaccel -ErrorAction SilentlyContinue
if (-not $svc -or $svc.Status -ne "Running") {
    Fail "Raw Accel driver not detected as running. Reinstall Raw Accel and enable the driver."
}
Write-Host "      Raw Accel driver: RUNNING"

if (-not (Test-Path $Cli)) {
    Write-Host "      WARNING: rawaccel-cli.exe not found - will fall back to writer.exe" -ForegroundColor Yellow
}
if (-not (Test-Path $Writer)) {
    Fail "writer.exe not found at $Writer"
}
if (-not (Test-Path $ProfileSrc)) {
    Fail "Profile not found at $ProfileSrc"
}
Write-Host "      Profile JSON: FOUND"

# ---------- 2. power + USB ----------
if ($OptimizePower) {
    Write-Host "[2/6] Setting High Performance power plan..."
    powercfg /setactive 8c5e7fda-e8bf-4a96-9a85-a6e23a8c635c *> $null
    Write-Host "      Power plan: HIGH PERFORMANCE"
}

if ($DisableUsbSuspend) {
    Write-Host "[3/6] Disabling USB Selective Suspend..."
    $scheme = (powercfg /getactivescheme | Select-String -Pattern "GUID:\s*([0-9a-f-]+)").Matches.Groups[1].Value
    if ($scheme) {
        powercfg /setacvalueindex $scheme SUB_USB USBSELECTIVE 0 *> $null
        powercfg /setdcvalueindex $scheme SUB_USB USBSELECTIVE 0 *> $null
        powercfg /setactive $scheme *> $null
        Write-Host "      USB Selective Suspend: DISABLED"
    }
}

# ---------- 3. backup ----------
Write-Host "[4/6] Backing up current settings..."
if (Test-Path $SettingsDst) {
    $same = (Get-FileHash $SettingsDst).Hash -eq (Get-FileHash $ProfileSrc).Hash
    if (-not $same) {
        Copy-Item $SettingsDst $BackupDst -Force
        Write-Host "      Current settings backed up as default"
    } else {
        Write-Host "      Profile already active - backup skipped"
    }
} else {
    Write-Host "      No existing settings.json to back up"
}

# ---------- 4. apply ----------
Write-Host "[5/6] Applying Dead End profile..."
Copy-Item $ProfileSrc $SettingsDst -Force

if (Test-Path $Cli) {
    & $Cli apply $SettingsDst
    if ($LASTEXITCODE -ne 0) { Fail "rawaccel-cli apply failed (exit $LASTEXITCODE)" }
} else {
    Push-Location $RawAccelDir
    & $Writer $SettingsDst
    $code = $LASTEXITCODE
    Pop-Location
    if ($code -ne 0) { Fail "writer.exe failed (exit $code)" }
}
Write-Host "      Profile written to driver: SUCCESS"

# ---------- 5. G HUB ----------
Write-Host "[6/6] Checking Logitech G HUB..."
$ghub = Get-Process lghub -ErrorAction SilentlyContinue
if (-not $ghub) {
    if (Test-Path $GHubExe) {
        Start-Process $GHubExe
        Start-Sleep -Seconds 3
        Write-Host "      G HUB launched"
    } else {
        Write-Host "      WARNING: G HUB not found - Lua scripts won't work" -ForegroundColor Yellow
    }
} else {
    Write-Host "      G HUB: RUNNING"
}

$omm = Get-Process OnboardMemoryManager* -ErrorAction SilentlyContinue
if ($omm) {
    Write-Host "      NOTE: OnboardMemoryManager is running - it can change DPI mid-game" -ForegroundColor Yellow
    Write-Host "            and shift your LUT. Close it after setting your DPI slot."
} else {
    Write-Host "      OnboardMemoryManager: NOT RUNNING (good)"
}

# ---------- watchdog ----------
if ($EnableWatchdog) {
    if (Test-Path $Cli -and (Test-Path $Watchdog)) {
        Start-Process powershell -WindowStyle Minimized -ArgumentList @(
            "-NoProfile", "-ExecutionPolicy", "Bypass",
            "-File", "`"$Watchdog`"",
            "-Cli", "`"$Cli`"",
            "-Profile", "`"$ProfileSrc`""
        )
        Write-Host "      Watchdog (real driver-state check): RUNNING (minimized)"
    } else {
        Write-Host "      Watchdog: SKIPPED (rawaccel-cli.exe or zombies-watchdog.ps1 missing)"
    }
}

# ---------- done ----------
Write-Host ""
Write-Host " ============================================================"
Write-Host "   ALL SYSTEMS READY - DEAD END LOADOUT ACTIVE"
Write-Host " ============================================================"
Write-Host ""
Write-Host "   Remember: keep the mouse at 1200 DPI for the whole"
Write-Host "   session. Any DPI change shifts the entire curve and"
Write-Host "   looks exactly like Raw Accel 'dropping'."
Write-Host ""
Read-Host "Press Enter to close this window (watchdog keeps running)"
