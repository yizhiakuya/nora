@echo off
REM 标准方式重启 agent-service: 停旧 → 打包(带测试) → javaw 无窗口启动 → 健康检查
REM (先停:Windows 下运行中的 java 锁住 jar,package 会失败)
set "JAVA_HOME=D:\tools\jdk-21"
set "PATH=D:\tools\jdk-21\bin;%PATH%"
set MVN=D:\tools\apache-maven-3.9.16\bin\mvn.cmd

echo === 1. stop agent-service (:8083) ===
for /f "tokens=5" %%p in ('netstat -ano ^| findstr ":8083 " ^| findstr LISTENING') do (
  echo stopping PID %%p
  powershell -Command "Stop-Process -Id %%p -Force"
)
timeout /t 2 /nobreak >nul

echo === 2. package (with tests) ===
cd /d D:\claude\Nora\nora-api
call %MVN% -q -pl services/agent-service clean package
if errorlevel 1 (
  echo BUILD FAILED
  exit /b 1
)
echo jar timestamp:
dir /b services\agent-service\target\agent-service-0.1.0-SNAPSHOT.jar

echo === 3. start (javaw, no window) ===
powershell -NoProfile -ExecutionPolicy Bypass -File D:\claude\Nora\nora-api\start-agent.ps1

echo === 4. health ===
curl.exe -s --noproxy "*" -o NUL -w "agent health: %%{http_code}\n" --max-time 10 http://127.0.0.1:8083/actuator/health
