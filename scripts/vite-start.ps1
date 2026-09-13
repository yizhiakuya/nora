# Start Nora web (vite) fully hidden - no console window, survives the caller.
# Usage: powershell -ExecutionPolicy Bypass -File D:\claude\Nora\scripts\vite-start.ps1
$conn = Get-NetTCPConnection -LocalPort 3001 -State Listen -ErrorAction SilentlyContinue
if ($conn) { Write-Host 'already running on :3001'; exit 0 }
Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = 'C:\Windows\System32\wscript.exe D:\claude\Nora\scripts\vite-hidden.vbs' } | Out-Null
Start-Sleep -Seconds 7
$conn = Get-NetTCPConnection -LocalPort 3001 -State Listen -ErrorAction SilentlyContinue
if ($conn) { Write-Host 'vite started (hidden) on :3001' } else { Write-Host 'vite not up; see nora-web.stdout.log / nora-web.stderr.log'; exit 1 }
