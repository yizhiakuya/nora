$c = Get-NetTCPConnection -LocalPort 3001 -State Listen -ErrorAction SilentlyContinue
if ($c) {
  $ownerPid = $c[0].OwningProcess
  $proc = Get-Process -Id $ownerPid -ErrorAction SilentlyContinue
  Write-Host ("3001 held by " + $proc.ProcessName + " (pid " + $ownerPid + ") - stopping to free dev port")
  Stop-Process -Id $ownerPid -Force
  Start-Sleep -Seconds 2
} else { Write-Host "3001 free" }
Start-Process -WindowStyle Hidden -FilePath 'cmd.exe' -ArgumentList '/c','npm run dev > D:\claude\Nora\nora-web.stdout.log 2> D:\claude\Nora\nora-web.stderr.log' -WorkingDirectory 'D:\claude\Nora\nora-web'
$code = ''
for ($i = 0; $i -lt 40; $i++) {
  Start-Sleep -Seconds 1
  $code = curl.exe -s -o NUL -w "%{http_code}" --noproxy "*" --max-time 2 http://127.0.0.1:3001/
  if ($code -eq '200') { break }
}
Write-Host ("vite http: " + $code)
$html = curl.exe -s --noproxy "*" --max-time 3 http://127.0.0.1:3001/
Write-Host ($html | Select-Object -First 15)
