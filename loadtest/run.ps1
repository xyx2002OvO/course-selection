param(
    [ValidateSet('submit', 'e2e', 'oversell', 'breaking', 'pipeline', 'ladder', 'correctness', 'latency-compare', 'all')]
    [string]$Scenario = 'all',
    [switch]$Rebuild,
    [switch]$SkipReset,
    [switch]$CompareConfirm,
    [int]$ProjectConcurrency = 6
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$compose = @('-f', 'compose.yaml', '-f', 'compose.loadtest.yaml')
if (-not $env:LOADTEST_OUT) { $env:LOADTEST_OUT = './loadtest/out' }
if (-not $env:PROJECT_CONCURRENCY) { $env:PROJECT_CONCURRENCY = "$ProjectConcurrency" }
if (-not $env:CONFIRM_CONCURRENCY) { $env:CONFIRM_CONCURRENCY = '6' }
if (-not $env:SUBMIT_GLOBAL) { $env:SUBMIT_GLOBAL = '150' }

function Wait-Healthy {
    $deadline = (Get-Date).AddMinutes(5)
    do {
        $api = docker inspect selection-demo-api-1 --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>$null
        $admission = docker inspect selection-demo-admission-1 --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>$null
        $worker = docker inspect selection-demo-worker-1 --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' 2>$null
        if ($api -eq 'healthy' -and $admission -eq 'healthy' -and $worker -eq 'healthy') { return }
        Start-Sleep -Seconds 3
    } while ((Get-Date) -lt $deadline)
    docker compose @compose ps -a
    throw "api/admission/worker not healthy (api=$api admission=$admission worker=$worker)"
}

function Start-Stack([switch]$Build) {
    Write-Output "Resetting loadtest stack (confirm=$($env:CONFIRM_CONCURRENCY) project=$($env:PROJECT_CONCURRENCY) submitGlobal=$($env:SUBMIT_GLOBAL))."
    cmd.exe /c "docker compose -f compose.yaml -f compose.loadtest.yaml --profile loadgen down -v"
    if ($LASTEXITCODE -ne 0) { throw 'Cannot reset loadtest stack' }
    $upCmd = 'docker compose -f compose.yaml -f compose.loadtest.yaml up -d'
    if ($Build) { $upCmd = 'docker compose -f compose.yaml -f compose.loadtest.yaml up --build -d' }
    cmd.exe /c $upCmd
    if ($LASTEXITCODE -ne 0) { throw 'Cannot build/start loadtest stack' }
    Wait-Healthy
}

function Save-Snapshot([string]$OutDir, [string]$Name) {
    $request = docker exec -e MYSQL_PWD=selection-dev selection-demo-mysql-1 mysql -uselection selection --table -e "SELECT state, COUNT(*) n FROM selection_request GROUP BY state"
    $outbox = docker exec -e MYSQL_PWD=selection-dev selection-demo-mysql-1 mysql -uselection selection --table -e "SELECT kind, state, COUNT(*) n FROM outbox_event GROUP BY kind, state"
    @($request + $outbox) | Set-Content (Join-Path $OutDir $Name)
    docker exec -e MYSQL_PWD=selection-dev selection-demo-mysql-1 mysql -uselection selection --batch --raw -e "SELECT state, TIMESTAMPDIFF(MICROSECOND, created_at, CASE WHEN state='SUCCESS' THEN updated_at ELSE CURRENT_TIMESTAMP(6) END)/1e6 AS wait_s FROM selection_request" |
        Set-Content (Join-Path $OutDir 'persist-wait.csv')
}

function Wait-Drain([int]$Seconds) {
    Write-Output "Waiting up to ${Seconds}s for ACCEPTED requests to drain."
    $deadline = (Get-Date).AddSeconds($Seconds)
    $pending = ''
    do {
        $pending = docker exec -e MYSQL_PWD=selection-dev selection-demo-mysql-1 mysql -uselection selection -N -e "SELECT COUNT(*) FROM selection_request WHERE state='ACCEPTED'"
        $pending = "$pending".Trim()
        if ($pending -eq '0') {
            Write-Output 'Drain complete: ACCEPTED=0.'
            return
        }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    Write-Output "Drain wait ended with ACCEPTED=$pending"
}

function Save-Invariants([string]$OutDir) {
    Get-Content (Join-Path $PSScriptRoot 'verify-results.sql') -Raw |
        docker exec -i -e MYSQL_PWD=selection-dev selection-demo-mysql-1 mysql -uselection selection --table |
        Set-Content (Join-Path $OutDir 'invariants.txt')
    docker exec -e MYSQL_PWD=selection-dev selection-demo-mysql-1 mysql -uselection selection --table -e "SELECT c.id,c.capacity,c.remaining,COUNT(e.request_id) enrolled, c.capacity-c.remaining-COUNT(e.request_id) drift FROM course c LEFT JOIN enrollment e ON e.course_id=c.id AND e.term_id=c.term_id WHERE c.id IN (201,202) OR (c.id BETWEEN 211 AND 230) GROUP BY c.id,c.capacity,c.remaining ORDER BY c.id; SELECT state,reason,COUNT(*) n FROM selection_request WHERE course_id=202 GROUP BY state,reason;" |
        Set-Content (Join-Path $OutDir 'stock-check.txt')
    docker exec selection-demo-redis-1 redis-cli GET 'selection:{202601}:stock:202' |
        Set-Content (Join-Path $OutDir 'redis-stock-202.txt')
}

function Invoke-Script([string]$Script, [string]$OutDir) {
    Write-Output "=== $Script -> $OutDir ==="
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $stopFlag = Join-Path $OutDir 'stop-watch'
    Remove-Item $stopFlag, (Join-Path $OutDir 'first-failure.json') -ErrorAction SilentlyContinue
    $watchers = @()
    if ($Script -eq 'breaking.js' -or $Script -eq 'pipeline.js' -or $Script -eq 'ladder.js' -or $Script -eq 'correctness.js' -or $Script -eq 'latency-compare.js') {
        $watchers += Start-Process -FilePath 'powershell.exe' -PassThru -WindowStyle Hidden -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass',
            '-File', "$PSScriptRoot\watch-instances.ps1",
            '-OutDir', $OutDir
        )
        $watchers += Start-Process -FilePath 'powershell.exe' -PassThru -WindowStyle Hidden -ArgumentList @(
            '-NoProfile', '-ExecutionPolicy', 'Bypass',
            '-File', "$PSScriptRoot\sample-database.ps1",
            '-OutDir', $OutDir
        )
    }
    $k6Args = "run --summary-export=/out/k6-summary.json /scripts/$Script"
    if ($Script -eq 'latency-compare.js') {
        $k6Args = "run --summary-export=/out/k6-summary.json --out csv=/out/http.csv /scripts/$Script"
    }
    $k6Log = Join-Path $OutDir 'k6.log'
    try {
        cmd.exe /c "docker compose -f compose.yaml -f compose.loadtest.yaml --profile loadgen run --rm --no-deps loadgen $k6Args > `"$k6Log`" 2>&1"
        Set-Content (Join-Path $OutDir 'k6-exit-code.txt') "$LASTEXITCODE"
        if (Test-Path $k6Log) { Get-Content $k6Log -Tail 35 }
    } catch {
        Write-Warning $_
        Set-Content (Join-Path $OutDir 'k6-error.txt') ($_ | Out-String)
    } finally {
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        New-Item -ItemType File -Force -Path $stopFlag | Out-Null
        Start-Sleep -Seconds 3
        foreach ($watch in $watchers) {
            Stop-Process -Id $watch.Id -Force -ErrorAction SilentlyContinue
        }
        if ($Script -eq 'correctness.js') { Wait-Drain 90 }
        Save-Snapshot $OutDir 'database-at-stop.txt'
        if ($Script -eq 'correctness.js') { Save-Invariants $OutDir }
        docker inspect selection-demo-admission-1 --format '{{json .Config.Env}}' |
            Set-Content (Join-Path $OutDir 'admission-env.txt')
        docker inspect selection-demo-worker-1 --format '{{json .Config.Env}}' |
            Set-Content (Join-Path $OutDir 'worker-env.txt')
        docker logs selection-demo-admission-1 --since 15m 2>&1 |
            Select-String -Pattern 'HikariPool|Connection is not available|SQLTimeout|Lock wait' |
            Set-Content (Join-Path $OutDir 'admission-wait-log.txt')
        docker logs selection-demo-worker-1 --since 15m 2>&1 |
            Select-String -Pattern 'HikariPool|Connection is not available|SQLTimeout|Lock wait' |
            Set-Content (Join-Path $OutDir 'worker-wait-log.txt')
        docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}} {{.MemPerc}}' `
            selection-demo-api-1 selection-demo-admission-1 selection-demo-worker-1 selection-demo-mysql-1 selection-demo-redis-1 selection-demo-kafka-1 |
            Set-Content (Join-Path $OutDir 'docker-stats.txt')
        $ErrorActionPreference = $previous
    }
    if (Test-Path (Join-Path $OutDir 'first-failure.json')) {
        Write-Output '--- First failed instance ---'
        Get-Content (Join-Path $OutDir 'first-failure.json') -Raw
    }
}

if ($CompareConfirm) {
    $Scenario = 'pipeline'
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $built = $false
    foreach ($confirm in @(6, 3)) {
        $env:CONFIRM_CONCURRENCY = "$confirm"
        $env:PROJECT_CONCURRENCY = "$ProjectConcurrency"
        $runId = "$stamp-confirm$confirm"
        $outDir = Join-Path $root "loadtest\out\$runId\pipeline"
        $env:LOADTEST_OUT = "./loadtest/out/$runId/pipeline"
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
        @{
            confirmConcurrency = $confirm
            projectConcurrency = $ProjectConcurrency
            httpTimeout = '1s'
            courseFrom = 211
            courseTo = 222
            warmup = '15s@50'
            steady = '60s@400'
        } | ConvertTo-Json | Set-Content (Join-Path $root "loadtest\out\$runId\run-metadata.json")
        Start-Stack -Build:($Rebuild -and -not $built)
        $built = $true
        Invoke-Script 'pipeline.js' $outDir
    }
    & "$PSScriptRoot\summarize-confirm.ps1" -Stamp $stamp
    return
}

if ($Scenario -eq 'latency-compare') {
    $env:SUBMIT_GLOBAL = '100000'
    $env:CONFIRM_CONCURRENCY = '6'
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $built = $false
    foreach ($mode in @('async', 'sync')) {
        $env:SYNC_BASELINE = $(if ($mode -eq 'sync') { 'true' } else { 'false' })
        $runId = "$stamp-latency-$mode"
        $outDir = Join-Path $root "loadtest\out\$runId\pipeline"
        $env:LOADTEST_OUT = "./loadtest/out/$runId/pipeline"
        New-Item -ItemType Directory -Force -Path $outDir | Out-Null
        @{
            scenario = 'latency-compare'
            mode = $mode
            syncBaseline = ($mode -eq 'sync')
            confirmConcurrency = 6
            submitGlobal = 100000
            httpTimeout = '8s'
            ladderTo = 1000
            pairing = 'rotate-course-on-student-pass'
            courseFrom = 211
            courseTo = 230
            mysqlCpus = 4
            innodbFlushLogAtTrxCommit = 2
        } | ConvertTo-Json | Set-Content (Join-Path $root "loadtest\out\$runId\run-metadata.json")
        Start-Stack -Build:($Rebuild -and -not $built)
        $built = $true
        Invoke-Script 'latency-compare.js' $outDir
    }
    Write-Output "Latency compare done. async=$stamp-latency-async sync=$stamp-latency-sync"
    return
}

if ($SkipReset) {
    Write-Output 'SkipReset: keeping current stack, waiting until api/admission/worker are healthy.'
    Wait-Healthy
} else {
    Start-Stack -Build:$Rebuild
}

$scripts = switch ($Scenario) {
    'submit' { @('submit.js') }
    'e2e' { @('e2e.js') }
    'oversell' { @('oversell.js') }
    'breaking' { @('breaking.js') }
    'pipeline' { @('pipeline.js') }
    'ladder' { @('ladder.js') }
    'correctness' { @('correctness.js') }
    default { @('submit.js', 'e2e.js', 'oversell.js') }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
foreach ($script in $scripts) {
    $name = [IO.Path]::GetFileNameWithoutExtension($script)
    $outDir = if ($script -eq 'pipeline.js' -or $script -eq 'breaking.js' -or $script -eq 'ladder.js' -or $script -eq 'correctness.js') {
        $runId = "$stamp-$name-confirm$($env:CONFIRM_CONCURRENCY)"
        $env:LOADTEST_OUT = "./loadtest/out/$runId/pipeline"
        New-Item -ItemType Directory -Force -Path (Join-Path $root "loadtest\out\$runId") | Out-Null
        @{
            scenario = $name
            confirmConcurrency = $env:CONFIRM_CONCURRENCY
            projectConcurrency = $env:PROJECT_CONCURRENCY
            courseFrom = 211
            courseTo = 230
            targetQps = $(if ($script -eq 'correctness.js') { 800 } elseif ($env:SUBMIT_GLOBAL -eq '150') { 150 } else { 500 })
            traffic = $(if ($script -eq 'correctness.js') { 'scarce-202-x8000-plus-open-800rps' } else { 'dispersed-students-and-courses' })
            admissionSplit = $true
            submitGlobal = $env:SUBMIT_GLOBAL
            waitSlaSeconds = 5
            mysqlCpus = 4
            mysqlMem = '2g'
            innodbBufferPool = '512M'
            innodbFlushLogAtTrxCommit = 2
        } | ConvertTo-Json | Set-Content (Join-Path $root "loadtest\out\$runId\run-metadata.json")
        Join-Path $root "loadtest\out\$runId\pipeline"
    } else {
        $env:LOADTEST_OUT = './loadtest/out'
        Join-Path $root 'loadtest\out'
    }
    Invoke-Script $script $outDir
}

Write-Output 'Resource snapshot (each Java instance 2 CPU / 1Gi; admission and worker are separate):'
docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}\t{{.MemPerc}}' selection-demo-api-1 selection-demo-admission-1 selection-demo-worker-1 selection-demo-mysql-1 selection-demo-redis-1 selection-demo-kafka-1
