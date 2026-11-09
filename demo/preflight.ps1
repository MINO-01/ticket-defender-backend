param([switch]$RequireRunning)
$ErrorActionPreference = 'Stop'

$required = @('DB_URL','DB_USERNAME','DB_PASSWORD','FASTAPI_URL','TRACK1_INTERNAL_SHARED_SECRET',
  'TRACK1_V2_ENABLED','OFFICIAL_PDF_ENABLED','OFFICIAL_PDF_DEMO_V1_ENABLED',
  'OFFICIAL_PDF_OPERATIONAL_MODE','OFFICIAL_PDF_SYNTHETIC_ISOLATION_ID',
  'TRACK2_CANDIDATE_IMPORT_ENABLED','TRACK2_CANDIDATE_IMPORT_OPERATIONAL_MODE',
  'TRACK2_CANDIDATE_IMPORT_SYNTHETIC_ISOLATION_ID','TRACK2_CANDIDATE_IMPORT_REFERENCE_HMAC_MASTER_KEY',
  'TRACK2_CANDIDATE_IMPORT_REFERENCE_HMAC_KEY_VERSION')
$missing = $required | Where-Object { [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($_)) }
if ($missing) { throw "Missing required demo environment variables: $($missing -join ', ')" }
if ($env:TRACK1_V2_ENABLED -ne 'true' -or $env:OFFICIAL_PDF_ENABLED -ne 'true' -or $env:OFFICIAL_PDF_DEMO_V1_ENABLED -ne 'true') {
  throw 'TRACK1_V2_ENABLED, OFFICIAL_PDF_ENABLED and OFFICIAL_PDF_DEMO_V1_ENABLED must all be true.'
}
if ($env:OFFICIAL_PDF_OPERATIONAL_MODE -ne 'SYNTHETIC_ISOLATED' -or $env:TRACK2_CANDIDATE_IMPORT_OPERATIONAL_MODE -ne 'SYNTHETIC_ISOLATED') {
  throw 'Both operational modes must be SYNTHETIC_ISOLATED.'
}
if ($env:OFFICIAL_PDF_SYNTHETIC_ISOLATION_ID -ne $env:TRACK2_CANDIDATE_IMPORT_SYNTHETIC_ISOLATION_ID) {
  throw 'Official PDF and candidate-import isolation IDs must be identical.'
}
foreach ($command in 'java','docker') { if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Required command unavailable: $command" } }
if ($RequireRunning) {
  foreach ($url in @($env:FASTAPI_URL, $env:FRONTEND_URL)) {
    if (-not [string]::IsNullOrWhiteSpace($url)) { try { Invoke-WebRequest -UseBasicParsing -TimeoutSec 5 -Uri $url | Out-Null } catch { throw "Endpoint is unavailable: $url" } }
  }
}
Write-Output 'Demo preflight passed. No data, container, or volume was created or changed.'
