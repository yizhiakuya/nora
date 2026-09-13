# 无窗口启动 agent-service（javaw.exe：无控制台窗口，后台运行）
#
# 背景：java.exe + Start-Process 会弹出前台控制台窗口；
# javaw.exe 是同一 JVM 的无控制台版本，适合常驻服务。
# 密钥由服务自动从 nora-api/.env.local 加载（DotenvEnvironmentPostProcessor），
# 无需在此设置环境变量。
#
# 用法：powershell -ExecutionPolicy Bypass -File start-agent.ps1

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot   # D:\claude\Nora
$api = $PSScriptRoot                        # D:\claude\Nora\nora-api
$jar = Join-Path $api "services/agent-service/target/agent-service-0.1.0-SNAPSHOT.jar"
$javaw = "D:\tools\jdk-21\bin\javaw.exe"

# 先停旧进程（jar 被锁会导致打包失败）
$existing = Get-CimInstance Win32_Process -Filter "Name LIKE 'java%'" |
    Where-Object { $_.CommandLine -like "*agent-service*" }
foreach ($p in $existing) {
    Write-Host "停止旧进程 PID=$($p.ProcessId)"
    Stop-Process -Id $p.ProcessId -Force
    Start-Sleep -Seconds 2
}

# 无窗口启动（javaw + 输出重定向）
Start-Process -FilePath $javaw `
    -ArgumentList "-jar", $jar `
    -WorkingDirectory $api `
    -RedirectStandardOutput (Join-Path $root "agent-service.stdout.log") `
    -RedirectStandardError  (Join-Path $root "agent-service.stderr.log")

# 等待端口就绪
$deadline = (Get-Date).AddSeconds(60)
while ((Get-Date) -lt $deadline) {
    $listen = netstat -ano | Select-String ":8083" | Select-String "LISTENING"
    if ($listen) {
        Write-Host "agent-service 已就绪（8083 LISTENING）"
        Write-Host $listen
        exit 0
    }
    Start-Sleep -Seconds 2
}
Write-Host "启动超时：60 秒内未监听 8083，请检查 agent-service.stdout.log"
exit 1