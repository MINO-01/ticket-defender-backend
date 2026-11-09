param([switch]$Spring)
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'preflight.ps1')
if (-not $Spring) { Write-Output 'Preflight passed. Start existing MySQL, Neo4j/GDS, MinIO, Keycloak, FastAPI and Frontend with their owner-managed configuration; use -Spring to run only this backend.'; exit 0 }
$root = Split-Path $PSScriptRoot -Parent
$env:GRADLE_USER_HOME = Join-Path $root '.gradle-demo'
Push-Location $root
try { & .\gradlew.bat bootRun } finally { Pop-Location }
