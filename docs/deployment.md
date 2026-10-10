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

### megumin HTTPS 入口

megumin 使用 `https://nora.rainaki.top:13001/`。域名沿用 `*.rainaki.top` 的 DNS only 解析,由现有 DDNS 更新公网地址。宿主机 Nginx 复用 `/etc/nginx/certs/rainaki.pem` 和 `rainaki.key` 通配证书,现有 acme.sh 续期任务会自动重载 Nginx。

`/opt/nora/.env` 和 `/opt/nora/repo/nora-api/.env` 中设置 `WEB_PORT=127.0.0.1:13002`:容器 HTTP 仅监听回环地址,宿主机 Nginx 在 13001 提供 HTTPS。入口配置见 `scripts/nginx-nora.conf`,部署到 `/etc/nginx/sites-available/nora`,并链接到 `sites-enabled/nora`。API 沿用 `NORA_AUTH_TOKEN` 认证,上传与 SSE 流通过该入口转发。

```bash
docker compose --env-file /opt/nora/.env -f /opt/nora/repo/nora-api/docker-compose.prod.yml up -d --no-deps nora-web
nginx -t && systemctl reload nginx
curl --fail --silent --show-error --head --resolve nora.rainaki.top:13001:127.0.0.1 https://nora.rainaki.top:13001/
```

路由器转发 TCP 13001 到 `192.168.0.109:13001`,并加入 `/root/ensure-port-mappings.sh` 的现有十分钟自愈检查。旧 HTTP 入口会重定向到 HTTPS 地址。

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

### 内置浏览器运行环境

Agent 镜像在构建时通过 jar 内的 Playwright CLI 安装 Chromium headless shell 和 Linux 依赖，浏览器版本与实际 Java 依赖一致。标准 `Dockerfile` 与 `Dockerfile.prebuilt` 都执行安装；运行时以 `nora` 用户从 `/ms-playwright` 启动，无需在服务器手动安装或首次请求时下载浏览器。浏览器登录状态、下载和采集成果仍保存在原有 `nora_nora-workspace` 卷中。

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

### megumin 更新记录（2026-10-07）

发布目录为 `/opt/nora/releases/20261007-214722`，本次更新 Agent 与前端镜像，数据库迁移至 V36。部署前备份位于 `/opt/nora/backups/20261007-214722`，包含数据库、文件、工作区及运行配置；旧镜像保留 `before-20261007-214722` 标签。

原有会话及其余配置保留，仅按用户确认同步本机 `nora-a` 的可用模型密钥。20 项实测通过：HTTPS 页面、静态资源、API 鉴权、服务器 Chromium、模型流式回复与消息保存，13 个容器均健康。验收记录保存在发布目录的 `verification.json`。

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
| 终端工具(仅注册 Agent 所在平台) | `powershell`(内置 pwsh 7) | `sh`(`/bin/sh`) | `sh`(`/bin/sh`) |
| MCP STDIO(本地进程) | ✅(npx.cmd) | ✅(npx) | 取决于镜像内命令 |
| 容器管理(env-service) | Docker Desktop | Docker | 已默认挂载 docker.sock(可移除) |
| 工作区/日志路径 | 相对 cwd(可配) | 相对 cwd | `/app/...` 卷内 |

**env-service 管理宿主机 Docker**:`docker-compose.prod.yml` 已默认挂载 `/var/run/docker.sock`(单用户自部署的既定设计——环境控制台的容器启停/日志与 agent 的 `manage_container` 工具依赖它;镜像内置 docker 静态 CLI)。

> 注意:挂载 docker.sock 等于把宿主机 Docker 控制权交给该容器。多人共享环境请在 compose 中移除 `env-service` 的该挂载行,容器管理功能随之不可用。

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

## megumin 平台终端修复(2026-10-08)

发布 `20261008-084113` 更新 Agent 与前端，终端注册由后端实际 OS 决定，移除模型的 `shell` 选择参数。Flyway V37 同步内置手册；已有会话保留原执行记录与展示。

Windows 和 Linux 真模型验收通过：自动选用已注册工具、同轮命令并发、失败隔离、ASK 审批与取消。13 个容器健康，其余 11 个容器未重建；`sess-1791419046617`、模型服务商和运行配置校验一致。记录：`logs/native-terminal-20261008/`。

备份位于 `/opt/nora/backups/20261008-084113`；更新前 Agent/Web 镜像保留为 `before-20261008-084113` 标签。

## megumin Agent 配置页面(2026-10-08)

