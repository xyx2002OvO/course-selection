param(
    [string]$OutDir
)
$ErrorActionPreference = 'Continue'
$stopFlag = Join-Path $OutDir 'stop-watch'
$csv = Join-Path $OutDir 'instance-health.csv'
$fail = Join-Path $OutDir 'first-failure.json'
$names = @(
    'selection-demo-api-1',
    'selection-demo-admission-1',
    'selection-demo-worker-1',
    'selection-demo-mysql-1',
    'selection-demo-redis-1',
    'selection-demo-kafka-1'
)
'time,name,status,health,oom,cpu,mem' | Set-Content -Encoding utf8 $csv

function Kill-Loadgen {
    $ids = docker ps -q --filter 'label=com.docker.compose.service=loadgen'
    if ($ids) { docker kill $ids | Out-Null }
}

while (-not (Test-Path $stopFlag)) {
    $now = (Get-Date).ToString('o')
    foreach ($name in $names) {
        $status = 'missing'
        $health = ''
        $oom = ''
        $cpu = ''
        $mem = ''
        $inspect = docker inspect $name --format '{{.State.Status}}|{{if .State.Health}}{{.State.Health.Status}}{{end}}|{{.State.OOMKilled}}' 2>$null
        if ($LASTEXITCODE -eq 0 -and $inspect) {
            $parts = $inspect.Trim().Split('|')
            $status = $parts[0]
            $health = $parts[1]
            $oom = $parts[2]
        }
        $stats = docker stats --no-stream --format '{{.CPUPerc}}|{{.MemUsage}}' $name 2>$null
        if ($stats) {
            $sp = $stats.Trim().Split('|')
            $cpu = $sp[0]
            $mem = $sp[1]
        }
        Add-Content -Encoding utf8 $csv "$now,$name,$status,$health,$oom,$cpu,$mem"
        $core = $name -match 'api-1$|admission-1$|worker-1$|mysql-1$'
        $dead = ($status -in @('exited', 'dead', 'missing')) -or ($oom -eq 'true') -or ($status -eq 'restarting') -or ($core -and $health -eq 'unhealthy')
        if ($dead -and -not (Test-Path $fail)) {
            $payload = @{
                time      = $now
                instance  = $name
                status    = $status
                health    = $health
                oomKilled = $oom
            } | ConvertTo-Json
            Set-Content -Encoding utf8 $fail $payload
            Kill-Loadgen
        }
    }
    Start-Sleep -Seconds 2
}
