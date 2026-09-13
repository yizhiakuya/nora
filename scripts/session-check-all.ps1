param([string]$Sid)
if (-not $Sid) { Write-Host 'need -Sid'; exit 1 }
$dir = 'D:\claude\Nora\logs'
Write-Host ("session: " + $Sid)

$toolCalls = New-Object System.Collections.Generic.List[string]
$calibs = New-Object System.Collections.Generic.List[string]
$reqBodies = New-Object System.Collections.Generic.List[string]

Get-ChildItem $dir -Filter 'agent-service-text*.log*' | Sort-Object LastWriteTime | ForEach-Object {
  if ($_.Extension -eq '.gz') {
    $fs = [System.IO.File]::OpenRead($_.FullName)
    $gs = New-Object System.IO.Compression.GZipStream($fs, [System.IO.Compression.CompressionMode]::Decompress)
    $sr = New-Object System.IO.StreamReader($gs, [System.Text.Encoding]::UTF8)
    while (-not $sr.EndOfStream) {
      $l = $sr.ReadLine()
      if ($l -notmatch $Sid) { continue }
      if ($l -match 'tool call: mcp__phone') { $toolCalls.Add($l) }
      elseif ($l -match 'context estimate') { $calibs.Add($l) }
      elseif ($l -match 'LLM upstream request') { $reqBodies.Add($l) }
    }
    $sr.Close(); $gs.Close(); $fs.Close()
  } else {
    $fs = New-Object System.IO.FileStream($_.FullName, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
    $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
    while (-not $sr.EndOfStream) {
      $l = $sr.ReadLine()
      if ($l -notmatch $Sid) { continue }
      if ($l -match 'tool call: mcp__phone') { $toolCalls.Add($l) }
      elseif ($l -match 'context estimate') { $calibs.Add($l) }
      elseif ($l -match 'LLM upstream request') { $reqBodies.Add($l) }
    }
    $sr.Close(); $fs.Close()
  }
}

Write-Host ("tool calls: " + $toolCalls.Count + "   request bodies: " + $reqBodies.Count + "   calibration: " + $calibs.Count)
Write-Host "--- tool calls ---"
foreach ($l in $toolCalls) {
  if ($l -match 'tool call: (mcp__phone__\w+) \(round=(\d+), args=(\{.*)') {
    $args = $Matches[3]
    if ($args.Length -gt 60) { $args = $args.Substring(0, 60) }
    Write-Host ("  r" + $Matches[2] + " " + $Matches[1].Replace('mcp__phone__','') + " " + $args)
  }
}
Write-Host "--- calibration ---"
foreach ($l in $calibs) {
  $i = $l.IndexOf('context estimate')
  Write-Host ("  " + $l.Substring($i))
}
Write-Host "--- request bodies ---"
$marker = [string][char]0x65E9 + [string][char]0x671F + [string][char]0x56FE + [string][char]0x7247 + [string][char]0x5DF2 + [string][char]0x7701 + [string][char]0x7565
$i = 0
foreach ($l in $reqBodies) {
  $i++
  $body = $l.Substring($l.IndexOf('body=') + 5)
  $imgs = ([regex]::Matches($body, 'data:image/')).Count
  $ts = if ($l.Length -gt 19) { $l.Substring(11, 8) } else { '??' }
  Write-Host ("  #" + $i + " " + $ts + " body=" + [Math]::Round($body.Length / 1024) + "K imgs=" + $imgs + " recycled=" + $body.Contains($marker))
}
