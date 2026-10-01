# E2E: 验证「占位标题先落库」这一阶段（AI 标题会覆盖它，所以必须在 AI 返回前采样）。
#
# 做法：把 SSE 请求放到后台跑，立刻高频轮询 /sessions，捕捉标题从占位 → AI 的跳变。
# 这样能同时证明两点：占位标题形态正确（约 20 字 + …）、且确实被 AI 标题替换。
#
# 用法: powershell -ExecutionPolicy Bypass -File scripts/e2e-placeholder-title.ps1
$ErrorActionPreference = "Stop"
$base = "http://localhost:18083/api/chat"
$sessionId = "sess-ph-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()

$long = "帮我分析一下手机相册里最近拍的猫的照片都分布在哪些相册里，顺便统计一下总共有多少张，" +
        "然后告诉我这些照片的时间跨度大概有多长，最后给出一个整理建议。" + ("补充" * 40)
Write-Host ("首条消息: {0} 字符" -f $long.Length)

$body = @{ content = $long; model = $null; reasoningLevel = "none"; permissionMode = "full" } | ConvertTo-Json -Compress
$tmp = Join-Path $env:TEMP "ph-body.json"
[System.IO.File]::WriteAllText($tmp, $body, (New-Object System.Text.UTF8Encoding($false)))

# 后台发请求（curl 会一直读到流结束，不能阻塞采样）
$job = Start-Job -ScriptBlock {
    param($base, $sessionId, $tmp)
    & curl.exe -sS --noproxy "*" --max-time 180 -N -X POST "$base/sessions/$sessionId/messages" `
        -H "Content-Type: application/json" --data-binary "@$tmp" | Out-Null
} -ArgumentList $base, $sessionId, $tmp

Write-Host "采样标题变化:"
$seen = @()
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Milliseconds 250
    try {
        $resp = curl.exe -sS --noproxy "*" --max-time 5 "$base/sessions"
        $row = ($resp | ConvertFrom-Json).data | Where-Object { $_.id -eq $sessionId }
        if ($row) {
            $key = "{0}|{1}" -f $row.title, $row.titleGenerated
            if ($seen -notcontains $key) {
                $seen += $key
                Write-Host ("  +{0,5}ms  title='{1}'  generated={2}  len={3}" -f
                    ($i * 250), $row.title, $row.titleGenerated, $row.title.Length)
            }
            if ($row.titleGenerated) { break }
        }
    } catch { }
}

Stop-Job $job -ErrorAction SilentlyContinue
Remove-Job $job -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host ("共观察到 {0} 个标题状态" -f $seen.Count)
Write-Host "sessionId = $sessionId"
