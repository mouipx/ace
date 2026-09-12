$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$acePath = Join-Path $projectRoot "app\build\compose\binaries\main\app\Ace\Ace.exe"

if (-not (Test-Path -LiteralPath $acePath -PathType Leaf)) {
    Write-Error "Current packaged Ace build was not found. Run: Push-Location app; .\gradlew.bat createDistributable; Pop-Location"
    exit 1
}

Write-Host "Launching CURRENT TEST BUILD:"
Write-Host $acePath
Start-Process -FilePath $acePath -WorkingDirectory (Split-Path $acePath)
