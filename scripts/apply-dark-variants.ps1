# Dark-mode variant injector for hardcoded light-only Tailwind utilities.
#
# Design goals:
# - Light mode stays pixel-identical (we only append dark: companions).
# - Idempotent: safe to re-run; tokens that already carry a dark: variant
#   of the same property are skipped.
# - Deliberately NOT mapped (already correct in dark mode):
#   bg-[#1e1e1e], border-gray-800 (always-dark code editors),
#   text-white / border-white (on colored surfaces),
#   bg-black/* (modal overlays), bg-blue-500, from-blue-500, to-indigo-600,
#   focus/ring blue-500, fill-blue-500/20, text-red-400, bg-red-400, bg-green-500.

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

$map = [ordered]@{
  # --- neutral surfaces ---
  'bg-[#f4f5f7]'         = 'dark:bg-gray-950'
  'bg-[#f8fafc]'         = 'dark:bg-gray-900'
  'bg-[#f0f2f5]'         = 'dark:bg-gray-900'
  'bg-[#fcfcfd]'         = 'dark:bg-gray-900'
  'bg-white/80'          = 'dark:bg-gray-900/80'
  'bg-white/60'          = 'dark:bg-gray-900/60'
  'bg-white'             = 'dark:bg-gray-900'
  'hover:bg-white'       = 'dark:hover:bg-gray-800'
  'bg-gray-50/80'        = 'dark:bg-gray-900/80'
  'bg-gray-50/50'        = 'dark:bg-gray-900/50'
  'bg-gray-50'           = 'dark:bg-gray-900'
  'hover:bg-gray-50/60'  = 'dark:hover:bg-gray-800/60'
  'hover:bg-gray-50'     = 'dark:hover:bg-gray-800'
  'bg-gray-100'          = 'dark:bg-gray-800'
  'hover:bg-gray-100'    = 'dark:hover:bg-gray-700'
  'bg-gray-200/50'       = 'dark:bg-gray-800/50'
  'hover:bg-gray-200/50' = 'dark:hover:bg-gray-700/50'
  'hover:bg-gray-200'    = 'dark:hover:bg-gray-700'
  'bg-gray-200'          = 'dark:bg-gray-800'
  'bg-gray-300'          = 'dark:bg-gray-500'
  'before:bg-gray-200'   = 'dark:before:bg-gray-700'

  # --- neutral text ---
  'text-gray-900'          = 'dark:text-gray-50'
  'hover:text-gray-900'    = 'dark:hover:text-gray-50'
  'text-gray-800'          = 'dark:text-gray-100'
  'hover:text-gray-800'    = 'dark:hover:text-gray-100'
  'text-gray-700'          = 'dark:text-gray-200'
  'hover:text-gray-700'    = 'dark:hover:text-gray-200'
  'focus:text-gray-700'    = 'dark:focus:text-gray-200'
  'text-gray-600'          = 'dark:text-gray-300'
  'hover:text-gray-600'    = 'dark:hover:text-gray-300'
  'group-hover:text-gray-600' = 'dark:group-hover:text-gray-300'
  'text-gray-500'          = 'dark:text-gray-400'
  'group-hover:text-gray-500' = 'dark:group-hover:text-gray-400'
  'text-gray-400'          = 'dark:text-gray-500'
  'text-gray-300'          = 'dark:text-gray-600'
  'placeholder-gray-400'   = 'dark:placeholder-gray-500'

  # --- neutral borders ---
  'border-gray-200'      = 'dark:border-gray-800'
  'hover:border-gray-200' = 'dark:hover:border-gray-800'
  'border-gray-100'      = 'dark:border-gray-800'
  'border-gray-50'       = 'dark:border-gray-800'
  'border-gray-300'      = 'dark:border-gray-700'
  'hover:border-gray-300' = 'dark:hover:border-gray-700'

  # --- blue ---
  'bg-blue-600'            = 'dark:bg-blue-500'
  'hover:bg-blue-700'      = 'dark:hover:bg-blue-600'
  'bg-blue-100'            = 'dark:bg-blue-900/50'
  'bg-blue-50/50'          = 'dark:bg-blue-950/30'
  'bg-blue-50/30'          = 'dark:bg-blue-950/20'
  'bg-blue-50/10'          = 'dark:bg-blue-950/10'
  'bg-blue-50'             = 'dark:bg-blue-950/40'
  'hover:bg-blue-100'      = 'dark:hover:bg-blue-900/50'
  'hover:bg-blue-50/50'    = 'dark:hover:bg-blue-950/30'
  'hover:bg-blue-50/20'    = 'dark:hover:bg-blue-950/20'
  'hover:bg-blue-50'       = 'dark:hover:bg-blue-950/40'
  'focus:bg-blue-50/50'    = 'dark:focus:bg-blue-950/30'
  'focus:bg-blue-50'       = 'dark:focus:bg-blue-950/40'
  'border-blue-200'        = 'dark:border-blue-800'
  'hover:border-blue-200'  = 'dark:hover:border-blue-800'
  'border-blue-100'        = 'dark:border-blue-900'
  'border-blue-50'         = 'dark:border-blue-900'
  'hover:border-blue-300'  = 'dark:hover:border-blue-700'
  'hover:border-blue-400'  = 'dark:hover:border-blue-600'
  'text-blue-800'          = 'dark:text-blue-300'
  'text-blue-700'          = 'dark:text-blue-400'
  'text-blue-600'          = 'dark:text-blue-400'
  'text-blue-500'          = 'dark:text-blue-400'
  'hover:text-blue-600'    = 'dark:hover:text-blue-400'
  'hover:text-blue-500'    = 'dark:hover:text-blue-400'
  'focus:text-blue-700'    = 'dark:focus:text-blue-400'
  'group-hover:text-blue-700' = 'dark:group-hover:text-blue-400'
  'group-hover:text-blue-600' = 'dark:group-hover:text-blue-400'
  'from-blue-50'           = 'dark:from-blue-950/30'
  'to-indigo-50'           = 'dark:to-indigo-950/30'

  # --- red ---
  'bg-red-600'           = 'dark:bg-red-500'
  'hover:bg-red-700'     = 'dark:hover:bg-red-600'
  'bg-red-100'           = 'dark:bg-red-900/50'
  'bg-red-50'            = 'dark:bg-red-950/40'
  'hover:bg-red-50'      = 'dark:hover:bg-red-950/40'
  'focus:bg-red-50'      = 'dark:focus:bg-red-950/40'
  'border-red-200'       = 'dark:border-red-800'
  'border-red-100'       = 'dark:border-red-900'
  'text-red-700'         = 'dark:text-red-400'
  'text-red-600'         = 'dark:text-red-400'
  'text-red-500'         = 'dark:text-red-400'
  'hover:text-red-700'   = 'dark:hover:text-red-400'
  'hover:text-red-500'   = 'dark:hover:text-red-400'
  'focus:text-red-700'   = 'dark:focus:text-red-400'

  # --- green ---
  'bg-green-600'         = 'dark:bg-green-500'
  'hover:bg-green-700'   = 'dark:hover:bg-green-600'
  'bg-green-100'         = 'dark:bg-green-900/50'
  'bg-green-50'          = 'dark:bg-green-950/40'
  'border-green-200'     = 'dark:border-green-800'
  'border-green-100'     = 'dark:border-green-900'
  'text-green-700'       = 'dark:text-green-400'
  'text-green-600'       = 'dark:text-green-400'
  'text-green-500'       = 'dark:text-green-400'

  # --- yellow ---
  'bg-yellow-200/60'     = 'dark:bg-yellow-900/40'
  'text-yellow-900'      = 'dark:text-yellow-200'
  'text-yellow-500'      = 'dark:text-yellow-400'
  'hover:text-yellow-400' = 'dark:hover:text-yellow-300'
  'hover:bg-yellow-50'   = 'dark:hover:bg-yellow-950/40'

  # --- purple ---
  'bg-purple-600'        = 'dark:bg-purple-500'
  'bg-purple-100'        = 'dark:bg-purple-900/50'
  'bg-purple-50'         = 'dark:bg-purple-950/40'
  'border-purple-200'    = 'dark:border-purple-800'
  'hover:border-purple-200' = 'dark:hover:border-purple-800'
  'border-purple-100'    = 'dark:border-purple-900'
  'text-purple-700'      = 'dark:text-purple-400'
  'text-purple-600'      = 'dark:text-purple-400'
  'text-purple-500'      = 'dark:text-purple-400'
  'from-purple-50'       = 'dark:from-purple-950/30'

  # --- orange ---
  'bg-orange-100'        = 'dark:bg-orange-900/50'
  'bg-orange-50'         = 'dark:bg-orange-950/40'
  'border-orange-100'    = 'dark:border-orange-900'
  'text-orange-700'      = 'dark:text-orange-400'
  'text-orange-500'      = 'dark:text-orange-400'

  # --- fuchsia ---
  'to-fuchsia-50'        = 'dark:to-fuchsia-950/30'
}

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$files = Get-ChildItem -Path (Join-Path $root 'src') -Recurse -Include *.tsx,*.ts -File |
  Where-Object { $_.Name -notmatch '\.test\.' }

