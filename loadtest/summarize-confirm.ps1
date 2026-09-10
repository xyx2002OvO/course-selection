param(
    [string]$Stamp,
    [string]$OutRoot = (Join-Path $PSScriptRoot 'out')
)
$ErrorActionPreference = 'Stop'

function Measurement($metric, [string]$statistic) {
    if (-not $metric) { return $null }
    $found = @($metric.measurements | Where-Object { $_.statistic -eq $statistic } | Select-Object -First 1)
    if ($found) { return [double]$found.value }
    return $null
}

function LatestMetric($samples, [int]$port, [string]$name) {
    $match = @($samples | Where-Object { $_.port -eq $port -and $_.query -eq $name -and $_.metric -and -not $_.error } | Select-Object -Last 1)
    if ($match.Count) { return $match[0].metric }
    return $null
}

function GaugeSeries($samples, [int]$port, [string]$name) {
    @($samples | Where-Object { $_.port -eq $port -and $_.query -eq $name -and $_.metric -and -not $_.error } | ForEach-Object {
        Measurement $_.metric 'VALUE'
    } | Where-Object { $_ -ne $null })
}

function SummarizeRun([string]$Dir) {
    $pipeline = Join-Path $Dir 'pipeline'
    $meta = Get-Content -Raw (Join-Path $Dir 'run-metadata.json') | ConvertFrom-Json
    $summary = Get-Content -Raw (Join-Path $pipeline 'k6-summary.json') | ConvertFrom-Json
    $samples = @()
    $metricsPath = Join-Path $pipeline 'metrics.jsonl'
    if (Test-Path $metricsPath) {
        $samples = @(Get-Content $metricsPath | ForEach-Object { $_ | ConvertFrom-Json })
    }
    $waits = @()
    $waitPath = Join-Path $pipeline 'mysql-waits.csv'
    if (Test-Path $waitPath) {
        $waits = @(Import-Csv $waitPath)
    }
    $db = @()
    $dbPath = Join-Path $pipeline 'database.csv'
    if (Test-Path $dbPath) {
        $db = @(Import-Csv $dbPath)
    }

    $steadyDuration = $summary.metrics.'http_req_duration{phase:steady}'
    $steadyChecks = $summary.metrics.'checks{phase:steady}'
    $accepted = $summary.metrics.'admission_outcomes{phase:steady,status:202}'
    if (-not $accepted) { $accepted = $summary.metrics.'admission_outcomes{status:202}' }
    $reqs = $summary.metrics.'http_reqs{phase:steady}'
    if (-not $reqs) { $reqs = $summary.metrics.http_reqs }

    $pending = GaugeSeries $samples 18090 'hikaricp.connections.pending'
    $active = GaugeSeries $samples 18090 'hikaricp.connections.active'
    $busy = GaugeSeries $samples 18090 'tomcat.threads.busy'
    $acquire = LatestMetric $samples 18090 'hikaricp.connections.acquire'
    $usage = LatestMetric $samples 18090 'hikaricp.connections.usage'
    $accept = LatestMetric $samples 18090 'selection.accept.duration'
    $commit = LatestMetric $samples 18090 'selection.tx.commit'
    $timeouts = LatestMetric $samples 18090 'hikaricp.connections.timeout'
    $confirm = LatestMetric $samples 18091 'selection.confirm.duration'

    $lockDelta = $null
    $logWaitDelta = $null
    $maxLockCurrent = $null
    $maxTrxWait = $null
    $maxThreads = $null
    if ($waits.Count -ge 2) {
        $lockDelta = [long]$waits[-1].row_lock_waits - [long]$waits[0].row_lock_waits
        $logWaitDelta = [long]$waits[-1].innodb_log_waits - [long]$waits[0].innodb_log_waits
        $maxLockCurrent = ($waits | ForEach-Object { [long]$_.row_lock_current } | Measure-Object -Maximum).Maximum
        $maxTrxWait = ($waits | ForEach-Object { [long]$_.trx_lock_wait } | Measure-Object -Maximum).Maximum
        $maxThreads = ($waits | ForEach-Object { [long]$_.threads_running } | Measure-Object -Maximum).Maximum
    }

    $successRate = $null
    if ($db.Count -ge 2) {
        $elapsed = [double]$db[-1].sampled_at - [double]$db[0].sampled_at
        if ($elapsed -gt 0) {
            $successRate = [Math]::Round(([long]$db[-1].success - [long]$db[0].success) / $elapsed, 2)
        }
    }

    $acquireMeanMs = $null
    if ((Measurement $acquire 'COUNT') -gt 0) {
        $acquireMeanMs = [Math]::Round(1000 * (Measurement $acquire 'TOTAL_TIME') / (Measurement $acquire 'COUNT'), 2)
    }
    $usageMeanMs = $null
    if ((Measurement $usage 'COUNT') -gt 0) {
        $usageMeanMs = [Math]::Round(1000 * (Measurement $usage 'TOTAL_TIME') / (Measurement $usage 'COUNT'), 2)
    }
    $acceptMeanMs = $null
    if ((Measurement $accept 'COUNT') -gt 0) {
        $acceptMeanMs = [Math]::Round(1000 * (Measurement $accept 'TOTAL_TIME') / (Measurement $accept 'COUNT'), 2)
    }
    $commitMeanMs = $null
    if ((Measurement $commit 'COUNT') -gt 0) {
        $commitMeanMs = [Math]::Round(1000 * (Measurement $commit 'TOTAL_TIME') / (Measurement $commit 'COUNT'), 2)
    }
    $confirmMeanMs = $null
    if ((Measurement $confirm 'COUNT') -gt 0) {
        $confirmMeanMs = [Math]::Round(1000 * (Measurement $confirm 'TOTAL_TIME') / (Measurement $confirm 'COUNT'), 2)
    }

    [pscustomobject]@{
        run = Split-Path $Dir -Leaf
        confirmConcurrency = $meta.confirmConcurrency
        onTimePercent = if ($steadyChecks) { [Math]::Round(100 * $steadyChecks.values.rate, 2) } else { $null }
        p95ms = if ($steadyDuration) { [Math]::Round($steadyDuration.values.'p(95)', 2) } else { $null }
        accepted202 = if ($accepted) { $accepted.values.count } else { $null }
        httpReqs = if ($reqs) { $reqs.values.count } else { $null }
        sampledSuccessPerSecond = $successRate
        hikariPendingMax = if ($pending.Count) { ($pending | Measure-Object -Maximum).Maximum } else { $null }
        hikariActiveMax = if ($active.Count) { ($active | Measure-Object -Maximum).Maximum } else { $null }
        tomcatBusyMax = if ($busy.Count) { ($busy | Measure-Object -Maximum).Maximum } else { $null }
        acquireMeanMs = $acquireMeanMs
        acquireMaxMs = if ($acquire) { [Math]::Round(1000 * (Measurement $acquire 'MAX'), 2) } else { $null }
        usageMeanMs = $usageMeanMs
        usageMaxMs = if ($usage) { [Math]::Round(1000 * (Measurement $usage 'MAX'), 2) } else { $null }
        acceptMeanMs = $acceptMeanMs
        acceptMaxMs = if ($accept) { [Math]::Round(1000 * (Measurement $accept 'MAX'), 2) } else { $null }
        commitMeanMs = $commitMeanMs
        commitMaxMs = if ($commit) { [Math]::Round(1000 * (Measurement $commit 'MAX'), 2) } else { $null }
        confirmMeanMs = $confirmMeanMs
        hikariTimeouts = if ($timeouts) { Measurement $timeouts 'COUNT' } else { $null }
        mysqlRowLockWaits = $lockDelta
        mysqlRowLockCurrentMax = $maxLockCurrent
        mysqlTrxLockWaitMax = $maxTrxWait
        mysqlLogWaits = $logWaitDelta
        mysqlThreadsRunningMax = $maxThreads
        lastSuccess = if ($db.Count) { $db[-1].success } else { $null }
        lastPending = if ($db.Count) { $db[-1].pending } else { $null }
    }
}

$dirs = @()
if ($Stamp) {
    $dirs = @(Get-ChildItem -LiteralPath $OutRoot -Directory | Where-Object { $_.Name -like "$Stamp-confirm*" } | Sort-Object Name)
} else {
    $dirs = @(Get-ChildItem -LiteralPath $OutRoot -Directory | Where-Object { $_.Name -like '*-confirm*' } | Sort-Object Name | Select-Object -Last 2)
}
if (-not $dirs) { throw "No confirm comparison runs in $OutRoot" }

$rows = foreach ($dir in $dirs) { SummarizeRun $dir.FullName }
$rows | Format-List
$comparePath = Join-Path $OutRoot "$(if ($Stamp) { $Stamp } else { 'latest' })-confirm-compare.json"
$rows | ConvertTo-Json -Depth 4 | Set-Content $comparePath
Write-Output "Wrote $comparePath"
