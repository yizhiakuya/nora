# Token 从本地密钥文件读取（.gitignore 已忽略，勿把 token 写进仓库）
$TOK = (Get-Content 'D:\claude\Nora\scripts\.phone-mcp-token' -Raw).Trim()
$URL = 'https://home.rainaki.top:8900/mcp'
$OUT = 'D:\claude\Nora\scripts'

# 36 格 tile=160 cols=6 -> 整图 960x960, 到手仍 160px -> 应触发强警告
$r = & curl.exe -s --max-time 120 -X POST $URL -H "Content-Type: application/json" -H "Authorization: Bearer $TOK" --data-binary "@$OUT\probe-review36.json"
$r | Out-File "$OUT\review36-out.json" -Encoding UTF8
Write-Host ("response chars: " + $r.Length)
$obj = $null
try { $obj = $r | ConvertFrom-Json } catch { Write-Host ("parse failed: " + $_.Exception.Message) }
if ($obj -ne $null) {
  foreach ($t in $obj.result.content) {
    if ($t.type -eq 'text') {
      Write-Host "--- text (first 600) ---"
      Write-Host ($t.text.Substring(0, [Math]::Min(600, $t.text.Length)))
    }
  }
}
