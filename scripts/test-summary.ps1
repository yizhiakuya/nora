$dir = 'D:\claude\Nora\nora-api\services\agent-service\target\surefire-reports'
Get-ChildItem $dir -ErrorAction SilentlyContinue | Select-Object Name, LastWriteTime | Format-Table -AutoSize
Write-Host "--- summaries ---"
Get-ChildItem "$dir\*.txt" -ErrorAction SilentlyContinue | ForEach-Object {
  Write-Host ("== " + $_.Name)
  Get-Content $_.FullName | Select-Object -First 4 | ForEach-Object { Write-Host ("   " + $_) }
}
Write-Host "--- BUILD markers in log? check mvn exit ---"
