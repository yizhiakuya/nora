# 真实离线恢复验收：仅在没有运行中对话时暂停网关，40 秒后恢复。
$ErrorActionPreference = 'Stop'
$taskRepo = Split-Path -Parent $PSScriptRoot
$taskRuns = Invoke-RestMethod 'http://localhost:18083/api/chat/runs?status=running,awaiting_approval,cancelling'
if (@($taskRuns.data).Count -gt 0) { throw '有运行中的对话，不能执行网关中断验收' }
$taskPids = @(Get-NetTCPConnection -LocalPort 18080 -State Listen | Select-Object -ExpandProperty OwningProcess -Unique)
if ($taskPids.Count -ne 1) { throw '网关进程不唯一' }
$taskProcess = Get-CimInstance Win32_Process -Filter "ProcessId=$($taskPids[0])"
if ($taskProcess.Name -notin @('java.exe', 'javaw.exe') -or $taskProcess.CommandLine -notlike '*gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar*') {
    throw '监听进程不是 Nora 网关'
}
Stop-Process -Id $taskPids[0] -Force
Write-Host 'GATEWAY_PAUSED'
try { Start-Sleep -Seconds 40 }
finally {
    Push-Location $taskRepo
    try { & "$env:ProgramFiles/Git/bin/bash.exe" -lc 'cd /d/claude/Nora && ./nora.sh start' }
    finally { Pop-Location }
}
