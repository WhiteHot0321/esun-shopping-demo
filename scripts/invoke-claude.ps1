#requires -Version 7.0
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$TaskFile,
    [string]$ClaudePath = "$env:USERPROFILE/.local/bin/claude.exe",
    [ValidateRange(30,1800)][int]$TimeoutSeconds = 600
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path $PSScriptRoot -Parent
$task = (Resolve-Path -LiteralPath $TaskFile).Path
if (-not (Test-Path -LiteralPath $ClaudePath)) { throw "Claude executable missing: $ClaudePath" }
$gitDir = (& git -C $repo rev-parse --absolute-git-dir).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Cannot locate Git metadata directory' }
$runDir = Join-Path $gitDir ('codex-claude-runs/' + [guid]::NewGuid().ToString())
New-Item -ItemType Directory -Path $runDir -Force | Out-Null
$info = [System.Diagnostics.ProcessStartInfo]::new()
$info.FileName = $ClaudePath
$info.WorkingDirectory = $repo
$info.UseShellExecute = $false
$info.CreateNoWindow = $true
$info.RedirectStandardInput = $true
$info.RedirectStandardOutput = $true
$info.RedirectStandardError = $true
foreach ($arg in @('-p','--output-format','json','--restricted','--strict-mcp-config','--tools','Read,Glob,Grep','--allowedTools','Read,Glob,Grep','--disable-slash-commands')) { $info.ArgumentList.Add($arg) }
$process = [System.Diagnostics.Process]::new()
$process.StartInfo = $info
try {
    if (-not $process.Start()) { throw 'Claude did not start' }
    $stdout = $process.StandardOutput.ReadToEndAsync()
    $stderr = $process.StandardError.ReadToEndAsync()
    $process.StandardInput.Write([IO.File]::ReadAllText($task))
    $process.StandardInput.Close()
    $completed = $process.WaitForExit($TimeoutSeconds * 1000)
    if (-not $completed) { $process.Kill($true); $process.WaitForExit() }
    $raw = $stdout.GetAwaiter().GetResult()
    [IO.File]::WriteAllText((Join-Path $runDir 'result.json'), $raw)
    [IO.File]::WriteAllText((Join-Path $runDir 'stderr.log'), $stderr.GetAwaiter().GetResult())
    Write-Host "Run logs: $runDir"
    if (-not $completed) { throw "Claude timed out after $TimeoutSeconds seconds" }
    if ($process.ExitCode -ne 0) { throw "Claude failed with exit code $($process.ExitCode); inspect result.json and stderr.log (authentication errors may be in JSON)" }
    $result = $raw | ConvertFrom-Json
    if ($result.is_error) { throw "Claude returned an error; inspect result.json" }
    if (-not $result.result) { throw 'Claude returned no report' }
    $result.result
} finally { $process.Dispose() }