`/agent` 集中管理默认行为、模型、指令与记忆、技能、工具连接、环境变量及网络；应用外观等保留在 `/settings`。旧配置入口自动跳转，模型子标签保留。

gateway 发布 `20261008-092122`，补齐旧部署缺失的 `/api/user-preferences` 路由；最终 web 发布 `20261008-092901`，包含隐藏配置页滚动条的调整。Agent 后端及其余服务未重建，数据库迁移仍为 V37。真实 API 和浏览器验收通过，13 个容器健康，原会话、服务商与运行配置校验一致。

备份 `/opt/nora/backups/20261008-092122` 包含数据库、文件、工作区和配置；更新前 gateway/web 镜像保留 `before-20261008-092122` 标签。发布记录与 API 验收脚本位于 `/opt/nora/releases/20261008-092122`，最终 web 产物位于 `/opt/nora/releases/20261008-092901`。

## megumin 上下文来源与回读修复（2026-10-08）

发布 `20261008-101155` 仅重建 Agent，清理默认上下文中的失效手册引用，按当前进程 PATH 注入执行账户和可定位的运行时，长工具记录支持字符分页。已有默认工作区规则及旧临时记忆已备份修正；用户定制、原会话、模型与 MCP 配置保留，数据库仍为 V37，其余容器未重建。

163 项后端冒烟、本机 Windows 与 megumin Linux 真模型验收通过。验收覆盖实际账户/PATH、按行与字符分页、现有 Context7 调用。发布目录 `/opt/nora/releases/20261008-101155`，备份 `/opt/nora/backups/20261008-101155`，旧镜像标签 `before-20261008-101155`。原会话在途问答已停止并保存步骤，后续可在原会话继续；详细记录见 `docs/dev/agent-context-sources-2026-10-08.md`。

## megumin 命令后台执行与任务面板（2026-10-08）

发布 `20261008-113331` 更新 Agent/Web，数据库迁移至 V38。命令默认等待 3 秒后返回执行 ID，后台进程独立运行；总时限默认 30 分钟，可在 Agent 设置中调整。统一侧面工作区加入后台任务标签，支持实时输出、同一任务查询/停止和完整日志回读。Agent 容器启用 init，并明确提供 setsid/kill 进程组清理所需工具。

Windows 和 Linux 各 17 项真实进程检查通过；本机与生产各 14 项真实模型/API 验收通过，覆盖后台继续交流、按轮次取消、日志和历史状态。前端类型检查、Lint、126 项冒烟及生产构建、后端 reactor 冒烟通过。本机浏览器验证任务面板；生产浏览器控制超时，线上验收结论限于真实 API/模型及发布健康检查。

部署前备份 `/opt/nora/backups/20261008-113331`，旧 Agent/Web 镜像保留 `before-20261008-113331`；发布与验收记录位于 `/opt/nora/releases/20261008-113331`。更新前后原会话、模型服务商及 MCP 配置校验一致，其余容器未重建。设计及验收细节见 `docs/dev/command-tool-design-2026-10-08.md`。

## megumin Agent 环境变量工具（2026-10-08）

发布 `20261008-144119` 更新 Agent/Web。新增原生 `manage_env_vars list/set/delete`，与设置页、命令注入及 MCP `${KEY}` 引用共用存储；单项更新使用数据库事务锁，避免并发覆盖。密钥默认脱敏，工具参数、审批、结果和持久化步骤隐藏原值；上游日志不记录请求正文。MCP 参数 Schema 的标题兼容修复继续使用 LangChain4j，不移除参数约束。数据库仍为 V38。

本机 Windows 和线上 Linux 各通过 24 项真实模型/API/MCP SDK 验收；前后端质量门通过。本机浏览器通过保存、刷新与遮蔽验收；线上浏览器控制超时，线上通过真实模型/API、HTTPS 前端和 Agent jar 与本次构建一致性及容器健康校验。测试变量已清理，原会话、模型、MCP 与设置前后校验一致，其余容器未重建。

备份 `/opt/nora/backups/20261008-144119`，发布记录 `/opt/nora/releases/20261008-144119`，旧镜像保留 `before-20261008-144119`。详细设计及验收记录见 `docs/dev/agent-env-vars-tool-2026-10-08.md`。密钥沿用既有数据库存储，脱敏不等同于静态加密。

## megumin 工作区开启时的历史会话入口（2026-10-09）

发布 `sidebar-20261009-085154` 仅更新前端。打开右侧工作区时不再强制收起主导航，已收起的侧栏保留展开按钮；手机工作区标题栏复用主导航入口，沿用现有最近会话列表和路由。

