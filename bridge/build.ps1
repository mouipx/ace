param(
    [string]$OutputDir = $PSScriptRoot
)

$ErrorActionPreference = "Stop"

function Fail([string]$Message) {
    Write-Error $Message
    exit 1
}

Write-Host "============================================"
Write-Host " Raw Accel Studio - bridge build"
Write-Host "============================================"
Write-Host ""

$vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
if (-not (Test-Path -LiteralPath $vswhere)) {
    Fail "Visual Studio Installer was not found. Install Visual Studio Build Tools with Desktop development with C++."
}

$vsInstallDir = @(& $vswhere -products "*" -property installationPath 2>$null) |
    ForEach-Object { $_.Trim() } |
    Where-Object { $_ -and (Test-Path -LiteralPath (Join-Path $_ "VC\Auxiliary\Build\vcvars64.bat")) } |
    Select-Object -First 1
if ([string]::IsNullOrWhiteSpace($vsInstallDir)) {
    Fail "No Visual Studio installation with VC\Auxiliary\Build\vcvars64.bat was found."
}
Write-Host "Found Visual Studio: $vsInstallDir"

$vcvars = Join-Path $vsInstallDir "VC\Auxiliary\Build\vcvars64.bat"

$envDump = & cmd /s /c "`"$vcvars`" >nul && set"
if ($LASTEXITCODE -ne 0) {
    Fail "vcvars64.bat failed."
}
foreach ($line in $envDump) {
    $equals = $line.IndexOf("=")
    if ($equals -gt 0) {
        [Environment]::SetEnvironmentVariable(
            $line.Substring(0, $equals),
            $line.Substring($equals + 1),
            "Process"
        )
    }
}

$cl = (Get-Command cl.exe -ErrorAction SilentlyContinue).Source
if ([string]::IsNullOrWhiteSpace($cl)) {
    Fail "cl.exe (x64) was not found. Install the MSVC C++ x64 build tools."
}
Write-Host "Using compiler: $cl"

if ([string]::IsNullOrWhiteSpace($env:WindowsSdkDir)) {
    Fail "No Windows SDK was detected by vcvars64."
}

$sdkIncludeRoot = Join-Path $env:WindowsSdkDir "Include"
$sdkLibRoot = Join-Path $env:WindowsSdkDir "Lib"
$goodSdk = Get-ChildItem -LiteralPath $sdkIncludeRoot -Directory |
    Where-Object {
        (Test-Path -LiteralPath (Join-Path $_.FullName "um\Windows.h")) -and
        (Test-Path -LiteralPath (Join-Path $_.FullName "ucrt\corecrt.h")) -and
        (Test-Path -LiteralPath (Join-Path $sdkLibRoot "$($_.Name)\um\x64\kernel32.lib"))
    } |
    Sort-Object Name |
    Select-Object -Last 1

if ($null -eq $goodSdk) {
    Write-Host ""
    Write-Host "Installed SDK include folders:"
    Get-ChildItem -LiteralPath $sdkIncludeRoot -Directory | ForEach-Object {
        $windows = if (Test-Path -LiteralPath (Join-Path $_.FullName "um\Windows.h")) { "Windows.h OK" } else { "missing um\Windows.h" }
        $ucrt = if (Test-Path -LiteralPath (Join-Path $_.FullName "ucrt\corecrt.h")) { "UCRT OK" } else { "missing ucrt\corecrt.h" }
        $kernel32 = if (Test-Path -LiteralPath (Join-Path $sdkLibRoot "$($_.Name)\um\x64\kernel32.lib")) { "kernel32 OK" } else { "missing x64 kernel32.lib" }
        Write-Host "  $($_.Name) - $windows, $ucrt, $kernel32"
    }
    Fail "No complete Windows SDK was found. Reinstall the Windows 10/11 SDK in Visual Studio Installer."
}

$sdkVersion = $goodSdk.Name
$env:INCLUDE = @(
    (Join-Path $sdkIncludeRoot "$sdkVersion\ucrt"),
    (Join-Path $sdkIncludeRoot "$sdkVersion\um"),
    (Join-Path $sdkIncludeRoot "$sdkVersion\shared"),
    $env:INCLUDE
) -join ";"
$env:LIB = @(
    (Join-Path $sdkLibRoot "$sdkVersion\ucrt\x64"),
    (Join-Path $sdkLibRoot "$sdkVersion\um\x64"),
    $env:LIB
) -join ";"
Write-Host "Windows SDK in use: $sdkVersion"

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

$source = Join-Path $PSScriptRoot "rawaccel_bridge.cpp"
$dll = Join-Path $OutputDir "rawaccel_bridge.dll"
$obj = Join-Path $OutputDir "rawaccel_bridge.obj"
$lib = Join-Path $OutputDir "rawaccel_bridge.lib"

Write-Host ""
Write-Host "Compiling rawaccel_bridge.dll ..."
Write-Host ""

& $cl `
    /nologo `
    /std:c++17 `
    /EHsc `
    /O2 `
    /LD `
    /DRA_BRIDGE_EXPORTS `
    /I $PSScriptRoot `
    $source `
    "/Fo$obj" `
    "/Fe$dll" `
    /link kernel32.lib "/IMPLIB:$lib"

if ($LASTEXITCODE -ne 0) {
    Fail "Build failed; compiler reported errors."
}
if (-not (Test-Path -LiteralPath $dll)) {
    Fail "Build failed; rawaccel_bridge.dll was not produced."
}

Write-Host ""
Write-Host "============================================"
Write-Host " SUCCESS"
Write-Host " Created: $dll"
Write-Host "============================================"
