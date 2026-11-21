# Install resource-agent on Windows 11 (run in an elevated PowerShell)
#   .\install-windows.ps1 -Exe .\resource-agent-windows-amd64.exe -Config .\config.json
# NOTE: Keep this file ASCII-only. Windows PowerShell 5.1 reads BOM-less UTF-8 as Shift-JIS.
param(
  [Parameter(Mandatory)] [string] $Exe,
  [Parameter(Mandatory)] [string] $Config
)
$ErrorActionPreference = "Stop"
$dir = "C:\Program Files\ResourceAgent"
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Copy-Item $Exe    "$dir\resource-agent.exe" -Force
Copy-Item $Config "$dir\config.json" -Force
& "$dir\resource-agent.exe" -config "$dir\config.json" install   # register as a Windows service
& "$dir\resource-agent.exe" start
Get-Service resource-agent
Write-Host "Logs: Event Viewer > Windows Logs > Application (source: resource-agent)"
