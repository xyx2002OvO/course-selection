param(
    [Parameter(Mandatory)][string]$OutDir,
    [int]$AdmissionPort = 18093,
    [int]$WorkerPort = 18091
)
$ErrorActionPreference = 'Stop'
$stopFlag = Join-Path $OutDir 'stop-watch'
$requestSql = @'
SELECT UNIX_TIMESTAMP(NOW(6)) AS sampled_at, COUNT(*) AS requests,
 COALESCE(SUM(state='SUCCESS'),0) AS success,
 COALESCE(SUM(state='ACCEPTED'),0) AS pending,
 COALESCE(SUM(state='CANCELLED'),0) AS cancelled,
 COALESCE(SUM(state='REJECTED'),0) AS rejected,
 COALESCE(MAX(CASE WHEN state='ACCEPTED' THEN TIMESTAMPDIFF(SECOND,created_at,NOW()) END),0) AS oldest_pending_seconds
FROM selection_request;
'@
$waitSql = @'
SELECT UNIX_TIMESTAMP(NOW(6)) AS sampled_at,
 (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_running') AS threads_running,
 (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Threads_connected') AS threads_connected,
 (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_current_waits') AS row_lock_current,
 (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_waits') AS row_lock_waits,
 (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_row_lock_time') AS row_lock_time_ms,
 (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_log_waits') AS innodb_log_waits,
 (SELECT VARIABLE_VALUE FROM performance_schema.global_status WHERE VARIABLE_NAME='Innodb_os_log_written') AS innodb_os_log_written,
 (SELECT COUNT(*) FROM information_schema.innodb_trx WHERE trx_state='LOCK WAIT') AS trx_lock_wait,
 (SELECT COUNT(*) FROM performance_schema.data_lock_waits) AS data_lock_waits;
'@
$eventSql = @'
SELECT UNIX_TIMESTAMP(NOW(6)) AS sampled_at, EVENT_NAME,
 COUNT_STAR AS wait_count, ROUND(SUM_TIMER_WAIT/1e12, 6) AS wait_s
FROM performance_schema.events_waits_summary_global_by_event_name
WHERE SUM_TIMER_WAIT > 0 AND EVENT_NAME NOT IN ('idle')
ORDER BY SUM_TIMER_WAIT DESC LIMIT 12;
'@
$instrumentSql = @'
UPDATE performance_schema.setup_instruments
 SET ENABLED='YES', TIMED='YES'
 WHERE NAME LIKE 'wait/io/file/innodb/%'
    OR NAME LIKE 'wait/lock/%'
    OR NAME LIKE 'wait/synch/mutex/innodb/log_%';
UPDATE performance_schema.setup_consumers SET ENABLED='YES'
 WHERE NAME IN ('global_instrumentation','events_waits_current','events_waits_history_long');
'@

function Invoke-Mysql([string]$User, [string]$Password, [string]$Sql) {
    docker exec -e "MYSQL_PWD=$Password" selection-demo-mysql-1 mysql "-u$User" --batch --raw -e $Sql
}

function Read-Metric([int]$Port, [string]$Name) {
    $uri = "http://127.0.0.1:$Port/actuator/metrics/$Name"
    Invoke-RestMethod $uri -TimeoutSec 2
}

$admissionMetrics = @(
    'hikaricp.connections.active',
    'hikaricp.connections.pending',
    'hikaricp.connections.idle',
    'hikaricp.connections.acquire',
    'hikaricp.connections.acquire?tag=phi:0.95',
    'hikaricp.connections.usage',
    'hikaricp.connections.usage?tag=phi:0.95',
    'hikaricp.connections.timeout',
    'tomcat.threads.busy',
    'tomcat.threads.current',
    'selection.accept.duration',
    'selection.accept.duration?tag=phi:0.95',
    'selection.tx.commit',
    'selection.tx.commit?tag=phi:0.95'
)
$workerMetrics = @(
    'hikaricp.connections.active',
    'hikaricp.connections.pending',
    'hikaricp.connections.idle',
    'hikaricp.connections.acquire',
    'hikaricp.connections.usage',
    'hikaricp.connections.timeout',
    'selection.confirm.duration',
    'selection.confirm.duration?tag=phi:0.95',
    'selection.tx.commit',
    'selection.tx.commit?tag=phi:0.95'
)

try {
    Invoke-Mysql 'root' 'root-dev' $instrumentSql | Out-Null
} catch {
    $_ | Out-String | Set-Content (Join-Path $OutDir 'sampling-instrument-error.txt')
}

try {
    while (-not (Test-Path $stopFlag)) {
        $rows = docker exec -e MYSQL_PWD=selection-dev selection-demo-mysql-1 mysql -uselection selection --batch --raw -e $requestSql
        if ($LASTEXITCODE -ne 0) { throw 'Database sampling failed' }
        $rows | ConvertFrom-Csv -Delimiter "`t" | Export-Csv (Join-Path $OutDir 'database.csv') -NoTypeInformation -Append

        try {
            $waitRows = Invoke-Mysql 'root' 'root-dev' $waitSql
            if ($LASTEXITCODE -eq 0) {
                $waitRows | ConvertFrom-Csv -Delimiter "`t" | Export-Csv (Join-Path $OutDir 'mysql-waits.csv') -NoTypeInformation -Append
            }
        } catch {
            @{ time = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); error = $_.Exception.Message } |
                ConvertTo-Json -Compress | Add-Content (Join-Path $OutDir 'mysql-waits-error.jsonl')
        }

        try {
            $eventRows = Invoke-Mysql 'root' 'root-dev' $eventSql
            if ($LASTEXITCODE -eq 0) {
                $parsed = @($eventRows | ConvertFrom-Csv -Delimiter "`t")
                @{ time = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); events = $parsed } |
                    ConvertTo-Json -Depth 6 -Compress | Add-Content (Join-Path $OutDir 'mysql-wait-events.jsonl')
            }
        } catch {
            @{ time = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); error = $_.Exception.Message } |
                ConvertTo-Json -Compress | Add-Content (Join-Path $OutDir 'mysql-wait-events.jsonl')
        }

        foreach ($pair in @(@{ port = $AdmissionPort; names = $admissionMetrics }, @{ port = $WorkerPort; names = $workerMetrics })) {
            foreach ($metric in $pair.names) {
                try {
                    $value = Read-Metric $pair.port $metric
                    @{ time = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); port = $pair.port; query = $metric; metric = $value } |
                        ConvertTo-Json -Depth 8 -Compress | Add-Content (Join-Path $OutDir 'metrics.jsonl')
                } catch {
                    @{ time = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds(); port = $pair.port; query = $metric; name = $metric; error = $_.Exception.Message } |
                        ConvertTo-Json -Compress | Add-Content (Join-Path $OutDir 'metrics.jsonl')
                }
            }
        }
        Start-Sleep -Seconds 2
    }
} catch {
    $_ | Out-String | Set-Content (Join-Path $OutDir 'sampling-error.txt')
    exit 1
} finally {
    try {
        docker exec -e MYSQL_PWD=root-dev selection-demo-mysql-1 mysql -uroot --batch -e "SHOW ENGINE INNODB STATUS" |
            Set-Content (Join-Path $OutDir 'innodb-status.txt')
    } catch {
        $_ | Out-String | Set-Content (Join-Path $OutDir 'innodb-status-error.txt')
    }
}
