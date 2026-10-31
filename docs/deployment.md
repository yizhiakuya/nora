# 部署指南

当前后端为一个 Spring Boot 应用，部署边界是单用户、单实例。业务包共享 PostgreSQL，通过服务方法直接调用；不再依赖网关、Nacos、Kafka 或平台 Redis。用户配置的 Redis 数据源仍由 Lettuce 支持。

```text
浏览器 → nora-web（Nginx，13001）→ nora-api（18080）→ PostgreSQL + pgvector
```

## 新部署

需要 Docker Compose v2。仓库根目录执行：

```bash
cp nora-api/.env.example nora-api/.env
# 编辑 .env：设置 DB_PASSWORD、NORA_AUTH_TOKEN 和固定的 NORA_TAG
cd nora-api
docker compose -f docker-compose.prod.yml pull
docker compose -f docker-compose.prod.yml up -d
docker compose -f docker-compose.prod.yml ps
```

浏览器访问 `http://<主机>:13001`。LLM 服务商在「设置 → 模型」配置，数据库是唯一配置来源。知识库嵌入需要 `NORA_EMBEDDING_API_KEY`。镜像分别为 `ghcr.io/yizhiakuya/nora/nora-api:<版本>`、`ghcr.io/yizhiakuya/nora/nora-web:<版本>`；先发布新镜像再部署，旧八服务镜像不能替代新的 nora-api。

源码构建使用 `docker compose -f docker-compose.prod.yml -f docker-compose.test.yml up -d --build`。统一 Dockerfile 默认执行 Maven verify；已有验证产物时可传 `--build-arg ARTIFACT_STAGE=prebuilt`。`nora-api/build-push.sh` 支持 nora-api、nora-web 和 all，默认先验证后端产物，再构建镜像。

镜像安装与 Java Playwright 版本一致的 Chromium headless shell 和系统依赖，运行用户为 nora，浏览器资料保存在工作区卷。容器 Docker socket 的 GID 必须与镜像构建参数 DOCKER_GID 一致（默认 999）；必要时在构建时覆盖，不能靠忽略权限错误修复。

## 从八服务升级

此次是部署拓扑变更，需要一次计划内后端停机。不得让旧服务和新应用同时修改同一数据库；不要执行 `down -v`、删除旧数据卷或重新初始化已有库。

1. 核对现有 Compose project 为 nora，并记录容器、卷挂载、镜像版本、环境变量和宿主 Nginx 配置。若已有部署改过项目名或卷名，先显式映射回原卷，避免误建空库。
2. 备份数据库，以及文件、媒体缓存、工作区、运行配置。备份必须可读；保留旧镜像和旧 Compose 文件用于回退。
3. 等待用户 Run、后台命令和自动任务空闲，停止旧 gateway/file/rag/agent/datasource/env/automation/notification 容器，防止调度重复执行。Redis/Nacos/Kafka 只有确认没有其他消费者后再停；新应用不使用它们。
4. 保持原 DB_USER、DB_PASSWORD、NORA_AUTH_TOKEN、嵌入与代理配置。旧 `GATEWAY_PORT` 改为 `API_PORT`；WEB_PORT 与 POSTGRES_PORT 保持原映射。以原 Compose 项目、原数据库和原数据卷启动 nora-api、nora-web。
5. 检查 `/actuator/health`、鉴权、原会话和工具历史、真实模型回复、文件预览与回收站、知识库、自动任务和环境状态。验收通过后再移除旧容器；旧基础设施卷先保留。

原七个领域 schema、迁移版本及 history 均保留。文件与知识库生命周期现在使用同一事务；启动时仅补完旧 pending_rag_sync 中未送达的状态，失败会阻止启动，不会静默丢弃。旧反思表、版本表等历史数据没有随代码清理删除。

回退前停止新应用，检查新版本是否已产生业务写入。仅回退镜像不能撤销迁移和新写入；需要恢复数据时，必须由当前操作人明确选择备份范围，避免覆盖更新的会话和文件。

## 数据与配置

Compose 保留原项目名 nora 和卷键：

| 卷 | 内容 |
|---|---|
| nora_pgdata | 会话、设置、文件元数据、知识库、自动任务和通知 |
| nora_nora-data | 上传文件、媒体缓存 |
| nora_nora-workspace | Agent 工作区、记忆、浏览器资料 |
| nora_nora-logs | 日志 |

PostgreSQL 只映射到宿主回环地址。数据库导出示例（替换实际数据库用户）：

```bash
docker exec nora-postgres pg_dump -U nora -Fc nora > nora-backup.dump
```

文件和工作区还需独立备份；仅备份数据库不能恢复原始文件。

每次发布尝试使用独立的备份目录；目录已存在时停止并换新名称，不覆盖已有备份。记录备份时的迁移版本，回退后重试也要保留第一次迁移前的备份。包含新迁移的备份不能用作迁移前快照；恢复业务数据前仍须核对备份范围与发布后的写入。

