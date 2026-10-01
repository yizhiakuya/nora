# 部署指南

Nora 支持两种部署形态:**Docker 一键部署(推荐)** 与**本地开发部署**。

## 架构总览

```
┌──────────────────────────────────────────────────────────────┐
│  浏览器                                                        │
│    ↓ :13001                                                    │
│  nora-web (nginx:静态文件 + /api 反代)                          │
│    ↓ :18080                                                    │
│  gateway-service ── Nacos 服务发现 ──► 7 个后端服务             │
│    │  file:18081 rag:18082 agent:18083 datasource:18084       │
│    │  env:18085 automation:18086 notification:18087           │
│    ▼                                                          │
│  PostgreSQL:15432  Redis:16379  Nacos:18848  Kafka:29092      │
└──────────────────────────────────────────────────────────────┘
```

**端口设计**:全部使用**高位端口段**(18080+,基础设施 15432+),避开 8080/5432 等常见默认端口的冲突。所有宿主机映射端口均可在 `.env` 中修改。

## 一键部署(Docker)

### 前置条件

- Docker 24+ 与 Docker Compose v2
- 至少 4GB 内存(全部容器约 3GB)

### 步骤

```bash
# 1. 拉取仓库
git clone https://github.com/yizhiakuya/nora.git && cd nora/nora-api

# 2. 配置(必须设置 DB_PASSWORD,按需修改端口/密钥)
cp .env.example .env

# 3. 一键拉起(镜像从 GHCR 拉取,多架构自动匹配)
docker compose -f docker-compose.prod.yml up -d

# 4. 查看状态
docker compose -f docker-compose.prod.yml ps
```

打开 `http://<主机>:13001` 即可使用。首次进入后在「设置 → 模型」配置 LLM 服务商,或在 `.env` 里预置 `NORA_LLM_API_KEY`。

### 镜像

镜像由 GitHub Actions 构建并推送到 GHCR(linux/amd64 + linux/arm64):

```
ghcr.io/yizhiakuya/nora/gateway-service:latest
ghcr.io/yizhiakuya/nora/file-service:latest
ghcr.io/yizhiakuya/nora/rag-service:latest
ghcr.io/yizhiakuya/nora/agent-service:latest
ghcr.io/yizhiakuya/nora/datasource-service:latest
ghcr.io/yizhiakuya/nora/env-service:latest
ghcr.io/yizhiakuya/nora/automation-service:latest
ghcr.io/yizhiakuya/nora/notification-service:latest
ghcr.io/yizhiakuya/nora/nora-web:latest
```

发布新版本:打 tag(`git tag v1.0.0 && git push --tags`)触发构建;部署端 `NORA_TAG=v1.0.0` 切换版本。

### 从源码本地构建镜像

```bash
# 在 nora-api 目录(后端 8 服务 + 基础设施)
docker compose -f docker-compose.prod.yml -f docker-compose.test.yml up -d --build
```

### 数据与备份

数据在 Docker 卷中:

| 卷 | 内容 |
|---|---|
| `nora_pgdata` | 全部业务数据(对话/文件元数据/知识库向量) |
| `nora_kafka-data` | 通知事件 |
| `nora_nora-data` | 文件中心存储 + 媒体缓存 |
| `nora_nora-workspace` | Agent 工作区(记忆) |
| `nora_nora-logs` | 服务日志 |

备份:`docker exec nora-postgres pg_dump -U nora nora > backup.sql`

## 环境变量参考

| 变量 | 默认 | 说明 |
|---|---|---|
| `NORA_TAG` | `latest` | 镜像版本 |
| `GATEWAY_PORT` / `WEB_PORT` | 18080 / 13001 | 对外端口 |
| `POSTGRES_PORT` / `REDIS_PORT` / `NACOS_PORT` | 15432 / 16379 / 18848 | 基础设施端口(仅绑定 127.0.0.1;远程管理走 SSH 隧道) |
| `NORA_AUTH_TOKEN` | 空 | 网关访问令牌;**公网暴露务必设置**(留空=免登录) |
| `NORA_LLM_API_KEY` / `NORA_LLM_BASE_URL` | 空 / openai | LLM 通道(OpenAI 兼容) |
| `NORA_EMBEDDING_API_KEY` / `NORA_EMBEDDING_BASE_URL` | 空 / Jina | 知识库嵌入 |
| `NORA_PROXY_ENABLED` 等 | false | 出站代理(外网受限环境) |
| `DB_USER` / `DB_PASSWORD` | nora / 必填 | 数据库凭据(显式设置强密码) |

