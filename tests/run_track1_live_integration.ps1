param(
    [Parameter(Mandatory = $true)]
    [string]$JarPath,
    [string]$GradleUserHome
)

$ErrorActionPreference = 'Stop'
$backend = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$workspace = (Resolve-Path -LiteralPath (Join-Path $backend '..')).Path
$data = Join-Path $workspace 'ticket-defender-data'
$composeFile = Join-Path $data 'tests\docker-compose.gds.yml'
$python = Join-Path $data 'venv\Scripts\python.exe'
$gradle = Join-Path $backend 'gradlew.bat'
$jar = (Resolve-Path -LiteralPath $JarPath).Path

if (-not (Test-Path -LiteralPath $composeFile -PathType Leaf) -or
        -not (Test-Path -LiteralPath $python -PathType Leaf) -or
        -not (Test-Path -LiteralPath $gradle -PathType Leaf)) {
    throw 'Required workspace files are unavailable'
}
if ((Get-Item -LiteralPath $jar).Length -lt 1000000) {
    throw 'GDS test JAR is invalid'
}

$project = 'tdissue18gds' + [guid]::NewGuid().ToString('N').Substring(0, 10)
$logs = Join-Path $backend 'build\track1-live'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$previous = @{}
foreach ($name in @('TD_GDS_TEST_JAR', 'TD_GDS_TEST_PASSWORD', 'NEO4J_URI',
        'NEO4J_USER', 'NEO4J_PASSWORD', 'FASTAPI_URL', 'TD_TRACK1_FASTAPI_URL',
        'GRADLE_USER_HOME')) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

$fastApi = $null
$composeStarted = $false
try {
    $env:TD_GDS_TEST_JAR = $jar
    $env:TD_GDS_TEST_PASSWORD = [guid]::NewGuid().ToString('N')
    if ($GradleUserHome) {
        $env:GRADLE_USER_HOME = (Resolve-Path -LiteralPath $GradleUserHome).Path
    }

    docker compose -p $project -f $composeFile config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Isolated Compose configuration is invalid' }
    $composeStarted = $true
    docker compose -p $project -f $composeFile up -d --pull never
    if ($LASTEXITCODE -ne 0) { throw 'Isolated Neo4j startup failed' }

    $portText = docker compose -p $project -f $composeFile port neo4j 7687
    $portMatch = [regex]::Match($portText, '^127\.0\.0\.1:([1-9][0-9]*)$')
    if ($LASTEXITCODE -ne 0 -or -not $portMatch.Success -or
            [int]$portMatch.Groups[1].Value -eq 7687) {
        throw 'Isolated Bolt port is not a dedicated loopback port'
    }
    $env:NEO4J_URI = "bolt://127.0.0.1:$($portMatch.Groups[1].Value)"
    $env:NEO4J_USER = 'neo4j'
    $env:NEO4J_PASSWORD = $env:TD_GDS_TEST_PASSWORD

    $checkGds = 'import os; from neo4j import GraphDatabase; d=GraphDatabase.driver(os.environ[''NEO4J_URI''],auth=(os.environ[''NEO4J_USER''],os.environ[''NEO4J_PASSWORD''])); d.verify_connectivity(); v=d.execute_query(''RETURN gds.version() AS v'').records[0][''v'']; assert v == ''2.6.6'', v; d.close()'
    $ready = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        try {
            & $python -B -c $checkGds 2>$null
            if ($LASTEXITCODE -eq 0) {
                $ready = $true
                break
            }
        } catch {}
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw 'Isolated Neo4j/GDS did not become ready' }

    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $httpPort = $listener.LocalEndpoint.Port
    $listener.Stop()
    if ($httpPort -le 0) { throw 'FastAPI loopback port is invalid' }
    $env:FASTAPI_URL = "http://127.0.0.1:$httpPort"
    $env:TD_TRACK1_FASTAPI_URL = $env:FASTAPI_URL
    $fastApi = Start-Process -FilePath $python -ArgumentList @(
        '-B', '-m', 'uvicorn', 'main:app', '--host', '127.0.0.1', '--port', "$httpPort"
    ) -WorkingDirectory $data -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $logs "$project-fastapi-out.log") `
        -RedirectStandardError (Join-Path $logs "$project-fastapi-err.log")

    $ready = $false
    for ($attempt = 0; $attempt -lt 40; $attempt++) {
        if ($fastApi.HasExited) { break }
        try {
            $health = Invoke-WebRequest -Uri "$env:FASTAPI_URL/openapi.json" -UseBasicParsing -TimeoutSec 2
            if ($health.StatusCode -eq 200) {
                $ready = $true
                break
            }
        } catch {}
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) { throw 'Isolated FastAPI server did not become ready' }

    Write-Output "gds_project=$project"
    Write-Output "gds_version=2.6.6"
    Write-Output "fastapi_url=$env:FASTAPI_URL"
    Push-Location $backend
    try {
        & $gradle test --offline --tests com.ticket.defender_core.integration.Track1LiveIntegrationTest --rerun-tasks
        if ($LASTEXITCODE -ne 0) { throw 'Live Spring-FastAPI-GDS-H2 integration test failed' }
        [xml]$result = Get-Content -LiteralPath (Join-Path $backend 'build\test-results\test\TEST-com.ticket.defender_core.integration.Track1LiveIntegrationTest.xml') -Raw -Encoding UTF8
        if ([int]$result.testsuite.tests -ne 2 -or [int]$result.testsuite.skipped -ne 0 -or
                [int]$result.testsuite.failures -ne 0 -or [int]$result.testsuite.errors -ne 0) {
            throw 'Live integration tests did not execute and pass'
        }
    }
    finally {
        Pop-Location
    }
}
finally {
    if ($fastApi -and -not $fastApi.HasExited) {
        Stop-Process -Id $fastApi.Id -Force
        $fastApi.WaitForExit()
    }
    if ($composeStarted) {
        docker compose -p $project -f $composeFile down --volumes --timeout 15
    }
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