| 变量 | 用途 |
|---|---|
| NORA_TAG | 固定镜像版本 |
| NORA_PTC_IMAGE | 可选 PTC 运行镜像，固定 sha256 digest 并提前拉取；留空禁用 |
| API_PORT / WEB_PORT / POSTGRES_PORT | 默认 18080 / 13001 / 15432，支持宿主绑定地址 |
| DB_USER / DB_PASSWORD | 数据库用户及已有库的密码 |
| NORA_AUTH_TOKEN | 统一访问令牌，留空为免登录 |
| NORA_EMBEDDING_API_KEY / NORA_EMBEDDING_BASE_URL | 向量嵌入服务 |
| NORA_PROXY_ENABLED / HOST / PORT | 出站代理，对应完整变量名前缀 NORA_PROXY_ |

数据库中的模型、MCP、Agent 设置和会话继续沿用。嵌入缓存改为 Caffeine 进程缓存（32 MiB、七天有效期），重启只会失去缓存，不会丢失知识向量或原文件。

## megumin 入口

PTC 启用前拉取经验证的镜像，例如 `docker pull node:22-alpine@sha256:fe4f6d74e20370acb04ad889077d14d0f64a9c8ea6a42bc60cddf1e844699914`，在 `.env` 设置同值 `NORA_PTC_IMAGE` 后重建 API 容器。核对 API 用户可访问 Docker、模式接口 `ptcAvailable=true`，再验证程序及子工具。脚本容器不挂载工作区/socket，副作用走原权限门。限额、恢复和迁移见 [PTC 记录](dev/ptc-mode-design-2026-10-23.md)。

升级保留了本地定制的旧 Compose 时，还须确认 `nora-api.environment` 包含 `NORA_PTC_IMAGE: ${NORA_PTC_IMAGE:-}`；仅修改 `.env` 不会把未映射的变量传入容器。沿用下述生产路径，只输出此配置项，避免显示包含密钥的完整配置：

```bash
docker compose --env-file /opt/nora/.env -f /opt/nora/repo/nora-api/docker-compose.prod.yml config --format json | python3 -c 'import json,sys; print(json.load(sys.stdin)["services"]["nora-api"]["environment"].get("NORA_PTC_IMAGE", ""))'
# 重建后使用 API 的运行用户检查容器内变量与 Docker 镜像访问
docker exec nora-api sh -c 'test -n "$NORA_PTC_IMAGE" && docker image inspect "$NORA_PTC_IMAGE" --format "{{.Id}}"'
```

以上检查和已鉴权的 `GET /api/chat/sessions/{sessionId}/execution-mode` 只确认配置、镜像访问与已有会话模式可读，不能替代隔离环境中的真实 PTC 工具执行验收。

既有入口为 `https://nora.rainaki.top:13001/`。宿主 Nginx 配置见 `scripts/nginx-nora.conf`，证书沿用 `/etc/nginx/certs/rainaki.pem` 和 rainaki.key。保留现有 acme.sh 续期和路由器端口转发。

现有 `.env` 使用 `WEB_PORT=127.0.0.1:13002`，容器 HTTP 只监听回环地址，由宿主 Nginx 提供 HTTPS。生产配置路径为 `/opt/nora/.env`，Compose 位于 `/opt/nora/repo/nora-api/`：

```bash
docker compose --env-file /opt/nora/.env -f /opt/nora/repo/nora-api/docker-compose.prod.yml ps
nginx -t
curl --noproxy '*' --fail --silent --show-error --head --resolve nora.rainaki.top:13001:127.0.0.1 https://nora.rainaki.top:13001/
```

旧 systemd 和静态目录部署脚本已移除，避免误操作现有 Compose 业务。megumin 前后端均运行 `20261029-ptc-1c978dc`（2026-10-31 复核）：后端由 `.env` 的 `NORA_TAG` 固定，前端在服务器 Compose 中固定同一版本。PTC 发布、首次切换回退、备份范围与验收边界见[10 月 29 日记录](dev/deployment-2026-10-29.md)。本站媒体鉴权修复见[10 月 22 日记录](dev/media-auth-2026-10-22.md)，提问中插入请求的修复见[提问等待修复记录](dev/question-steering-2026-10-18.md)。其他部署历史在[开发记录](dev/README.md)。

## 本地开发

Java 21、Maven 3.9+、Node 22.12+、pnpm 11.23.0：

```bash
docker compose -f nora-api/docker-compose.yml up -d
./nora.sh build
cd nora-web
pnpm install
pnpm dev
```

Windows 可用 `scripts/backend.ps1 -Action build`。启动器仅操作已核对路径的本应用进程；占用端口的其他程序不会被自动终止。本机配置使用 NORA_DB_URL、NORA_DB_USERNAME、NORA_DB_PASSWORD 等环境变量，生产 Compose 的 DB_* 变量由 Compose 转换。使用外部全新 PostgreSQL 时先运行 `nora-api/docker/postgres-init.sql`，创建 public.vector；已有库不要重新初始化。

后端重启影响聊天、资料、环境、自动任务等全部功能。LiveTurn、审批和浏览器仍是单实例能力，不能直接把同一数据库连到多个 Nora 应用副本。