$total = 0
foreach ($file in $files) {
  # Explicit UTF-8: Windows PowerShell 5.1 would otherwise read/write
  # BOM-less UTF-8 as ANSI and double-encode every non-ASCII character.
  $content = [System.IO.File]::ReadAllText($file.FullName, $utf8NoBom)
  $fileCount = 0
  foreach ($key in $map.Keys) {
    if ($key -match '^((?:[a-z-]+:)*)([a-z]+-)') {
      $property = $Matches[2]
    } else {
      throw "Cannot derive property prefix from key: $key"
    }
    $escaped = [regex]::Escape($key)
    # Skip tokens followed by /<opacity>, longer shades, or an existing
    # dark: companion of the SAME property (idempotency).
    $pattern = "(?<![\w:-])${escaped}(?!(?:[\w/-]|\s+dark:(?:[a-z-]+:)*${property}))"
    $replacement = "$key $($map[$key])"
    $new = [regex]::Replace($content, $pattern, $replacement.Replace('$', '$$'))
    if ($new.Length -ne $content.Length) { $fileCount++ }
    $content = $new
  }
  if ($fileCount -gt 0) {
    [System.IO.File]::WriteAllText($file.FullName, $content, $utf8NoBom)
    Write-Host ("{0}: {1} token groups mapped" -f $file.FullName.Replace("$root\", ''), $fileCount)
    $total += $fileCount
  }
}
Write-Host "Done. Token groups mapped: $total"
