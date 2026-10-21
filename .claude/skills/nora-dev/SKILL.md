---
name: nora-dev
description: Nora 本地后端的构建、启动、停止、健康检查与迁移维护。
---

# 开发维护

先读根 AGENTS 与 nora-api/CLAUDE.md。当前只有一个后端应用（18080），需要 PostgreSQL + pgvector。

- 初次：`cd nora-api && docker compose up -d`。
- 构建并启动：仓库根 `./nora.sh build`；仅启动/停止/状态：`./nora.sh start|stop|status`。
- Windows 原生入口：`powershell -File scripts/backend.ps1 -Action build`。需 Java 21、Maven 3.9。
- 手动验证：`cd nora-api && mvn verify`；产物 `target/nora-api-0.1.0-SNAPSHOT.jar`。
- Windows 构建前须停掉运行同一 jar 的进程，不能通过使用旧构建产物绕过锁错误。
- 前端使用 pnpm，固定端口 3001，按 nora-web/AGENTS.md 完成质量检查与浏览器验收。
- 数据库已有 schema 与 Flyway 版本不改，新增迁移放 `nora-api/src/main/resources/db/migration/{domain}`。
- 接口不变；领域内调用 service，禁止重新引入自我 HTTP 调用或独立运行入口。
- 只停止本项目进程，不按端口随意杀死无关程序。生产升级先备份并等待/停止活动 Run，见 docs/deployment.md。
