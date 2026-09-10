param(
    [string]$ReferenceJar = (Join-Path $PSScriptRoot '..\target\course-selection-0.1.0.jar'),
    [string]$OutputRoot = (Join-Path $PSScriptRoot '..\loadtest\out')
)
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Read-Fingerprint([string]$Path) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    $entries = @{}
    try {
        foreach ($entry in $zip.Entries) {
            if ($entry.FullName.EndsWith('/')) { continue }
            if ($entry.FullName -notlike 'BOOT-INF/classes/*' -and
                $entry.FullName -notlike 'BOOT-INF/lib/*' -and
                $entry.FullName -ne 'BOOT-INF/classpath.idx') { continue }
            $stream = $entry.Open()
            $hash = [Security.Cryptography.SHA256]::Create()
            try {
                $entries[$entry.FullName] = [BitConverter]::ToString($hash.ComputeHash($stream)).Replace('-','')
            } finally { $hash.Dispose(); $stream.Dispose() }
        }
    } finally { $zip.Dispose() }
    if (-not $entries.ContainsKey('BOOT-INF/classes/dev/demo/selection/application/SelectionService.class')) {
        throw "Not a course-selection executable JAR: $Path"
    }
    return $entries
}

$reference = (Resolve-Path -LiteralPath $ReferenceJar).Path
$referenceHashes = Read-Fingerprint $reference
$runDir = Join-Path $OutputRoot ('runtime-audit-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $runDir | Out-Null
$report = [ordered]@{
    createdAt = [DateTimeOffset]::Now.ToString('o')
    referenceJar = $reference
    referenceSha256 = (Get-FileHash -LiteralPath $reference -Algorithm SHA256).Hash
    scope = 'Application classes, resources, dependency JARs and classpath order; ZIP timestamps excluded'
    containers = @()
    status = 'INCOMPLETE'
}
try {
    foreach ($name in @('selection-demo-api-1','selection-demo-worker-1')) {
        $raw = docker inspect $name
        if ($LASTEXITCODE -ne 0) { throw "Cannot inspect $name" }
        $inspection = ($raw | ConvertFrom-Json)[0]
        $jarPath = Join-Path $runDir "$name.jar"
        docker cp "${name}:/app/app.jar" $jarPath
        if ($LASTEXITCODE -ne 0) { throw "Cannot copy runtime JAR from $name" }
        $runtimeHashes = Read-Fingerprint $jarPath
        $differences = @()
        foreach ($key in @($referenceHashes.Keys + $runtimeHashes.Keys | Sort-Object -Unique)) {
            if ($referenceHashes[$key] -ne $runtimeHashes[$key]) {
                $differences += [pscustomobject]@{
                    entry=$key; reference=$referenceHashes[$key]; runtime=$runtimeHashes[$key]
                }
            }
        }
        $selectedEnvironment = @($inspection.Config.Env | Where-Object {
            $_ -match '^(JAVA_TOOL_OPTIONS|JDK_JAVA_OPTIONS|_JAVA_OPTIONS|CLASSPATH|LOADER_PATH|SPRING_PROFILES_ACTIVE|SPRING_CONFIG_LOCATION|SPRING_CONFIG_ADDITIONAL_LOCATION|SPRING_KAFKA_LISTENER_CONCURRENCY|SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE|CONFIRM_CONCURRENCY|PROJECT_CONCURRENCY|ADMISSION_FAST_PATH|BACKLOG_HEADROOM_SECONDS)='
        })
        $report.containers += [pscustomobject]@{
            name=$name; id=$inspection.Id; image=$inspection.Image
            startedAt=$inspection.State.StartedAt
            entrypoint=$inspection.Config.Entrypoint; command=$inspection.Config.Cmd
            mounts=@($inspection.Mounts | Select-Object Type,Source,Destination,RW)
            selectedEnvironment=$selectedEnvironment
            cpuQuota=$inspection.HostConfig.NanoCpus; memoryBytes=$inspection.HostConfig.Memory
            jarSha256=(Get-FileHash -LiteralPath $jarPath -Algorithm SHA256).Hash
            comparedEntries=$runtimeHashes.Count
            payloadMatches=($differences.Count -eq 0)
            differences=$differences
        }
    }
    $report.status = if (@($report.containers | Where-Object { -not $_.payloadMatches }).Count) {
        'PAYLOAD_MISMATCH'
    } else { 'PAYLOAD_MATCH_REVIEW_RUNTIME_OVERRIDES' }
} catch {
    $report.error = $_.Exception.Message
    throw
} finally {
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $runDir 'audit.json') -Encoding utf8
    Write-Output "Audit: $runDir"
}
if ($report.status -eq 'PAYLOAD_MISMATCH') { throw 'Runtime JAR differs from the reference; inspect audit.json' }
Write-Output 'Both runtime payloads match the reference. Review recorded command, mounts and environment separately.'
