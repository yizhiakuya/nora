param([string]$File, [string]$Pattern)
$c = Get-Content $File -Encoding UTF8
for ($i = 0; $i -lt $c.Count; $i++) {
  if ($c[$i] -match $Pattern) {
    Write-Host (($i + 1).ToString().PadLeft(5) + '| ' + $c[$i].Trim())
  }
}
