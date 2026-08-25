[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"

if (Get-Process RuneLite -ErrorAction SilentlyContinue)
{
    throw "Close RuneLite before installing GenericClient."
}

$runeliteDirectory = Join-Path $env:LOCALAPPDATA "RuneLite"
$configPath = Join-Path $runeliteDirectory "config.json"
$backupPath = Join-Path $runeliteDirectory "config.stock.json"
$installDirectory = Join-Path $env:LOCALAPPDATA "GenericClient"
$jarPath = Join-Path $installDirectory "GenericClient.jar"
$downloadPath = Join-Path $env:TEMP "GenericClient.download.jar"
$downloadUrl = "https://github.com/JarrettOneSource/GenericClient/releases/latest/download/GenericClient.jar"

if (-not (Test-Path $configPath))
{
    throw "RuneLite is not installed at $runeliteDirectory."
}

New-Item -ItemType Directory -Path $installDirectory -Force | Out-Null
Invoke-WebRequest -Uri $downloadUrl -OutFile $downloadPath
Move-Item -Path $downloadPath -Destination $jarPath -Force

if (-not (Test-Path $backupPath))
{
    Copy-Item -Path $configPath -Destination $backupPath
}

$config = Get-Content -Raw $configPath | ConvertFrom-Json
$config.classPath = @("../GenericClient/GenericClient.jar")
$config.mainClass = "com.genericclient.GenericClientLauncher"
$vmArgs = @($config.vmArgs)
if ($vmArgs -notcontains "-ea")
{
    $vmArgs += "-ea"
}
$config.vmArgs = $vmArgs

$json = $config | ConvertTo-Json -Depth 20
$utf8 = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($configPath, $json, $utf8)

Write-Host "GenericClient installed. Launch RuneLite from the Jagex Launcher."
