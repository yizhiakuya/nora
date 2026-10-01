# E2E: 会话标题「占位 → AI 改名」验证。
#
# 覆盖两条关键行为：
#   1. 超长首条消息不再让会话创建失败（旧实现把原文塞进 VARCHAR(255) 会 500）
#   2. 占位标题先落地（首条约 20 字 + …），随后异步被 AI 短标题覆盖
#
# 用法: powershell -ExecutionPolicy Bypass -File scripts/e2e-session-title.ps1
$ErrorActionPreference = "Stop"
$base = "http://localhost:18083/api/chat"
$sessionId = "sess-title-e2e-" + [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()

# 刻意用一条远超 255 字符的消息（旧实现必崩）
$long = "帮我梳理一下这个项目的会话标题生成机制到底是怎么工作的，我需要知道占位标题是什么时候写入的，" +
        "AI 又是在什么时机去覆盖它，另外如果 AI 调用失败会怎样，标题会不会一直卡在占位上，" +
        "还有前端是从哪里拿到更新后的标题的，是轮询还是推送，断线重连之后还拿得到吗，" +
        "顺便再说明一下这个机制对已有的历史会话有没有影响，以及超长消息会不会有问题。" +
        ("补充说明" * 30)
Write-Host ("首条消息长度: {0} 字符 (旧实现 VARCHAR(255) 会失败)" -f $long.Length)

$body = @{ content = $long; model = $null; reasoningLevel = "none"; permissionMode = "full" } | ConvertTo-Json -Compress
$tmp = New-Item -ItemType File -Path (Join-Path $env:TEMP "title-e2e-body.json") -Force
[System.IO.File]::WriteAllText($tmp.FullName, $body, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "发送 SSE 请求..."
$raw = curl.exe -sS --noproxy "*" --max-time 180 -N -X POST `
    "$base/sessions/$sessionId/messages" `
    -H "Content-Type: application/json" `
    --data-binary "@$($tmp.FullName)" 2>&1

$joined = $raw -join "`n"
$events = $joined -split "`n" | Where-Object { $_ -match '^event:' } | ForEach-Object { $_.Substring(6).Trim() }
Write-Host ("收到事件: " + (($events | Select-Object -Unique) -join ", "))

# 只认 「event: title」 紧跟的 data 行——不能用裸 "title":" 匹配：
# 工具步骤(step 事件)的载荷里也有 title 字段,会命中一堆假阳性。
$titleEvents = [regex]::Matches($joined, 'event:\s*title\s*\r?\ndata:\s*(\{[^\n]*\})')
if ($titleEvents.Count -eq 0) {
    Write-Host "（本轮未收到 SSE title 事件——可能标题晚于轮次结束才回来，靠下面的落库轮询兜底）"
}
foreach ($m in $titleEvents) {
    Write-Host ("SSE title 事件: " + $m.Groups[1].Value)
}

Write-Host ""
Write-Host "等待异步标题落库..."
for ($i = 1; $i -le 6; $i++) {
    Start-Sleep -Seconds 2
    $resp = curl.exe -sS --noproxy "*" --max-time 20 "$base/sessions"
    $data = ($resp | ConvertFrom-Json).data
    $row = $data | Where-Object { $_.id -eq $sessionId }
    if ($row) {
        Write-Host ("[{0}] title='{1}' generated={2}" -f $i, $row.title, $row.titleGenerated)
        if ($row.titleGenerated) { break }
    } else {
        Write-Host ("[{0}] 会话尚未出现" -f $i)
    }
}
Write-Host ""
Write-Host "sessionId = $sessionId"
