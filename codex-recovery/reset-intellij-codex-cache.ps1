param(
  [switch]$ResetAuth,
  [string]$DefaultModel = "gpt-5.5[medium]"
)

$ErrorActionPreference = "Stop"

$ideaProcess = Get-Process -ErrorAction SilentlyContinue |
  Where-Object { $_.ProcessName -match "idea64|idea|IntelliJ" }

if ($ideaProcess) {
  Write-Host "IntelliJ IDEA appears to be running. Close IntelliJ first, then run this script again."
  Write-Host "This avoids IntelliJ rewriting the same settings/cache files during shutdown."
  exit 1
}

$codexHome = Join-Path $env:LOCALAPPDATA "JetBrains\IntelliJIdea2025.3\aia\codex"
$historyDir = Join-Path $env:LOCALAPPDATA "JetBrains\IntelliJIdea2025.3\aia-task-history"
$settingsFile = Join-Path $env:APPDATA "JetBrains\IntelliJIdea2025.3\options\acpAgents.xml"

if (-not (Test-Path -LiteralPath $codexHome)) {
  throw "Codex home not found: $codexHome"
}

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$backupRoot = Join-Path (Split-Path -Parent $PSCommandPath) "jetbrains-codex-reset-backup-$stamp"
New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null

if (Test-Path -LiteralPath $historyDir) {
  $historyBackup = Join-Path $backupRoot "aia-task-history"
  New-Item -ItemType Directory -Path $historyBackup -Force | Out-Null
  Get-ChildItem -LiteralPath $historyDir -File |
    Where-Object { $_.LastWriteTime -gt (Get-Date).AddDays(-14) } |
    Copy-Item -Destination $historyBackup -Force
}

$targets = @(
  "models_cache.json",
  "cache",
  ".tmp"
)

if ($ResetAuth) {
  $targets += "auth.json"
}

foreach ($name in $targets) {
  $path = Join-Path $codexHome $name
  if (Test-Path -LiteralPath $path) {
    $destination = Join-Path $backupRoot $name
    Move-Item -LiteralPath $path -Destination $destination -Force
    Write-Host "Moved $path"
  }
}

if (Test-Path -LiteralPath $settingsFile) {
  Copy-Item -LiteralPath $settingsFile -Destination (Join-Path $backupRoot "acpAgents.xml") -Force

  [xml]$settings = Get-Content -Raw -LiteralPath $settingsFile
  $codexModelEntry = $settings.application.component.option |
    Where-Object { $_.name -eq "agent_models" } |
    ForEach-Object { $_.map.entry } |
    Where-Object { $_.key -eq "codex" } |
    Select-Object -First 1

  if ($codexModelEntry) {
    $oldModel = $codexModelEntry.value
    $codexModelEntry.value = $DefaultModel
    $settings.Save($settingsFile)
    Write-Host "Changed saved Codex model from $oldModel to $DefaultModel"
  }
}

Write-Host ""
Write-Host "Done. Backup:"
Write-Host $backupRoot
Write-Host ""
Write-Host "Open IntelliJ, then open AI Chat and select Codex. Codex should rebuild these files."
Write-Host "Start a new Codex chat instead of continuing the compact-failed chat."
if ($ResetAuth) {
  Write-Host "Because -ResetAuth was used, sign back in to Codex when prompted."
}
