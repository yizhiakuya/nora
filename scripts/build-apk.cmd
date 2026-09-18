@echo off
setlocal
cd /d D:\claude\Nora\phone-album-mcp\android-app
call gradlew.bat :app:assembleDebug --console=plain
if errorlevel 1 (
  echo BUILD FAILED
  exit /b 1
)
echo BUILD OK
dir /b app\build\outputs\apk\debug\app-debug.apk
endlocal
