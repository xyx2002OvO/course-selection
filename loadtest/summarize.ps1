param([string]$OutDir = (Join-Path $PSScriptRoot 'out'))
$ErrorActionPreference = 'Stop'
Get-ChildItem -LiteralPath $OutDir -Directory | ForEach-Object {
    $summaryPath = Join-Path $_.FullName 'pipeline\pipeline-summary.json'
    if (-not (Test-Path $summaryPath)) { return }
    $result = Get-Content -Raw $summaryPath | ConvertFrom-Json
    $metadataPath = Join-Path $_.FullName 'run-metadata.json'
    $metadata = if (Test-Path $metadataPath) { Get-Content -Raw $metadataPath | ConvertFrom-Json } else { $null }
    $samplesPath = Join-Path $_.FullName 'pipeline\database.csv'
    $samples = if (Test-Path $samplesPath) { @(Import-Csv $samplesPath) } else { @() }
    $logPath = Join-Path $_.FullName 'pipeline\k6-events.log'
    $startMs = $null
    if (Test-Path $logPath) {
        $match = Select-String -Path $logPath -Pattern 'LOAD_START_MS=(\d+)' | Select-Object -First 1
        if ($match) { $startMs = [double]$match.Matches[0].Groups[1].Value }
    }
    $steady = @($samples | Where-Object {
        $startMs -and [double]$_.sampled_at -ge ($startMs/1000+15) -and [double]$_.sampled_at -le ($startMs/1000+75)
    })
    $confirmedRate = $null
    $sampleSeconds = 0
    if ($steady.Count -ge 2) {
        $sampleSeconds = [double]$steady[-1].sampled_at - [double]$steady[0].sampled_at
        $confirmedRate = [Math]::Round(([long]$steady[-1].success-[long]$steady[0].success)/$sampleSeconds,2)
    }
    [pscustomobject]@{
        run=$_.Name; variant=$metadata.variant
        accepted202PerSecond=[Math]::Round($result.metrics.'admission_outcomes{phase:steady,status:202}'.values.count/60,2)
        onTimePercent=[Math]::Round($result.metrics.'checks{phase:steady}'.values.rate*100,2)
        p95ms=[Math]::Round($result.metrics.'http_req_duration{phase:steady}'.values.'p(95)',2)
        dropped=$result.metrics.dropped_iterations.values.count
        steady429=$result.metrics.'admission_outcomes{phase:steady,status:429}'.values.count
        sampledSuccessPerSecond=$confirmedRate
        sampledWindowSeconds=[Math]::Round($sampleSeconds,2)
        lastSuccess=$(if ($samples.Count) { $samples[-1].success } else { $null })
        lastCancelled=$(if ($samples.Count) { $samples[-1].cancelled } else { $null })
    }
}
