@echo off
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
echo === version ===
%ADB% shell dumpsys package com.nora.phonealbum | findstr /C:"versionName" /C:"versionCode"
echo === foreground service ===
%ADB% shell dumpsys activity services com.nora.phonealbum | findstr /C:"ServiceRecord" /C:"isForeground" /C:"startRequested"
echo === battery optimization ===
%ADB% shell dumpsys deviceidle whitelist ^| findstr /C:"com.nora.phonealbum"
echo === app standby bucket ===
%ADB% shell am get-standby-bucket com.nora.phonealbum
echo === process ===
%ADB% shell "ps -A | grep phonealbum"
echo === wakelock ===
%ADB% shell dumpsys power | findstr /C:"mWakeLockSummary" /C:"PhoneAlbum"
