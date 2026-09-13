$sid = 'sess-' + [DateTimeOffset]::Now.ToUnixTimeMilliseconds()
Set-Content -Path 'D:\claude\Nora\scripts\e2e-sid.txt' -Value $sid -Encoding ASCII
# payload: rewrite as UTF-8 without BOM (JSON parsers reject BOM)
$p = 'D:\claude\Nora\scripts\e2e-payload.json'
$txt = [System.IO.File]::ReadAllText($p)
[System.IO.File]::WriteAllText($p, $txt, (New-Object System.Text.UTF8Encoding($false)))
$out = 'D:\claude\Nora\scripts\e2e-sse.txt'
$err = 'D:\claude\Nora\scripts\e2e-sse.err'
Remove-Item $out,$err -ErrorAction SilentlyContinue
$p2 = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c','D:\claude\Nora\scripts\e2e-run.cmd',$sid) -PassThru -WindowStyle Hidden
Write-Host ("session: " + $sid)
Write-Host ("runner pid: " + $p2.Id)
