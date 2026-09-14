# 清理本机 Nora 开发环境的遗留文件。
#
# 只清「已被取代、确认无引用」的东西；每一项都有判断依据，不做模糊删除。
# 默认 dry-run（只报告），加 -Apply 才真删。
#
# 用法:
#   powershell -ExecutionPolicy Bypass -File scripts/cleanup-local.ps1          # 预览
#   powershell -ExecutionPolicy Bypass -File scripts/cleanup-local.ps1 -Apply   # 执行
param([switch]$Apply)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$totalFreed = 0

function Report($label, $bytes) {
    $mb = [math]::Round($bytes / 1MB, 1)
    Write-Host ("  {0,-46} {1,8:N1} MB" -f $label, $mb)
}

function Remove-IfFree($path) {
    # 先判定是否真被进程占用：被锁的文件删不掉也不该删（可能是活跃日志）
    try {
        $s = [System.IO.File]::Open($path, 'Open', 'ReadWrite', 'None')
        $s.Close()
    } catch {
        Write-Host ("  跳过（被占用，可能是活跃日志）: $path")
        return 0
    }
    $len = (Get-Item $path).Length
    if ($Apply) {
        Remove-Item $path -Force
        Write-Host ("  已删除: $path")
    } else {
        Write-Host ("  待删除: $path")
    }
    return $len
}

Write-Host "=== 1. 旧启动方式遗留的孤儿日志 ==="
Write-Host "（nora.sh 的 LOGDIR 是 nora-api/，根目录这些是历史启动脚本留下的，不再被写入）"
$orphanLogs = Get-ChildItem "$root/*.log" -File -ErrorAction SilentlyContinue |
    Where-Object { $_.Length -gt 1MB }
foreach ($f in $orphanLogs) {
    # 双重判断：最后写入早于今天 且 当前可独占打开（无进程持有）
    $stale = $f.LastWriteTime.Date -lt (Get-Date).Date
    if ($stale) {
        Report $f.Name $f.Length
        $totalFreed += Remove-IfFree $f.FullName
    } else {
        Write-Host ("  保留（今天仍在写入）: $($f.Name)  $([math]::Round($f.Length/1MB,1)) MB")
    }
}

Write-Host ""
Write-Host "=== 2. 未跟踪的临时截图 ==="
$strayPng = Join-Path $root "scripts/browser-start.png"
if (Test-Path $strayPng) {
    # 已确认全仓无引用（grep browser-start 无结果）
    Report "scripts/browser-start.png" (Get-Item $strayPng).Length
    $totalFreed += Remove-IfFree $strayPng
}

Write-Host ""
Write-Host "=== 3. 前端构建缓存（可重建，但会拖慢下次构建，默认保留）==="
$viteCache = Join-Path $root "nora-web/node_modules/.vite"
if (Test-Path $viteCache) {
    $sz = (Get-ChildItem $viteCache -Recurse -File | Measure-Object Length -Sum).Sum
    Write-Host ("  nora-web/node_modules/.vite  {0,8:N1} MB  （如需清理请手动删）" -f ($sz / 1MB))
}

Write-Host ""
if ($Apply) {
    Write-Host ("=== 已清理，释放约 {0:N1} MB ===" -f ($totalFreed / 1MB))
} else {
    Write-Host ("=== 预览模式：将释放约 {0:N1} MB。加 -Apply 执行 ===" -f ($totalFreed / 1MB))
}
