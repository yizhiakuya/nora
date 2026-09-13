@echo off
set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
echo === phonealbum in whitelist? ===
%ADB% shell dumpsys deviceidle whitelist | findstr /C:phonealbum
echo (empty = NOT whitelisted)
echo.
echo === standby bucket ===
%ADB% shell am get-standby-bucket com.nora.phonealbum
echo (10=ACTIVE 20=WORKING_SET 30=FREQUENT 40=RARE 45=RESTRICTED 50=NEVER; 5=EXEMPTED)
echo.
echo === battery stats summary ===
%ADB% shell dumpsys batterystats --charged com.nora.phonealbum > "%TEMP%\bstats.txt"
findstr /C:"Estimated power use" "%TEMP%\bstats.txt"
findstr /C:"Uid u0a429" "%TEMP%\bstats.txt"
echo.
echo === recent wakeups/network from app ===
findstr /C:"Wake lock" /C:"Job" /C:"Sync" "%TEMP%\bstats.txt" | more +0
