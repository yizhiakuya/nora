@echo off
REM Start all Nora backend services and check their health.
REM Requires JDK 21 at D:\tools\jdk-21 and Git Bash.
set "JAVA_HOME=D:\tools\jdk-21"
set "PATH=D:\tools\jdk-21\bin;%PATH%"
"C:\Program Files\Git\bin\bash.exe" -lc "cd /d/claude/Nora && ./nora.sh start"
