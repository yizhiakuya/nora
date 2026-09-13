param([string]$File, [int]$Start, [int]$Count)
$c = Get-Content $File -Encoding UTF8
Write-Host ("total lines: " + $c.Count)
$end = [Math]::Min($Start + $Count - 1, $c.Count - 1)
for ($i = $Start; $i -le $end; $i++) {
  Write-Host (($i + 1).ToString().PadLeft(5) + '| ' + $c[$i])
}
