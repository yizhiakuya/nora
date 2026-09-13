Write-Host "=== Collage.kt constants ==="
$f = 'D:\claude\phone-album-mcp\android-app\app\src\main\java\com\nora\phonealbum\Collage.kt'
$c = Get-Content $f -Encoding UTF8
for ($i = 0; $i -lt $c.Count; $i++) {
  if ($c[$i] -match 'const val|coerceIn|MAX_BYTES|MAX_ITEMS') {
    Write-Host (($i + 1).ToString().PadLeft(4) + '| ' + $c[$i].Trim())
  }
}

Write-Host ""
Write-Host "=== photos_review description (McpProtocol.kt) ==="
$f2 = 'D:\claude\phone-album-mcp\android-app\app\src\main\java\com\nora\phonealbum\McpProtocol.kt'
$c2 = Get-Content $f2 -Encoding UTF8
for ($i = 0; $i -lt $c2.Count; $i++) {
  if ($c2[$i] -match 'photos_review|tile|limit|cols|扫看|格子') {
    Write-Host (($i + 1).ToString().PadLeft(4) + '| ' + $c2[$i].Trim())
  }
}

Write-Host ""
Write-Host "=== build.gradle version ==="
Select-String -Path 'D:\claude\phone-album-mcp\android-app\app\build.gradle.kts' -Pattern 'versionName|versionCode' | ForEach-Object {
  Write-Host $_.Line.Trim()
}
