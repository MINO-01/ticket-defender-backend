# Track 1 분석 결과가 Case 출처로 연결되는 전체 흐름을 합성 격리 환경에서 실행합니다.
# 이 스크립트가 만든 MySQL 컨테이너와 Neo4j Compose 프로젝트만 정리하며 기존 컨테이너와 볼륨은 건드리지 않습니다.
param(
    [Parameter(Mandatory = $true)]
    [string]$JarPath,
    [string]$GradleUserHome,
    [string]$DataDirectory
)

$ErrorActionPreference = 'Stop'

function Get-RequiredTrack1IntegrationSecret {
    $secret = [Environment]::GetEnvironmentVariable('TRACK1_INTERNAL_SHARED_SECRET', 'Process')
    if ([string]::IsNullOrWhiteSpace($secret) -or
            [System.Text.Encoding]::UTF8.GetByteCount($secret) -lt 32 -or
            [regex]::IsMatch($secret, '[\x00-\x1F\x7F]')) {
        throw 'Track 1 integration authentication configuration is invalid'
    }
    return $secret
}

$track1SharedSecret = Get-RequiredTrack1IntegrationSecret
$backend = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$workspace = (Resolve-Path -LiteralPath (Join-Path $backend '..')).Path
if (-not $PSBoundParameters.ContainsKey('DataDirectory')) {
    $DataDirectory = Join-Path $workspace 'ticket-defender-data'
}
if ([string]::IsNullOrWhiteSpace($DataDirectory) -or
        -not (Test-Path -LiteralPath $DataDirectory -PathType Container)) {
    throw 'Data directory is unavailable: specify a valid -DataDirectory'
}
$data = (Resolve-Path -LiteralPath $DataDirectory).Path
$composeFile = Join-Path $data 'tests\docker-compose.gds.yml'
$python = Join-Path $data 'venv\Scripts\python.exe'
$gradle = Join-Path $backend 'gradlew.bat'
$jar = (Resolve-Path -LiteralPath $JarPath).Path
$mysqlImage = 'mysql:8.0.46'

foreach ($relativePath in @('main.py', 'api\routes\graph_routes.py',
        'core\dependencies.py', 'tests\docker-compose.gds.yml',
        'venv\Scripts\python.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $data $relativePath) -PathType Leaf)) {
        throw "Required Data file is unavailable: $relativePath"
    }
}
if ((Get-Item -LiteralPath $jar).Length -lt 1000000) {
    throw 'GDS test JAR is invalid'
}

function Get-FreeLoopbackPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    $port = $listener.LocalEndpoint.Port
    $listener.Stop()
    if ($port -le 0) { throw 'Loopback port is invalid' }
    return $port
}

