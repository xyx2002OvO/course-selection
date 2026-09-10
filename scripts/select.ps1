param(
    [int]$Student = 1001,
    [int]$Course = 101,
    [string]$BaseUrl = 'http://localhost:18080',
    [string]$RequestId = [guid]::NewGuid().ToString(),
    [int]$WaitSeconds = 30,
    [switch]$QueryOnly
)
$ErrorActionPreference = 'Stop'
$headers = @{ 'X-Student-Id' = "$Student"; 'Idempotency-Key' = $RequestId }
$endpoint = "$BaseUrl/api/terms/202601/selections"
Write-Output "RequestId: $RequestId"
if (-not $QueryOnly) {
    try {
        $accepted = Invoke-RestMethod -Method Post -Uri $endpoint -Headers $headers -ContentType 'application/json' -Body (@{courseId=$Course} | ConvertTo-Json) -TimeoutSec 10
        $accepted | ConvertTo-Json -Compress
        if ($accepted.state -in @('SUCCESS','REJECTED','CANCELLED')) { return }
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        if ($status -ge 400 -and $status -lt 500) { throw }
        Write-Warning 'Submission outcome unknown. Querying the same request; no new ID is generated.'
    }
}
$watch = [Diagnostics.Stopwatch]::StartNew()
$intervalMs = 500
while ($watch.Elapsed.TotalSeconds -lt $WaitSeconds) {
    $delay = [int]($intervalMs * (Get-Random -Minimum 80 -Maximum 121) / 100)
    Start-Sleep -Milliseconds $delay
    try {
        $result = Invoke-RestMethod -Uri "$endpoint/$RequestId" -Headers $headers -TimeoutSec 5
        $result | ConvertTo-Json -Compress
        if ($result.state -in @('SUCCESS','REJECTED','CANCELLED')) { return }
    } catch {
        $status = if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 }
        if ($status -in @(400,401,403,404)) { throw }
        Write-Warning 'Status temporarily unavailable; backing off.'
    }
    $intervalMs = [Math]::Min(5000, $intervalMs * 2)
}
Write-Output "Still unconfirmed. Query again with -QueryOnly -RequestId $RequestId. This does not cancel the selection."
