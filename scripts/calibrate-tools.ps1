# 用逐轮真实数据标定 tools spec 的 chars/token 比例:
# 对每个请求,拿 body 字符数 与 该轮上报的 usage.in 配对
$log = 'D:\claude\Nora\logs\agent-service-text.log'
$sse = 'D:\claude\Nora\logs\agent-service-sse.log'

$reqLines = Select-String -Path $log -Pattern 'LLM upstream request' | Select-Object -Last 40
$doneLines = Select-String -Path $sse -Pattern 'round done' | Select-Object -Last 40

Write-Host ("req lines: " + $reqLines.Count + "  done lines: " + $doneLines.Count)
Write-Host ""
Write-Host ("{0,-24} {1,12} {2,10} {3,10} {4,8}" -f 'time', 'bodyChars', 'inTokens', 'chars/tok', 'toolsChars')

$n = [Math]::Min($reqLines.Count, $doneLines.Count)
$rs = $reqLines | Select-Object -Last $n
$ds = $doneLines | Select-Object -Last $n
for ($i = 0; $i -lt $n; $i++) {
  $body = $rs[$i].Line.Substring($rs[$i].Line.IndexOf('body=') + 5)
  $ts = $rs[$i].Line.Substring(0, 19)
  $inTok = $null
  if ($ds[$i].Line -match 'usage=in=(\d+)') { $inTok = [int]$Matches[1] }
  $ti = $body.IndexOf('"tools"')
  $toolsChars = if ($ti -ge 0) { $body.Length - $ti } else { 0 }
  if ($inTok) {
    $ratio = [Math]::Round($body.Length / $inTok, 2)
    Write-Host ("{0,-24} {1,12} {2,10} {3,10} {4,8}" -f $ts, $body.Length, $inTok, $ratio, $toolsChars)
  }
}
