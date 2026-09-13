@echo off
REM 用项目标准方式启动后端: nora.sh start (启动全部 7 服务 + 健康轮询)
REM 依赖: JDK 21 在 D:\tools\jdk-21,git bash 提供 bash
set "JAVA_HOME=D:\tools\jdk-21"
set "PATH=D:\tools\jdk-21\bin;%PATH%"
"C:\Program Files\Git\bin\bash.exe" -lc "cd /d/claude/Nora && ./nora.sh start"
