param(
    [string]$BaseUrl = 'http://localhost:18080',
    [string]$Password = 'demo-pass',
    [string]$Id,
    [string]$Tag,
    [switch]$Slow,
    [switch]$StartStack,
    [switch]$FailFast
)
$env:PYTHONUTF8 = '1'
$env:PYTHONIOENCODING = 'utf-8'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root
$python = Get-Command python -ErrorAction SilentlyContinue
if (-not $python) { throw 'Need Python 3 on PATH to run tests/run_cases.py' }
$argsList = @("$PSScriptRoot\run_cases.py", "--base-url", $BaseUrl, "--password", $Password)
if ($Id) { $argsList += @('--id', $Id) }
if ($Tag) { $argsList += @('--tag', $Tag) }
if ($Slow) { $argsList += '--slow' }
if ($StartStack) { $argsList += '--start-stack' }
if ($FailFast) { $argsList += '--fail-fast' }
& python @argsList
exit $LASTEXITCODE
