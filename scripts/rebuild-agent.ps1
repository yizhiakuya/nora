$ErrorActionPreference = 'Continue'

Write-Host "=== 1. stop agent-service on :18083 (PowerShell, no bash quoting) ==="
$conn = Get-NetTCPConnection -LocalPort 18083 -State Listen -ErrorAction SilentlyContinue
if ($conn) {
  foreach ($c in $conn) {
    Write-Host ("stopping PID " + $c.OwningProcess)
    Stop-Process -Id $c.OwningProcess -Force -ErrorAction SilentlyContinue
  }
  Start-Sleep -Seconds 3
} else {
  Write-Host "not listening"
}
$still = Get-NetTCPConnection -LocalPort 18083 -State Listen -ErrorAction SilentlyContinue
Write-Host ("still listening: " + [bool]$still)

Write-Host "=== 2. package (clean package, with tests) ==="
$env:JAVA_HOME = 'D:\tools\jdk-21'
Push-Location 'D:\claude\Nora\nora-api'
try {
  & cmd.exe /c "set `"JAVA_HOME=D:\tools\jdk-21`" && D:\tools\apache-maven-3.9.16\bin\mvn.cmd -q -pl services/agent-service clean package" 2>&1 |
    Select-Object -Last 30 | ForEach-Object { Write-Host $_ }
} finally {
  Pop-Location
}
$jar = Get-Item 'D:\claude\Nora\nora-api\services\agent-service\target\agent-service-0.1.0-SNAPSHOT.jar' -ErrorAction SilentlyContinue
Write-Host ("jar timestamp: " + $jar.LastWriteTime + "  size: " + $jar.Length)

Write-Host "=== 3. start (javaw, no window) ==="
& powershell -NoProfile -ExecutionPolicy Bypass -File D:\claude\Nora\nora-api\start-agent.ps1
