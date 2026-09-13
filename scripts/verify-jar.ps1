$jar = 'D:\claude\Nora\nora-api\services\agent-service\target\agent-service-0.1.0-SNAPSHOT.jar'
Get-Item $jar | Select-Object LastWriteTime, Length | Format-List

$tmp = Join-Path $env:TEMP ('jarcheck-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $tmp -Force | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($jar)
try {
  $entry = $zip.Entries | Where-Object { $_.FullName -eq 'BOOT-INF/classes/com/nora/agent/service/ChatOrchestrationService.class' }
  if (-not $entry) { Write-Host 'class not found in jar!'; exit 1 }
  $out = Join-Path $tmp 'ChatOrchestrationService.class'
  [System.IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $out, $true)
  Write-Host ("extracted: " + (Get-Item $out).Length + " bytes")
  # class 常量池用 modified-UTF8 存中文,直接用 UTF8 解码整体字节再找 ASCII 符号
  $bytes = [System.IO.File]::ReadAllBytes($out)
  $utf8 = [System.Text.Encoding]::UTF8.GetString($bytes)
  # "早期图片已省略" = U+65E9 U+671F U+56FE U+7247 U+5DF2 U+7701 U+7565
  $cjkMarker = [string][char]0x65E9 + [string][char]0x671F + [string][char]0x56FE + [string][char]0x7247 + [string][char]0x5DF2 + [string][char]0x7701 + [string][char]0x7565
  foreach ($needle in @('toolsOverheadTokens', 'requestOverheadTokens', 'recycleOldImages')) {
    Write-Host ("  contains [" + $needle + "]: " + $utf8.Contains($needle))
  }
  Write-Host ("  contains [image-recycle marker]: " + $utf8.Contains($cjkMarker))
} finally {
  $zip.Dispose()
  Remove-Item $tmp -Recurse -Force -ErrorAction SilentlyContinue
}