## 部署踩坑(2026-10-01 megumin 实机部署记录)

以下问题均为真实部署中实测遇到并已修复,新环境部署时若遇类似症状可对照排查:

| 症状 | 根因 | 修复 |
|---|---|---|
| 配了 `NORA_AUTH_TOKEN` 后 gateway 容器 unhealthy | healthcheck 走 `/actuator/health` 被鉴权拦截 401 | 网关白名单放行 `/actuator/**`(已内置) |
| 容器访问部分外网站点超时(如 Jina),常见大站正常 | 宿主走本地代理(sing-box 等),容器直连被墙 | `.env` 设 `NORA_PROXY_ENABLED=true` + `NORA_PROXY_HOST=<docker0 网关 IP, 如 172.17.0.1>` + 代理端口 |
| 服务启动后报 `chat_session 不存在` | `NORA_DB_URL` 覆盖了各服务默认值,丢了 `currentSchema` | compose 已按服务内置各自 schema 的 URL |
| 上游 404: Model | 静态兜底模型名(`nora.llm.model`)在上游不存在 | `.env` 设 `NORA_LLM_MODEL=<上游可用模型名>`(或在设置页配置 provider) |
| gateway 容器重建后 web 502 | nginx 启动时解析一次容器名并缓存,IP 变更后失效 | nginx 已用 `resolver 127.0.0.11` + 变量化 proxy_pass 重解析 |
| 检索步骤总显示「检索降级(部分通道失败)」 | Flyway 在 schema_rag 的 search_path 下执行 `CREATE EXTENSION pg_trgm`,扩展落在 schema_rag;运行时连接的 search_path(`"$user", public`)看不到 `<<%` 操作符 | rag 迁移 V13 把扩展挪回 public(已内置;已有环境可手动 `ALTER EXTENSION pg_trgm SET SCHEMA public`) |
| 环境控制台纳管源全显示「日志文件不存在」 | 种子路径是开发机 Windows 绝对路径(D:\claude\Nora\logs\...),容器里不存在 | env 迁移 V11 归一化为裸文件名,运行时按 `LOG_PATH` 解析(已内置);且全部服务已挂共享日志卷 `nora-logs:/app/logs` |
| 所有 DOCKER 纳管源报「docker 不可用」 | env-service 容器内无 docker CLI 且未挂 docker.sock | 镜像内置 docker 静态 CLI(28.5.2,amd64/arm64)+ compose 默认挂载 `/var/run/docker.sock`;多人共享环境可移除挂载 |

> 提示:全新部署首次启动即自动完成全部数据库迁移(Flyway),无需手工建表;pgvector 扩展由初始化脚本自动创建。

## 跨平台说明

| 能力 | Windows | Linux / macOS | 容器内 |
|---|---|---|---|
| 服务本体(全部 API) | ✅ | ✅ | ✅ |
| `run_command` 终端工具 | PowerShell(内置 pwsh 7) | 系统 bash | 系统 bash |
| MCP STDIO(本地进程) | ✅(npx.cmd) | ✅(npx) | 取决于镜像内命令 |
| 容器管理(env-service) | Docker Desktop | Docker | 需挂载 docker.sock |
| 工作区/日志路径 | 相对 cwd(可配) | 相对 cwd | `/app/...` 卷内 |

**env-service 管理宿主机 Docker**(可选):在 `docker-compose.prod.yml` 的 `env-service` 增加挂载:

```yaml
volumes:
  - /var/run/docker.sock:/var/run/docker.sock
```

> 注意:挂载 docker.sock 等于把宿主机 Docker 控制权交给该容器,单用户自部署场景可接受,多人共享环境不建议。

## 本地开发部署(不用 Docker 跑服务)

```bash
# 1. 基础设施(容器)
cd nora-api
docker compose --profile dev-basic up -d

# 2. 后端(8 服务,并发拉起 + 健康轮询)
./nora.sh start          # 或 ./nora.sh deploy(自动检测变更并重建)

# 3. 前端
cd ../nora-web
pnpm install && pnpm dev  # http://localhost:3001
```

环境要求:Java 21、Maven 3.9+、Node 22.12+、pnpm 11.23.0。详见仓库根 README。
