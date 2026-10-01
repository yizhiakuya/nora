---
description: 重启 Nora 后端服务(停旧进程 → package → 启动 → 确认日志)
argument-hint: [服务名: agent|datasource|rag|file|env|automation] (缺省 agent)
allowed-tools: Bash, Read, Grep
---

# 重启 Nora 后端服务

服务名: $ARGUMENTS(缺省 `agent`)。端口映射: gateway=8080 file=8081 rag=8082 agent=8083 datasource=8084 env=8085。

严格按顺序执行,任何一步失败即停止并报告:

## 1. 停旧进程

```bash
netstat -ano | grep :<端口> | grep LISTENING
```

有 LISTENING 行则取最后一列 PID:

```bash
powershell -Command "Stop-Process -Id <pid> -Force"
```

再 `netstat` 复查确认已释放。无监听则直接进入下一步。

## 2. 打包

在 `nora-api` 目录:

```bash
mvn -q -pl services/<服务名>-service package -DskipTests
```

## 3. 启动

```bash
(cd nora-api && java -jar services/<服务名>-service/target/<服务名>-service-0.1.0-SNAPSHOT.jar > <服务名>-service.stdout.log 2> <服务名>-service.stderr.log &)
```

## 4. 验证

等 8-10 秒后:

1. `netstat -ano | grep :<端口> | grep LISTENING` — 确认新进程监听
2. Read `<服务名>-service.stdout.log` 尾部 — 确认 Spring 启动完成、无异常堆栈
3. gateway 路由经 Nacos 注册,如需全链路可用再确认 nora-nacos 容器在运行

汇报: 停掉的 PID、打包结果、新 PID、启动日志关键行。
