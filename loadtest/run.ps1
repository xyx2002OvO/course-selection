param(
    [ValidateSet('submit', 'e2e', 'oversell', 'breaking', 'pipeline', 'ladder', 'all')]
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
    Write-Output "Resetting loadtest stack (confirm=$($env:CONFIRM_CONCURRENCY) project=$($env:PROJECT_CONCURRENCY))."
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
}

function Invoke-Script([string]$Script, [string]$OutDir) {
    Write-Output "=== $Script -> $OutDir ==="
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    $stopFlag = Join-Path $OutDir 'stop-watch'
    Remove-Item $stopFlag, (Join-Path $OutDir 'first-failure.json') -ErrorAction SilentlyContinue
    $watchers = @()
    if ($Script -eq 'breaking.js' -or $Script -eq 'pipeline.js' -or $Script -eq 'ladder.js') {
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
        Save-Snapshot $OutDir 'database-at-stop.txt'
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
    default { @('submit.js', 'e2e.js', 'oversell.js') }
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
foreach ($script in $scripts) {
    $name = [IO.Path]::GetFileNameWithoutExtension($script)
    $outDir = if ($script -eq 'pipeline.js' -or $script -eq 'breaking.js' -or $script -eq 'ladder.js') {
        $runId = "$stamp-$name-confirm$($env:CONFIRM_CONCURRENCY)"
        $env:LOADTEST_OUT = "./loadtest/out/$runId/pipeline"
        New-Item -ItemType Directory -Force -Path (Join-Path $root "loadtest\out\$runId") | Out-Null
        @{
            scenario = $name
            confirmConcurrency = $env:CONFIRM_CONCURRENCY
            projectConcurrency = $env:PROJECT_CONCURRENCY
            courseFrom = 211
            courseTo = 230
            targetQps = 500
            traffic = 'dispersed-students-and-courses'
            admissionSplit = $true
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
