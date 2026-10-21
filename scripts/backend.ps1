param([ValidateSet('start','stop','restart','status','build','deploy')][string]$Action = 'status')
$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path -Parent $PSScriptRoot
$taskApi = Join-Path $taskRoot 'nora-api'
$taskJar = Join-Path $taskApi 'target/nora-api-0.1.0-SNAPSHOT.jar'
$taskPort = if ($env:NORA_PORT) { $env:NORA_PORT } else { '18080' }
$taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { (Get-Command java).Source }
function Get-NoraProcess {
    Get-CimInstance Win32_Process -Filter "Name LIKE 'java%'" | Where-Object {
        $_.CommandLine -and $_.CommandLine.Contains($taskJar)
    }
}
function Stop-Nora {
    foreach ($taskProcess in @(Get-NoraProcess)) { Stop-Process -Id $taskProcess.ProcessId -Force }
}
function Start-Nora {
    if (Get-NoraProcess) { Write-Host 'Nora is already running'; return }
    if (-not (Test-Path -LiteralPath $taskJar)) { throw 'Run ./nora.sh build first' }
    if (Get-NetTCPConnection -LocalPort $taskPort -State Listen -ErrorAction SilentlyContinue) {
        throw "Port $taskPort is occupied by another process; stop the old deployment explicitly"
    }
    $env:NORA_AGENT_WORKSPACE = Join-Path $taskRoot 'agent-workspace'
    $env:LOG_PATH = Join-Path $taskRoot 'logs'
    $env:NORA_PWSH_BUNDLE = Join-Path $taskRoot 'tools/pwsh/PowerShell-7.6.6-win-x64.zip'
    New-Item -ItemType Directory -Force -Path $env:LOG_PATH | Out-Null
    $taskProcess = Start-Process -FilePath $taskJava -WindowStyle Hidden -ArgumentList @('-jar', ('"' + $taskJar + '"')) `
        -WorkingDirectory $taskApi -RedirectStandardOutput (Join-Path $env:LOG_PATH 'nora.stdout.log') `
        -RedirectStandardError (Join-Path $env:LOG_PATH 'nora.stderr.log') -PassThru
    $taskDeadline = (Get-Date).AddSeconds(120)
    while ((Get-Date) -lt $taskDeadline) {
        if ($taskProcess.HasExited) { throw 'Nora exited; inspect logs/nora.stderr.log' }
        try {
            if ((Invoke-WebRequest "http://127.0.0.1:$taskPort/actuator/health" -UseBasicParsing -TimeoutSec 3).StatusCode -eq 200) {
                Write-Host "Nora ready on $taskPort (PID $($taskProcess.Id))"; return
            }
        } catch [System.Net.WebException] { }
        Start-Sleep -Seconds 2
    }
    throw 'Nora did not become healthy within 120 seconds'
}
switch ($Action) {
    'stop' { Stop-Nora }
    'start' { Start-Nora }
    'restart' { Stop-Nora; Start-Nora }
    { $_ -in 'build','deploy' } {
        Stop-Nora
        Push-Location $taskApi
        try { & mvn verify; if ($LASTEXITCODE -ne 0) { throw 'Maven verification failed' } }
        finally { Pop-Location }
        Start-Nora
    }
    'status' {
        Get-NoraProcess | Select-Object ProcessId, CommandLine
        Invoke-WebRequest "http://127.0.0.1:$taskPort/actuator/health" -UseBasicParsing -TimeoutSec 5 | Select-Object StatusCode, Content
    }
}