# 테스트 클래스 하나를 실행하고 JUnit 결과에서 실행 수와 Skip 0건을 확인합니다.
function Invoke-RequiredSuite([string]$pattern, [string]$suiteProfile) {
    & $gradle test --tests $pattern --rerun-tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle suite failed: $pattern" }
    & $python -B (Join-Path $backend '.github\scripts\check_junit_results.py') $suiteProfile `
        --results (Join-Path $backend 'build\test-results\test')
    if ($LASTEXITCODE -ne 0) { throw "Required suite did not run cleanly: $suiteProfile" }
}

$suffix = [guid]::NewGuid().ToString('N').Substring(0, 10)
$project = 'tdissue62gds' + $suffix
$mysqlContainer = 'td-issue62-mysql-' + $suffix
$logs = Join-Path $backend 'build\track1-case-link-live'
New-Item -ItemType Directory -Force -Path $logs | Out-Null
$names = @('TD_GDS_TEST_JAR', 'TD_GDS_TEST_PASSWORD', 'NEO4J_URI', 'NEO4J_USER', 'NEO4J_PASSWORD',
    'FASTAPI_URL', 'TD_TRACK1_FASTAPI_URL', 'TRACK1_INTERNAL_SHARED_SECRET', 'GRADLE_USER_HOME', 'TD_ISSUE32_MYSQL_URL',
    'TD_ISSUE32_MYSQL_USER', 'TD_ISSUE32_MYSQL_PASSWORD', 'TD_MYSQL_MIGRATION_URL',
    'TD_MYSQL_MIGRATION_USER', 'TD_MYSQL_MIGRATION_PASSWORD')
$previous = @{}
foreach ($name in $names) {
    $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

$fastApi = $null
$composeStarted = $false
$mysqlStarted = $false
try {
    $env:TRACK1_INTERNAL_SHARED_SECRET = $track1SharedSecret
    if ($GradleUserHome) {
        $env:GRADLE_USER_HOME = (Resolve-Path -LiteralPath $GradleUserHome).Path
    }

    # 1. 이 실행 전용 MySQL 8 컨테이너를 띄웁니다.
    # 기존 마이그레이션 테스트는 127.0.0.1:13307 과 td_issue32 접두사 DB에서만 실행되므로 같은 조건을 사용합니다.
    $mysqlPassword = [guid]::NewGuid().ToString('N')
    $mysqlPort = 13307
    $probe = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, $mysqlPort)
    try { $probe.Start() } catch { throw 'Port 13307 is already in use: stop and keep the other test MySQL first' }
    $probe.Stop()
    docker run -d --name $mysqlContainer -e "MYSQL_ROOT_PASSWORD=$mysqlPassword" `
        -e MYSQL_DATABASE=td_issue32_issue62_e2e -p "127.0.0.1:${mysqlPort}:3306" $mysqlImage | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'Isolated MySQL startup failed' }
    $mysqlStarted = $true
    $ready = $false
    for ($attempt = 0; $attempt -lt 90; $attempt++) {
        try {
            docker exec -e "MYSQL_PWD=$mysqlPassword" $mysqlContainer mysql -h 127.0.0.1 -uroot `
                -e 'SELECT 1' td_issue32_issue62_e2e 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0) {
                $ready = $true
                break
            }
        } catch {}
        Start-Sleep -Seconds 2
    }
    if (-not $ready) { throw 'Isolated MySQL did not become ready' }
    $mysqlUrl = "jdbc:mysql://127.0.0.1:$mysqlPort/td_issue32_issue62_e2e?useSSL=false&allowPublicKeyRetrieval=true"
    $env:TD_ISSUE32_MYSQL_URL = $mysqlUrl
    $env:TD_ISSUE32_MYSQL_USER = 'root'
    $env:TD_ISSUE32_MYSQL_PASSWORD = $mysqlPassword

    # 2. CI와 같은 순서로 마이그레이션 테스트를 실행해 운영 스키마를 만듭니다.
    Push-Location $backend
    try {
        foreach ($suite in @(
                @('*FanReportMigrationMySqlTest', 'mysql-migration'),
                @('*CaseMigrationMySqlTest', 'case-migration'),
                @('*CaseW05MigrationMySqlTest', 'case-w05-migration'),
                @('*CaseW06MigrationMySqlTest', 'case-w06-migration'),
                @('*CaseW07MigrationMySqlTest', 'case-w07-migration'),
                @('*CaseW08aMigrationMySqlTest', 'case-w08a-migration'),
                @('*CaseW08bMigrationMySqlTest', 'case-w08b-migration'),
                @('*CaseW09MigrationMySqlTest', 'case-w09-migration'),
                @('*Track2ListingAnalysisMigrationMySqlTest', 'track2-listing-migration'),
                @('*AdminEvidenceContentMigrationMySqlTest', 'admin-evidence-content-migration'))) {
            Invoke-RequiredSuite $suite[0] $suite[1]
        }
    }
    finally {
        Pop-Location
    }

    # 3. 이 실행 전용 Neo4j GDS와 FastAPI를 띄웁니다.
    $env:TD_GDS_TEST_JAR = $jar
    $env:TD_GDS_TEST_PASSWORD = [guid]::NewGuid().ToString('N')
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

    $httpPort = Get-FreeLoopbackPort
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

    Write-Output "mysql_container=$mysqlContainer"
    Write-Output "gds_project=$project"
    Write-Output "gds_version=2.6.6"
    Write-Output "fastapi_url=$env:FASTAPI_URL"

    # 4. 마이그레이션된 MySQL에서 기존 Track 1 동시성 테스트와 새 E2E를 실행합니다.
    $env:TD_MYSQL_MIGRATION_URL = $mysqlUrl
    $env:TD_MYSQL_MIGRATION_USER = 'root'
    $env:TD_MYSQL_MIGRATION_PASSWORD = $mysqlPassword
    Push-Location $backend
    try {
        Invoke-RequiredSuite '*Track1ConcurrentMySqlTest' 'track1-concurrency'
        Invoke-RequiredSuite '*Track1CaseLinkLiveMySqlTest' 'track1-case-link-live'
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
    if ($mysqlStarted) {
        docker rm -f -v $mysqlContainer | Out-Null
    }
    foreach ($name in $previous.Keys) {
        [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process')
    }
}
