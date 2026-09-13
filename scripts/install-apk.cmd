@echo off
setlocal
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
set "APK=D:\claude\phone-album-mcp\android-app\app\build\outputs\apk\debug\app-debug.apk"
echo === install (replace, keep data) ===
%ADB% install -r "%APK%"
echo.
echo === verify version ===
%ADB% shell dumpsys package com.nora.phonealbum | findstr /C:"versionName"
echo.
echo === restart app ===
%ADB% shell am force-stop com.nora.phonealbum
timeout /t 1 /nobreak >nul
%ADB% shell monkey -p com.nora.phonealbum -c android.intent.category.LAUNCHER 1 >nul 2>&1
timeout /t 6 /nobreak >nul
%ADB% shell "ps -A | grep phonealbum"
endlocal
