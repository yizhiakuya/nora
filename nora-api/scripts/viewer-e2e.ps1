param(
    [string]$BaseUrl = 'http://localhost:3001/api',
    [string]$EnvFile = (Join-Path $PSScriptRoot '../.env.local'),
    [string]$SessionId = ''
)
$ErrorActionPreference = 'Stop'
$headers = @{}
if (Test-Path -LiteralPath $EnvFile) {
    $line = Get-Content -LiteralPath $EnvFile | Where-Object { $_ -match '^NORA_AUTH_TOKEN=' } | Select-Object -First 1
    if ($line) { $headers.Authorization = 'Bearer ' + ($line -split '=', 2)[1].Trim().Trim([char]34).Trim([char]39) }
}
function Request-Viewer($path, $method = 'GET', $body = $null, $extraHeaders = @{}) {
    $request = @{ Uri = "$BaseUrl$path"; Method = $method; Headers = ($headers + $extraHeaders); SkipHttpErrorCheck = $true; NoProxy = $true }
    if ($null -ne $body) { $request.ContentType = 'application/json; charset=utf-8'; $request.Body = ($body | ConvertTo-Json -Depth 12 -Compress) }
    $response = Invoke-WebRequest @request
    $json = if ($response.Headers.'Content-Type' -match 'json') { $response.Content | ConvertFrom-Json } else { $null }
    return @{ status = [int]$response.StatusCode; json = $json; response = $response }
}
function Check-Viewer($condition, $label) {
    if (!$condition) { throw "FAIL: $label" }
    Write-Output "PASS: $label"
}
function Write-Viewer($path, $content, $expectedHash = $null) {
    $result = Request-Viewer '/workspace/file' 'PUT' @{ path = $path; content = $content; expectedHash = $expectedHash }
    if ($result.status -ne 200 -or $result.json.code -ne 0) { throw "File write failed (HTTP $($result.status)): $($result.response.Content)" }
}

if ($SessionId) {
    $messages = Request-Viewer ('/chat/sessions/' + [uri]::EscapeDataString($SessionId) + '/messages')
    $steps = @($messages.json | ForEach-Object { $_.steps } | Where-Object { $_.toolName -eq 'open_file' -and $_.status -in @('completed', 'partial') -and $_.result.files.Count -gt 0 })
    Check-Viewer ($messages.status -eq 200 -and $steps.Count -gt 0) '真实模型 open_file 步骤已持久化'
    $artifacts = Request-Viewer '/saved-artifacts?limit=200'
    foreach ($step in $steps) {
        Check-Viewer ($step.result.focusTarget -in $step.result.files.target) '回放保留规范引用与焦点'
        foreach ($file in $step.result.files | Where-Object { $_.delivery.status -eq 'registered' }) {
            $saved = @($artifacts.json.data | Where-Object { $_.id -eq $file.delivery.artifactId -and $_.path -eq $file.target.Substring(10) -and $_.sessionId -eq $SessionId -and $_.messageKey -eq $step.id })
            Check-Viewer ($saved.Count -eq 1) '成果归属来自真实会话与工具步骤'
        }
    }
    return
}

