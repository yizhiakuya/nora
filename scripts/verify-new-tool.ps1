# Token 从本地密钥文件读取（.gitignore 已忽略，勿把 token 写进仓库）
$TOK = (Get-Content 'D:\claude\Nora\scripts\.phone-mcp-token' -Raw).Trim()
$URL = 'https://home.rainaki.top:8900/mcp'
$OUT = 'D:\claude\Nora\scripts'

Write-Host "=== 1. tools/list: photos_review description ==="
$r1 = & curl.exe -s --max-time 30 -X POST $URL -H "Content-Type: application/json" -H "Authorization: Bearer $TOK" --data-binary "@$OUT\probe-list.json"
$r1 | Out-File "$OUT\list-out.json" -Encoding UTF8

$kwCols = [string][char]0x5217 + [string][char]0x6570 + [string][char]0x6BD4
$kwCoarse = [string][char]0x7C97 + [string][char]0x7B5B
foreach ($kw in @('tile=160', $kwCols, $kwCoarse, '384-512')) {
  Write-Host ("  contains [" + $kw + "]: " + $r1.Contains($kw))
}

Write-Host ""
Write-Host "=== 2. photos_review default call ==="
$r2 = & curl.exe -s --max-time 90 -X POST $URL -H "Content-Type: application/json" -H "Authorization: Bearer $TOK" --data-binary "@$OUT\probe-review.json"
$r2 | Out-File "$OUT\review-out.json" -Encoding UTF8
Write-Host ("  response chars: " + $r2.Length)
$obj = $null
try { $obj = $r2 | ConvertFrom-Json } catch { Write-Host ("  parse failed: " + $_.Exception.Message) }
if ($obj -ne $null) {
  foreach ($t in $obj.result.content) {
    if ($t.type -eq 'text') {
      $txt = $t.text
      Write-Host "  --- text block (first 800 chars) ---"
      Write-Host ($txt.Substring(0, [Math]::Min(800, $txt.Length)))
    }
  }
}
