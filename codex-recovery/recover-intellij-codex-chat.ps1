param(
  [switch]$List,
  [switch]$Latest,
  [switch]$LatestBroken,
  [string]$ChatId,
  [int]$Days = 30,
  [switch]$ResetCache,
  [switch]$ResetAuth,
  [string]$DefaultModel = "gpt-5.5[medium]",
  [string]$IdeProduct
)

$ErrorActionPreference = "Stop"

function Get-IntellijProduct {
  param([string]$RequestedProduct)

  if ($RequestedProduct) {
    $localPath = Join-Path $env:LOCALAPPDATA "JetBrains\$RequestedProduct"
    if (-not (Test-Path -LiteralPath $localPath)) {
      throw "JetBrains product folder not found: $localPath"
    }
    return $RequestedProduct
  }

  $root = Join-Path $env:LOCALAPPDATA "JetBrains"
  $candidate = Get-ChildItem -LiteralPath $root -Directory -Filter "IntelliJIdea*" |
    Where-Object {
      Test-Path -LiteralPath (Join-Path $_.FullName "aia-task-history")
    } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

  if (-not $candidate) {
    throw "No IntelliJ IDEA task-history folder found under $root"
  }

  return $candidate.Name
}

function ConvertFrom-EventLine {
  param([string]$Line)

  if ([string]::IsNullOrWhiteSpace($Line)) {
    return $null
  }

  try {
    $decoded = [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($Line.Trim()))
    return [PSCustomObject]@{
      Decoded = $decoded
      Json = $decoded | ConvertFrom-Json
    }
  } catch {
    return $null
  }
}

function Get-EventText {
  param($Json)

  if ($Json.prompt) {
    return [string]$Json.prompt
  }

  if ($Json.event) {
    if ($Json.event.text) {
      return [string]$Json.event.text
    }
    if ($Json.event.result) {
      return [string]$Json.event.result
    }
    if ($Json.event.message) {
      return [string]$Json.event.message
    }
  }

  return $null
}

function Format-Snippet {
  param(
    [string]$Text,
    [int]$Max = 140
  )

  if (-not $Text) {
    return ""
  }

  $flat = ($Text -replace "\s+", " ").Trim()
  if ($flat.Length -le $Max) {
    return $flat
  }

  return $flat.Substring(0, $Max - 3) + "..."
}

function Read-ChatFile {
  param([System.IO.FileInfo]$File)

  $sectionsByKey = @{}
  $sections = New-Object System.Collections.Generic.List[object]
  $firstPrompt = $null
  $lastPrompt = $null
  $compactErrors = New-Object System.Collections.Generic.List[string]
  $order = 0

  foreach ($line in Get-Content -LiteralPath $File.FullName) {
    $order++
    $eventLine = ConvertFrom-EventLine -Line $line
    if (-not $eventLine) {
      continue
    }

    $json = $eventLine.Json
    $text = Get-EventText -Json $json

    if ($text -and ($text -like "*remote compact task*" -or $text -like "*backend-api/codex/responses/compact*")) {
      $compactErrors.Add((Format-Snippet -Text $text -Max 500))
    }

    if ($json.prompt) {
      if (-not $firstPrompt) {
        $firstPrompt = [string]$json.prompt
      }
      $lastPrompt = [string]$json.prompt
      $sections.Add([PSCustomObject]@{
        Order = $order
        Role = "User"
        Text = [string]$json.prompt
      })
      continue
    }

    if ($json.event -and $text) {
      $kind = [string]$json.event.kind
      $stepId = [string]$json.event.stepId
      if (-not $stepId) {
        $stepId = "event-$order"
      }

      $key = "$kind-$stepId"
      if (-not $sectionsByKey.ContainsKey($key)) {
        $section = [PSCustomObject]@{
          Order = $order
          Role = "Codex"
          Text = $text
        }
        $sectionsByKey[$key] = $section
        $sections.Add($section)
      } else {
        $sectionsByKey[$key].Text = $text
      }
    }
  }

  return [PSCustomObject]@{
    ChatId = [IO.Path]::GetFileNameWithoutExtension($File.Name)
    File = $File
    LastWriteTime = $File.LastWriteTime
    Length = $File.Length
    FirstPrompt = $firstPrompt
    LastPrompt = $lastPrompt
    HasCompactError = $compactErrors.Count -gt 0
    CompactErrors = $compactErrors
    Sections = ($sections | Sort-Object Order)
  }
}

