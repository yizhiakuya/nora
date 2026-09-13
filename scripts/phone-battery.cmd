@echo off
set ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe
echo === deviceidle whitelist (full) ===
%ADB% shell dumpsys deviceidle whitelist
echo.
echo === battery stats for app (uid) ===
%ADB% shell dumpsys batterystats --charged com.nora.phonealbum ^| findstr /C:"Estimated power" /C:"Uid" /C:"mAh" /C:"Screen off" /C:"CPU" /C:"Wake lock" /C:"Network"
echo.
echo === network usage ===
%ADB% shell dumpsys netstats detail ^| findstr /C:"uid=10429" | more +0
