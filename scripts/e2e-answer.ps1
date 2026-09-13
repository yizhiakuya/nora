$t = [System.IO.File]::ReadAllText('D:\claude\Nora\scripts\e2e-sse.txt')
$answer = ''
foreach ($m in [regex]::Matches($t, 'event:delta\s*\r?\ndata:(\{.*?\})\r?\n\r?\n')) {
  try {
    $obj = $m.Groups[1].Value | ConvertFrom-Json
    if ($obj.content) { $answer += $obj.content }
  } catch {}
}
Write-Host ("answer chars: " + $answer.Length)
Write-Host "=== answer ==="
Write-Host $answer
Write-Host "=== done ==="
foreach ($m in [regex]::Matches($t, 'event:done\s*\r?\ndata:(\{.*?\})\r?\n\r?\n')) { Write-Host $m.Groups[1].Value }

Write-Host ""
Write-Host "=== calibration line (full) ==="
$sid = (Get-Content 'D:\claude\Nora\scripts\e2e-sid.txt' -Raw).Trim()
Select-String -Path 'D:\claude\Nora\logs\agent-service-text.log' -Pattern 'context estimate' |
  Where-Object { $_.Line -match $sid } | Select-Object -Last 6 | ForEach-Object { Write-Host $_.Line }
