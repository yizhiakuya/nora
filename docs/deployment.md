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

# 2. 配置(按需修改端口/密钥)
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
| `POSTGRES_PORT` / `REDIS_PORT` / `NACOS_PORT` | 15432 / 16379 / 18848 | 基础设施端口(仅本机访问可不暴露) |
| `NORA_AUTH_TOKEN` | 空 | 网关访问令牌;**公网暴露务必设置**(留空=免登录) |
| `NORA_LLM_API_KEY` / `NORA_LLM_BASE_URL` | 空 / openai | LLM 通道(OpenAI 兼容) |
| `NORA_EMBEDDING_API_KEY` / `NORA_EMBEDDING_BASE_URL` | 空 / Jina | 知识库嵌入 |
| `NORA_PROXY_ENABLED` 等 | false | 出站代理(外网受限环境) |
| `DB_USER` / `DB_PASSWORD` | nora / nora | 数据库凭据 |

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

环境要求:Java 21、Maven 3.9+、Node 20+、pnpm。详见仓库根 README。
