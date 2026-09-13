param([string]$Sid = 'sess-1789298882261')
$log = 'D:\claude\Nora\logs\agent-service-text.log'
$hits = Select-String -Path $log -Pattern $Sid | Where-Object { $_.Line -match 'context estimate' }
Write-Host ("calibration lines: " + $hits.Count)
foreach ($h in $hits) {
  $l = $h.Line
  # 只保留 calibration 部分
  $i = $l.IndexOf('context estimate')
  if ($i -ge 0) { Write-Host $l.Substring($i) }
}

Write-Host ""
Write-Host "=== 全部请求体 vs usage 配对 ==="
$sse = 'D:\claude\Nora\logs\agent-service-sse.log'
$reqs = Select-String -Path $log -Pattern 'LLM upstream request' | Where-Object { $_.Line -match $Sid }
$dones = Select-String -Path $sse -Pattern 'round done' | Where-Object { $_.Line -match $Sid }
Write-Host ("reqs=" + $reqs.Count + " dones=" + $dones.Count)
for ($k = 0; $k -lt [Math]::Min($reqs.Count, $dones.Count); $k++) {
  $body = $reqs[$k].Line.Substring($reqs[$k].Line.IndexOf('body=') + 5)
  $inTok = 'n/a'
  if ($dones[$k].Line -match 'usage=in=(\d+)') { $inTok = $Matches[1] }
  $imgs = ([regex]::Matches($body, 'data:image/')).Count
  $ts = $reqs[$k].Line.Substring(11, 8)
  Write-Host ("  #" + ($k + 1) + " " + $ts + " body=" + [Math]::Round($body.Length / 1024) + "K imgs=" + $imgs + " in=" + $inTok)
}
