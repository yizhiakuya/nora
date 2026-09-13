$ErrorActionPreference = 'Continue'
$api = 'D:\claude\Nora\nora-api'
$javaw = 'D:\tools\jdk-21\bin\javaw.exe'
$svcs = @('gateway','file','rag','agent','datasource','env','automation')

foreach ($svc in $svcs) {
  $jar = Join-Path $api "services\$svc-service\target\$svc-service-0.1.0-SNAPSHOT.jar"
  if (-not (Test-Path $jar)) { Write-Host ("$svc : jar missing, skip"); continue }
  $out = Join-Path $api "$svc-service.stdout.log"
  $err = Join-Path $api "$svc-service.stderr.log"
  Start-Process -FilePath $javaw -ArgumentList '-jar', $jar -WorkingDirectory $api `
    -RedirectStandardOutput $out -RedirectStandardError $err -WindowStyle Hidden
  Write-Host ("started $svc")
}

Write-Host "--- wait for health (max 120s) ---"
$deadline = (Get-Date).AddSeconds(120)
$ports = @{gateway=8080; file=8081; rag=8082; agent=8083; datasource=8084; env=8085; automation=8086}
while ((Get-Date) -lt $deadline) {
  $all = $true
  foreach ($k in $ports.Keys) {
    $code = curl.exe -s -o NUL -w "%{http_code}" --noproxy "*" --max-time 2 ("http://127.0.0.1:" + $ports[$k] + "/actuator/health")
    if ($code -ne '200') { $all = $false }
  }
  if ($all) { break }
  Start-Sleep -Seconds 3
}

Write-Host "--- status ---"
foreach ($k in $ports.Keys) {
  $p = $ports[$k]
  $listen = (netstat -ano | Select-String (":" + $p + " ") | Select-String 'LISTENING').Count
  $code = curl.exe -s -o NUL -w "%{http_code}" --noproxy "*" --max-time 3 ("http://127.0.0.1:" + $p + "/actuator/health")
  Write-Host ("{0,-11} :{1}  listen={2}  health={3}" -f $k, $p, $listen, $code)
}
