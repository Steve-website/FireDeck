# FireDeck startup installer
#
# Registers scheduled tasks so FireDeck runs in the background at logon:
#   1. "FireDeck Server"               - pythonw.exe, hidden, auto-restart
#   2. "FireDeck LibreHardwareMonitor" - needs Administrator (CPU temps)
#
# Run normally for task 1. Run as Administrator for both.

$ErrorActionPreference = "Continue"

$serverDir = $PSScriptRoot
$root = Split-Path -Parent $serverDir
$user = "$env:USERDOMAIN\$env:USERNAME"

# --- locate pythonw.exe ------------------------------------------------------
$py = Join-Path $env:USERPROFILE "miniconda3\pythonw.exe"
if (-not (Test-Path $py)) {
    $cmd = Get-Command pythonw.exe -ErrorAction SilentlyContinue
    if ($cmd) { $py = $cmd.Source }
}
if (-not $py) {
    Write-Warning "pythonw.exe not found - install Python or edit this script."
} else {
    $action = New-ScheduledTaskAction -Execute $py `
        -Argument "`"$serverDir\deck_server.py`" --port 9100" `
        -WorkingDirectory $serverDir
    $trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
    $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Limited
    $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries `
        -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew `
        -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
        -ExecutionTimeLimit ([TimeSpan]::Zero)

    Register-ScheduledTask -TaskName "FireDeck Server" -Action $action `
        -Trigger $trigger -Principal $principal -Settings $settings `
        -Description "FireDeck control server (hidden, port 9100)" -Force | Out-Null
    Write-Host "OK    FireDeck Server task registered"

    if (-not (Get-NetTCPConnection -LocalPort 9100 -State Listen -ErrorAction SilentlyContinue)) {
        Start-ScheduledTask -TaskName "FireDeck Server"
        Write-Host "OK    FireDeck Server started"
    }
}

# --- LibreHardwareMonitor (admin only) --------------------------------------
$lhmDir = Join-Path $root "tools\LibreHardwareMonitor"
$lhm = Join-Path $lhmDir "LibreHardwareMonitor.exe"
if (Test-Path $lhm) {
    $isAdmin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
        [Security.Principal.WindowsBuiltInRole]::Administrator)
    if ($isAdmin) {
        $action = New-ScheduledTaskAction -Execute $lhm -WorkingDirectory $lhmDir
        $trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
        $principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest
        $settings = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries `
            -DontStopIfGoingOnBatteries -StartWhenAvailable -MultipleInstances IgnoreNew `
            -ExecutionTimeLimit ([TimeSpan]::Zero)

        Register-ScheduledTask -TaskName "FireDeck LibreHardwareMonitor" -Action $action `
            -Trigger $trigger -Principal $principal -Settings $settings `
            -Description "LibreHardwareMonitor for FireDeck CPU temperatures" -Force | Out-Null
        Write-Host "OK    LibreHardwareMonitor task registered"

        if (-not (Get-Process LibreHardwareMonitor -ErrorAction SilentlyContinue)) {
            Start-ScheduledTask -TaskName "FireDeck LibreHardwareMonitor"
            Write-Host "OK    LibreHardwareMonitor started"
        }
    } else {
        Write-Warning "Run this script as Administrator to auto-start LibreHardwareMonitor (CPU temps)."
    }
}
