---
description: 构建、重启并验证 Nora 单应用后端
argument-hint: [build|restart|status]（缺省 build）
allowed-tools: Bash, Read, Grep
---

# 重启 Nora 后端

后端是一个 Spring Boot 应用，默认端口 18080，不再接受服务名参数。

1. 先检查是否存在用户正在执行的 Run；重启会影响全部后端能力。遵循当前用户授权范围。
2. 仓库根目录运行 `./nora.sh build`，依次停止本应用、执行 Maven verify、启动并等待健康检查。只重启已有产物用 `./nora.sh restart`。
3. Windows 原生入口是 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/backend.ps1 -Action build`。启动器按完整 jar 路径核对进程归属，端口被其他进程占用时直接报错。
4. 检查 `/actuator/health`、`logs/nora.stdout.log`、`logs/nora.stderr.log` 和受影响的真实 API；构建或启动失败时停止流程。

产物：`nora-api/target/nora-api-0.1.0-SNAPSHOT.jar`。Java 21、Maven 3.9+ 必须可用。
Docker 部署使用 `nora-api/docker-compose.prod.yml`，操作见 `docs/deployment.md`，不使用本地启动器控制容器。

汇报构建、健康检查与业务验收结果；不得只凭进程存在判定启动成功。
