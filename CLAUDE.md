# Nora — 个人工作台

RAG 知识库 + SQL/日志工具调用的对话 Agent + 数据源/环境/自动任务管理。

## 仓库结构

- `nora-api/` — Maven 多模块 Java 21 / Spring Boot 3.3 微服务（详见 nora-api/CLAUDE.md）
- `nora-web/` — React 18 + Vite(3001) + Tailwind + shadcn 风格 ui + Zustand persist（详见 nora-web/CLAUDE.md）
- 基础设施:Docker(nora-postgres/nora-redis/nora-nacos),Flyway 迁移在各服务 `db/migration`

## 常用命令

```bash
# 后端(在 nora-api 目录)
mvn -q -pl services/<svc> package -DskipTests
(java -jar services/<svc>/target/<svc>-0.1.0-SNAPSHOT.jar > <svc>.stdout.log 2> <svc>.stderr.log &)

# 前端(在 nora-web 目录)
pnpm dev                # VITE_USE_BACKEND=true 走真实后端
pnpm exec tsc --noEmit  # 类型检查
pnpm exec vitest run    # 单测
```

- 前端开发服务统一用内置浏览器预览启动(name=`nora-web`, 端口 3001, 配置见 `.claude/launch.json`),不要用裸 `pnpm dev` 后台进程;截图/控制台检查直接在内置浏览器完成

## 硬性规则(Windows)

- **重启后端服务前必须先停旧进程**:jar 被锁会导致 repackage 失败(Unable to rename)。`netstat -ano | grep :<port>` 找 PID → `powershell Stop-Process -Id <pid> -Force`
- curl 测本地 SSE 时中文 body 会 UTF-8 编码错,用 `printf > file --data-binary @file`

## 约定

- **单测不写断言**(2026-09-12 用户明确要求):单元测试仅作冒烟——执行代码路径、不校验结果(抛异常即失败);行为正确性由 E2E 实测验证(浏览器/curl 打真实服务)。写测试时不要加 assertEquals/verify/expect 等断言;已有的断言在改动相关文件时顺手移除
- **代码查找优先用知识图谱**:codebase-memory MCP 已为仓库建索引(项目名 `D-claude-Nora`)——找符号用 `search_graph`、查调用链用 `trace_path`、取源码用 `get_code_snippet`、复杂多跳用 `query_graph`、先摸架构用 `get_architecture`;仅在字面文本/非代码内容、或图谱覆盖不足时才回退 Grep/Glob
- 代码注释、UI 文案用中文;错误消息带可操作的 hint
- 前端 API 统一走 `src/lib/api/client.ts` 的 envelope(`{code,data,message}`,code=0 为成功)
- `USE_BACKEND`(VITE_USE_BACKEND)开关控制本地 mock / 真实后端,新功能两条路都要能走
