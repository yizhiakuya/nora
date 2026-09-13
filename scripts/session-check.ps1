param([string]$Sid = '')
if (-not $Sid) {
  $Sid = (Get-Content 'D:\claude\Nora\scripts\e2e-sid.txt' -Raw -ErrorAction SilentlyContinue)
  if ($Sid) { $Sid = $Sid.Trim() }
}
if (-not $Sid) { Write-Host 'no session id'; exit 1 }
Write-Host ("session: " + $Sid)
$log = 'D:\claude\Nora\logs\agent-service-text.log'
$hits = Select-String -Path $log -Pattern $Sid -ErrorAction SilentlyContinue
Write-Host ("log lines: " + $hits.Count)
Write-Host "--- tool calls ---"
$hits | Where-Object { $_.Line -match 'tool call: mcp__phone' } | ForEach-Object {
  if ($_.Line -match 'tool call: (mcp__phone__\w+) \(round=(\d+), args=(\{.*)') {
    Write-Host ("  round=" + $Matches[2] + " " + $Matches[1] + " " + $Matches[3].Substring(0, [Math]::Min(70, $Matches[3].Length)))
  }
}
Write-Host "--- calibration ---"
$hits | Where-Object { $_.Line -match 'context estimate' } | ForEach-Object {
  $l = $_.Line
  if ($l.Length -gt 200) { $l = $l.Substring(0, 200) }
  Write-Host ("  " + $l)
}
Write-Host "--- request bodies ---"
$marker = [string][char]0x65E9 + [string][char]0x671F + [string][char]0x56FE + [string][char]0x7247 + [string][char]0x5DF2 + [string][char]0x7701 + [string][char]0x7565
$reqs = Select-String -Path $log -Pattern 'LLM upstream request' | Where-Object { $_.Line -match $Sid }
$i = 0
foreach ($r in $reqs) {
  $i++
  $body = $r.Line.Substring($r.Line.IndexOf('body=') + 5)
  $imgs = ([regex]::Matches($body, 'data:image/')).Count
  Write-Host ("  req#" + $i + " body=" + [Math]::Round($body.Length / 1024) + "K images=" + $imgs + " recycled=" + $body.Contains($marker))
}
