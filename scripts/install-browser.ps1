$ErrorActionPreference = 'Stop'
$taskRepository = Split-Path -Parent $PSScriptRoot
$taskApi = Join-Path $taskRepository 'nora-api'
$taskJava = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
Push-Location $taskApi
try {
    & mvn '-q' 'dependency:build-classpath' '-Dmdep.outputFile=target/browser-classpath.txt' '-DincludeScope=runtime'
    if ($LASTEXITCODE -ne 0) { throw 'Browser dependency resolution failed' }
    $taskClasspath = (Get-Content -LiteralPath 'target/browser-classpath.txt' -Raw).Trim()
    & $taskJava '-cp' $taskClasspath 'com.microsoft.playwright.CLI' 'install' 'chromium'
    if ($LASTEXITCODE -ne 0) { throw 'Chromium installation failed' }
} finally {
    Pop-Location
}