$fixture = 'reports/viewer-e2e-' + (Get-Date -Format 'yyyyMMdd-HHmmss')
$markdown = "$fixture/验收报告.md"
$csv = "$fixture/带引号和换行.csv"
$html = "$fixture/静态页面.html"
$source = "# 统一文件查看器`n`n这是通过真实服务保存的验收文件。`n`n- 文件交付与原件下载`n- 工作区、画廊和对话统一入口`n- 引用到对话与版本冲突保护`n"
Write-Viewer $markdown $source
Write-Viewer $csv "名称,说明,数量`r`n苹果,`"含,逗号`",2`r`n香蕉,`"第一行`n第二行`",3`r`n葡萄,`"双`"`"引号`",4`r`n"
Write-Viewer $html '<!doctype html><html><head><style>body{font:16px sans-serif;padding:24px}h1{color:#2563eb}</style></head><body><h1>静态 HTML 预览</h1><p id="result">脚本未执行</p><script>document.getElementById("result").textContent="脚本已执行"</script><a href="https://example.invalid/">链接禁止导航</a><img src="https://example.invalid/viewer-probe"></body></html>'
$resolved = Request-Viewer '/viewer/resolve' 'POST' @{ targets = @("workspace:$markdown", "workspace:$csv", "workspace:$html", "workspace:$markdown", 'workspace:../outside.txt') }
Check-Viewer ($resolved.status -eq 200 -and $resolved.json.data.files.Count -eq 3 -and $resolved.json.data.errors.Count -eq 1) '真实文件解析、去重与路径边界'
Check-Viewer (($resolved.json.data.files.previewKind -join ',') -eq 'markdown,csv,html') '格式与能力由服务端识别'
$text = Request-Viewer ('/viewer/text?target=' + [uri]::EscapeDataString("workspace:$markdown"))
Check-Viewer ($text.json.data.content -eq $source -and $text.json.data.hash.Length -eq 64 -and !$text.json.data.truncated) '源码与 SHA-256 版本校验'
Write-Viewer $markdown ($source + "`n版本更新验证。`n") $text.json.data.hash
$conflict = Request-Viewer '/workspace/file' 'PUT' @{ path = $markdown; content = '不能覆盖'; expectedHash = $text.json.data.hash }
Check-Viewer ($conflict.status -eq 409 -and $conflict.json.code -eq 409) '旧版本保存返回 409'
$current = Request-Viewer ('/viewer/text?target=' + [uri]::EscapeDataString("workspace:$markdown"))
Check-Viewer ($current.json.data.content -ne '不能覆盖') '冲突时原件保持完整'
$raw = Request-Viewer ('/workspace/file/raw?path=' + [uri]::EscapeDataString($markdown)) 'GET' $null @{ Range = 'bytes=0-15' }
Check-Viewer ($raw.status -eq 206 -and $raw.response.Headers.'Content-Range' -match '^bytes 0-15/') '原件按 Range 读取'
$download = Request-Viewer ('/workspace/file/raw?download=true&path=' + [uri]::EscapeDataString($markdown))
Check-Viewer ($download.status -eq 200 -and $download.response.Headers.'Content-Disposition' -match 'attachment') '下载真实原件'
$downloadPath = Join-Path $env:TEMP ('nora-viewer-original-' + [guid]::NewGuid().ToString('N') + '.md')
Invoke-WebRequest -Uri ($BaseUrl + '/workspace/file/raw?download=true&path=' + [uri]::EscapeDataString($markdown)) -Headers $headers -NoProxy -OutFile $downloadPath
Check-Viewer ((Get-FileHash -LiteralPath $downloadPath -Algorithm SHA256).Hash.ToLowerInvariant() -eq $current.json.data.hash) '下载字节与原件 SHA-256 一致'
Remove-Item -LiteralPath $downloadPath
$outside = Request-Viewer '/viewer/resolve' 'POST' @{ targets = @('https://example.invalid/file', 'workspace:C:/Windows/win.ini', 'workspace:reports') }
Check-Viewer ($outside.json.data.files.Count -eq 0 -and $outside.json.data.errors.Count -eq 3) '拒绝 URL、绝对路径和目录'
$tooMany = Request-Viewer '/viewer/resolve' 'POST' @{ targets = @(1..21 | ForEach-Object { "workspace:missing-$_" }) }
Check-Viewer ($tooMany.status -eq 400) '单次最多 20 个目标'
$savedBody = @{ kind = 'workspace_file'; path = $markdown; name = '统一文件查看器验收报告' }
$first = Request-Viewer '/saved-artifacts' 'POST' $savedBody
$second = Request-Viewer '/saved-artifacts' 'POST' $savedBody
Check-Viewer ($first.json.data.id -eq $second.json.data.id) '成果登记幂等'
Write-Output "VIEWER_FIXTURE=$fixture"
Write-Output ('VIEWER_URL=http://localhost:3001/files?viewer=' + [uri]::EscapeDataString("workspace:$markdown"))