类型检查、Lint、126 项冒烟测试及生产构建通过。线上浏览器在 1499 × 892 视口确认工作区打开时最近会话列表保留，并实际选择历史会话、加载原有消息，手动收起和重新展开也通过；手机尺寸和深色模式因浏览器控制超时未完成本次验收。93 个构建文件和公网 HTTPS 首页与本机产物一致，前端容器健康，其余容器未重建，数据库仍为 V38。

发布记录 `/opt/nora/releases/sidebar-20261009-085154/verification.json`，回退镜像标签 `before-sidebar-20261009-085154`。构建保留旧静态资源，供更新前已打开页面继续加载；刷新页面加载新版交互。设计及验证详情见 `docs/dev/side-workspace-2026-10-07.md`。

## megumin 内容链接打开方式（2026-10-09）

最终发布 `sidebar-20261009-101136` 仅更新前端。文档、对话和采集结果中的网页链接默认在共享侧面工作区的内置浏览器中新建标签；「设置 → 通用 → 链接打开方式」可以切换为客户端浏览器的新标签，即时生效并在当前浏览器保存。复用现有浏览器控制和未保存修改保护，同时将浏览器组件存活与轮询生命周期分开，避免切换面板跳过排队命令并留下 busy。

类型检查、Lint、127 项冒烟及生产构建通过。线上真实浏览器验证原报告点击新增两个内置网页标签、原标签保留，切换外部模式后打开客户端标签，刷新仍保留设置；验收后恢复默认值并清理测试标签、恢复原网页 Agent 控制。手机尺寸、深色模式和特殊按键未单独复测。94 个构建文件和公网 HTTPS 首页与本机一致，前端容器健康，其余容器未重建，无数据库迁移。

最终发布记录 `/opt/nora/releases/sidebar-20261009-101136/verification.json`，本次更新前镜像保留为 `nora-web:before-sidebar-20261009-100019`。构建保留旧静态资源，刷新页面加载新版。设计、验证范围及截图见 `docs/dev/link-opening-2026-10-09.md`。

### Agent 执行一致性与工具安全（2026-10-09）

发布 `reliability-20261009-1250` 更新 Agent、Automation 与前端，最终 `reliability-20261009-1320` 补充 Agent 的 JSON 转义凭据脱敏。统一停止与历史修改边界，回答与运行终态原子保存，普通对话采用服务端最新默认设置，工具风险复用实际路径与 MCP 参数解析。定时任务先创建记录再并发执行；SSE 退订与澄清后状态同步一起修正。

数据库、运行配置备份在 `/opt/nora/backups/` 的对应发布目录，旧镜像保留 `before-<release>` 标签。原模型、MCP、Agent 设置及会话保留；只重建受影响组件，沿用既有数据卷。前端质量门、后端打包与冒烟通过；本机 27 项真实验收、线上 11 项真实验收通过。故障注入仅在本机隔离会话执行，生产使用只读命令及停止验收。

发布和验证记录位于 `/opt/nora/releases/` 的上述两个目录；设计、验证范围、单实例边界与截图见 [Agent 可靠性修正](dev/agent-reliability-2026-10-09.md)。刷新页面加载新版前端。

## megumin Token 计量与上下文整理（2026-10-09）

发布 `context-20261009-1450` 更新 Agent/Web，数据库迁移 V39 仅增加运行计量 JSON。Anthropic 完整输入包含普通输入、缓存读取和缓存写入；OpenAI 缓存按输入子集处理。执行中每秒估算并标 `≈`，上游实测到达后替换；同一快照用于预算校准、重连和历史恢复。容量未配置时明确显示 128K 为整理预算。请求前超过 83% 向 65% 整理，保留用户要求与近期完整工具往返，归档旧过程支持原文回读；输出额度继续沿用既有上游策略。

后端 160 项冒烟与打包、前端质量门/128 项冒烟/生产构建、本机 44 项真实 E2E、9 次请求的前端真实事件回放、线上 14 项模型/API 验收通过。浏览器确认生成中更新、历史恢复、手机宽度和深色显示；线上最终计量 8,574 输入（含 8,320 缓存）+6 输出=8,580，与页面一致。

发布前等待运行空闲，原 39 个会话、138 条消息以及模型/MCP/Agent 设置校验一致。13 个容器健康，仅重建 Agent/Web。备份 `/opt/nora/backups/context-20261009-1450`，发布记录 `/opt/nora/releases/context-20261009-1450`，旧镜像保留 `before-context-20261009-1450`。详细根因、未实现的工作集检查点与语义摘要见 [Token 计量审计](dev/context-meter-2026-10-09.md)。刷新页面加载新版。