function Get-ChatSummaries {
  param(
    [string]$HistoryDir,
    [int]$RecentDays
  )

  $since = (Get-Date).AddDays(-1 * $RecentDays)
  Get-ChildItem -LiteralPath $HistoryDir -File -Filter "*.events" |
    Where-Object { $_.LastWriteTime -ge $since } |
    Sort-Object LastWriteTime -Descending |
    ForEach-Object { Read-ChatFile -File $_ }
}

function Export-Chat {
  param(
    $Chat,
    [string]$OutputRoot
  )

  New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null

  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $exportDir = Join-Path $OutputRoot "$stamp-$($Chat.ChatId)"
  New-Item -ItemType Directory -Path $exportDir -Force | Out-Null

  $rawPath = Join-Path $exportDir "$($Chat.ChatId).events.raw.b64"
  $mdPath = Join-Path $exportDir "$($Chat.ChatId).recovered.md"
  $promptPath = Join-Path $exportDir "continue-from-this-chat.md"

  Copy-Item -LiteralPath $Chat.File.FullName -Destination $rawPath -Force

  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add("# Recovered IntelliJ Codex Chat")
  $lines.Add("")
  $lines.Add("Chat ID: ``$($Chat.ChatId)``")
  $lines.Add("Source: ``$($Chat.File.FullName)``")
  $lines.Add("Recovered: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz')")
  $lines.Add("")

  if ($Chat.CompactErrors.Count -gt 0) {
    $lines.Add("## Compact Errors")
    $lines.Add("")
    foreach ($errorText in $Chat.CompactErrors) {
      $lines.Add("- $errorText")
    }
    $lines.Add("")
  }

  foreach ($section in $Chat.Sections) {
    if (-not $section.Text) {
      continue
    }
    $lines.Add("## $($section.Role)")
    $lines.Add("")
    $lines.Add($section.Text)
    $lines.Add("")
  }

  Set-Content -LiteralPath $mdPath -Value $lines -Encoding UTF8

  $usableSections = @($Chat.Sections |
    Where-Object {
      $_.Text -and
      $_.Text -notlike "*Error running remote compact task*" -and
      $_.Text -notlike "*backend-api/codex/responses/compact*"
    } |
    Select-Object -Last 8)

  $prompt = New-Object System.Collections.Generic.List[string]
  $prompt.Add("# Continue From Recovered IntelliJ Codex Chat")
  $prompt.Add("")
  $prompt.Add("Paste everything below this line into a new IntelliJ AI Chat with Codex.")
  $prompt.Add("")
  $prompt.Add("---")
  $prompt.Add("")
  $prompt.Add("I need to continue a previous IntelliJ Codex chat that failed during remote compacting.")
  $prompt.Add("")
  $prompt.Add("Do not try to continue the old broken chat. Continue from this recovered context.")
  $prompt.Add("")
  $prompt.Add("Workspace:")
  $prompt.Add("")
  $prompt.Add("Use the project currently open in IntelliJ IDEA.")
  $prompt.Add("")
  $prompt.Add("Recovered transcript file:")
  $prompt.Add("")
  $prompt.Add("``$mdPath``")
  $prompt.Add("")
  $prompt.Add("The latest useful context from the recovered chat is below.")
  $prompt.Add("")

  foreach ($section in $usableSections) {
    $prompt.Add("## $($section.Role)")
    $prompt.Add("")
    $prompt.Add($section.Text)
    $prompt.Add("")
  }

  $prompt.Add("Please inspect the project files as needed and continue from this context.")

  Set-Content -LiteralPath $promptPath -Value $prompt -Encoding UTF8

  return [PSCustomObject]@{
    ExportDir = $exportDir
    RawPath = $rawPath
    RecoveredMarkdown = $mdPath
    ContinuePrompt = $promptPath
  }
}

