$dir = 'D:\claude\Nora\logs'
$sessions = @('sess-1789282409451','sess-1789299517901')

foreach ($sid in $sessions) {
  Write-Host "===================== $sid ====================="
  $reviews = New-Object System.Collections.Generic.List[string]
  $contents = New-Object System.Collections.Generic.List[string]
  $bodies = New-Object System.Collections.Generic.List[string]
  $calib = New-Object System.Collections.Generic.List[string]

  Get-ChildItem $dir -Filter 'agent-service-text*.log*' | Sort-Object LastWriteTime | ForEach-Object {
    $isGz = $_.Extension -eq '.gz'
    if ($isGz) {
      $fs = [System.IO.File]::OpenRead($_.FullName)
      $gs = New-Object System.IO.Compression.GZipStream($fs, [System.IO.Compression.CompressionMode]::Decompress)
      $sr = New-Object System.IO.StreamReader($gs, [System.Text.Encoding]::UTF8)
    } else {
      $fs = New-Object System.IO.FileStream($_.FullName, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
      $sr = New-Object System.IO.StreamReader($fs, [System.Text.Encoding]::UTF8)
    }
    while (-not $sr.EndOfStream) {
      $l = $sr.ReadLine()
      if ($l -notmatch $sid) { continue }
      if ($l -match 'tool call: mcp__phone__photos_review') { $reviews.Add($l) }
      elseif ($l -match 'tool call: mcp__phone__photo_content') { $contents.Add($l) }
      elseif ($l -match 'LLM upstream request') { $bodies.Add($l) }
      elseif ($l -match 'context estimate') { $calib.Add($l) }
    }
    $sr.Close()
    if ($isGz) { $gs.Close() }
    $fs.Close()
  }

  Write-Host ("photos_review: " + $reviews.Count + " | photo_content: " + $contents.Count + " | request bodies: " + $bodies.Count)
  Write-Host "--- photos_review calls (full args) ---"
  foreach ($l in $reviews) {
    $i = $l.IndexOf('tool call: ')
    if ($i -ge 0) {
      $seg = $l.Substring($i + 11)
      if ($seg.Length -gt 260) { $seg = $seg.Substring(0, 260) }
      Write-Host ("  " + $seg)
    }
  }
  Write-Host "--- photo_content ids (ordered) ---"
  $ids = @()
  foreach ($l in $contents) {
    if ($l -match 'args=\{"id":(\d+)\}') { $ids += $Matches[1] }
  }
  Write-Host ("  count=" + $ids.Count)
  Write-Host ("  " + ($ids -join ', '))
  Write-Host "--- request bodies ---"
  $marker = [string][char]0x65E9 + [string][char]0x671F + [string][char]0x56FE + [string][char]0x7247 + [string][char]0x5DF2 + [string][char]0x7701 + [string][char]0x7565
  $k = 0
  foreach ($l in $bodies) {
    $k++
    $body = $l.Substring($l.IndexOf('body=') + 5)
    $imgs = ([regex]::Matches($body, 'data:image/')).Count
    $ts = if ($l.Length -gt 19) { $l.Substring(11, 8) } else { '?' }
    Write-Host ("  #" + $k + " " + $ts + " body=" + [Math]::Round($body.Length / 1024) + "K imgs=" + $imgs + " recycledMarker=" + $body.Contains($marker))
  }
  Write-Host "--- calibration ---"
  foreach ($l in $calib) {
    $i = $l.IndexOf('context estimate')
    if ($i -ge 0) { Write-Host ("  " + $l.Substring($i)) }
  }
  Write-Host ""
}