function Reset-CodexCache {
  param(
    [string]$LocalBase,
    [string]$RoamingBase,
    [string]$HistoryDir,
    [string]$BackupParent,
    [string]$Model,
    [bool]$IncludeAuth
  )

  $ideaProcess = Get-Process -ErrorAction SilentlyContinue |
    Where-Object { $_.ProcessName -match "idea64|idea|IntelliJ" }

  if ($ideaProcess) {
    Write-Host "IntelliJ IDEA appears to be running. Close IntelliJ first, then rerun with -ResetCache."
    return $null
  }

  $codexHome = Join-Path $LocalBase "aia\codex"
  if (-not (Test-Path -LiteralPath $codexHome)) {
    throw "Codex home not found: $codexHome"
  }

  $stamp = Get-Date -Format "yyyyMMdd-HHmmss"
  $backupRoot = Join-Path $BackupParent "jetbrains-codex-reset-backup-$stamp"
  New-Item -ItemType Directory -Path $backupRoot -Force | Out-Null

  if (Test-Path -LiteralPath $HistoryDir) {
    $historyBackup = Join-Path $backupRoot "aia-task-history"
    New-Item -ItemType Directory -Path $historyBackup -Force | Out-Null
    Get-ChildItem -LiteralPath $HistoryDir -File |
      Where-Object { $_.LastWriteTime -gt (Get-Date).AddDays(-14) } |
      Copy-Item -Destination $historyBackup -Force
  }

  $targets = @("models_cache.json", "cache", ".tmp")
  if ($IncludeAuth) {
    $targets += "auth.json"
  }

  foreach ($name in $targets) {
    $path = Join-Path $codexHome $name
    if (Test-Path -LiteralPath $path) {
      Move-Item -LiteralPath $path -Destination (Join-Path $backupRoot $name) -Force
      Write-Host "Moved $path"
    }
  }

  $settingsFile = Join-Path $RoamingBase "options\acpAgents.xml"
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
      $codexModelEntry.value = $Model
      $settings.Save($settingsFile)
      Write-Host "Changed saved Codex model from $oldModel to $Model"
    }
  }

  return $backupRoot
}

$product = Get-IntellijProduct -RequestedProduct $IdeProduct
$localBase = Join-Path $env:LOCALAPPDATA "JetBrains\$product"
$roamingBase = Join-Path $env:APPDATA "JetBrains\$product"
$historyDir = Join-Path $localBase "aia-task-history"
$outputRoot = Join-Path (Split-Path -Parent $PSCommandPath) "exports"

if (-not (Test-Path -LiteralPath $historyDir)) {
  throw "Task history not found: $historyDir"
}

$chats = @(Get-ChatSummaries -HistoryDir $historyDir -RecentDays $Days)

if ($List) {
  $chats |
    Select-Object ChatId, LastWriteTime, @{Name="MB"; Expression={"{0:N2}" -f ($_.Length / 1MB)}}, HasCompactError, @{Name="FirstPrompt"; Expression={Format-Snippet -Text $_.FirstPrompt -Max 90}} |
    Format-Table -AutoSize
}

$selected = $null
if ($ChatId) {
  $selected = $chats | Where-Object { $_.ChatId -eq $ChatId -or $_.ChatId -like "$ChatId*" } | Select-Object -First 1
  if (-not $selected) {
    throw "No chat found matching ChatId: $ChatId"
  }
} elseif ($Latest) {
  $selected = $chats | Select-Object -First 1
} elseif ($LatestBroken -or -not $List) {
  $selected = $chats | Where-Object { $_.HasCompactError } | Select-Object -First 1
}

if (-not $selected -and -not $List) {
  throw "No matching chat found. Try: .\recover-intellij-codex-chat.ps1 -List"
}

if ($selected) {
  $export = Export-Chat -Chat $selected -OutputRoot $outputRoot
  Write-Host ""
  Write-Host "Recovered chat:"
  Write-Host $export.RecoveredMarkdown
  Write-Host ""
  Write-Host "Paste this into a new Codex chat:"
  Write-Host $export.ContinuePrompt
}

if ($ResetCache) {
  Write-Host ""
  Write-Host "Resetting regenerable Codex cache..."
  $backup = Reset-CodexCache -LocalBase $localBase -RoamingBase $roamingBase -HistoryDir $historyDir -BackupParent (Split-Path -Parent $PSCommandPath) -Model $DefaultModel -IncludeAuth:$ResetAuth
  if ($backup) {
    Write-Host ""
    Write-Host "Cache reset backup:"
    Write-Host $backup
  }
}

if (-not $List -and -not $selected -and -not $ResetCache) {
  Write-Host "Nothing to do. Try: .\recover-intellij-codex-chat.ps1 -LatestBroken"
}
