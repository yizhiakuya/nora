<div align="center">

# Nora

**个人 AI 助手** — 用自己的资料和已连接的工具完成具体任务、交付成果,并接住重复性的日常工作

[![Java](https://img.shields.io/badge/Java-21-ED8B00?logo=openjdk&logoColor=white)](nora-api/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3-6DB33F?logo=springboot&logoColor=white)](nora-api/)
[![React](https://img.shields.io/badge/React-18-61DAFB?logo=react&logoColor=black)](nora-web/)
[![TypeScript](https://img.shields.io/badge/TypeScript-5-3178C6?logo=typescript&logoColor=white)](nora-web/)

</div>

---

## 功能演示

<!-- 视频经 GitHub 附件上传后,单独一行即渲染为原生播放器 -->

https://github.com/user-attachments/assets/aa8d18f0-dc0b-4e68-b496-dd1deb121dbb

> 真实浏览器录制的完整操作流(真实后端 + 真实模型对话,无剪辑拼接):**① 手机相册 MCP → ② 对话交付文件闭环 → ③ 知识库问答 → ④ 数据源查询**。分场景短片见 [docs/demo/](docs/demo/)。

## 手机相册 MCP(配套能力)

Nora 可以通过 MCP 连接**手机相册**——照片不用搬到电脑,直接检索、查看、整理手机上的照片。

**演示见上方视频第 1 段**:对话里查看相册列表 → 从「猫」相册挑选照片 → 真猫照片直接在对话中展示。

| 能力 | 说明 |
|---|---|
| **相册检索** | 按时间 / 相册 / 类型 / 文件名检索 · 统计(总数 / 占用 / 按年分布) |
| **拼图扫看** | 一批照片拼成编号缩略图网格(像人翻相册一样先扫一圈,再对候选细看——「找某类照片」的标准流程,如图中的找猫) |
| **照片展示** | 图片内容直接返回,在对话中可见;聊天界面内联显示 |
| **相册整理** | 移动 / 复制 / 重命名 / 删除(进 App 回收站,可恢复)· 新建相册 |
| **两种接入** | 手机主动连中继(换 Wi-Fi 无影响)或局域网直连;照片不出局域网、服务端零存储 |

> 实现为独立配套项目 `phone-album-mcp`(Android App + 中继服务,私有仓库);在 Nora 的「设置 → 连接与工具」里添加这个 MCP 服务器即可启用。

## Nora 是什么

一个**部署在自己机器上的单用户工作台**:与它对话,它会真实地操作工作台完成事情,而不是只给建议。

- **对话即操作**:查数据库、读服务日志、读写文件、跑命令、管理容器与任务,每一步真实执行、结果可核对
- **文件即交付**:报告与表格以真实文件落到工作区,聊天里出卡片,可直接在文件查看器中阅读、编辑、引用回对话
- **资料即上下文**:上传的文件与知识库文档可被引用、被检索,回答带引用来源
- **权限可知可控**:三档权限(请求批准 / 帮我批准 / 完全访问)+ 三级风险(低 / 高 / 严重),高风险操作必须经过你

## 核心能力

| 能力 | 说明 |
|---|---|
| **对话 Agent** | SSE 流式输出 · 思考过程可见 · ReAct 工具循环 · 审批流 · 断线重连 · 上下文自动压缩 |
| **知识库(RAG)** | 文件解析(Tika)→ 分块 → 向量化(Jina v3 / pgvector)· 混合检索(向量 + 关键词)· 资料库分组 · 引用来源 |
| **文件中心** | 上传 / 文件夹组织 / 重命名 / 移动 / 下载 · 统一文件查看器(Markdown / 表格 / 图片 / PDF / HTML 隔离预览)· 「已保存成果」登记 |
| **数据源** | PostgreSQL / MySQL / Redis 连接管理 · Schema 浏览 · 受限只读 SQL + 单条写语句守卫 |
| **自动任务** | 真实日程(每日 / 每周 / 时区)· 无人值守执行 · 执行记录与失败原因 |
| **环境控制台** | Docker 容器管理 · 日志流(SSE)· AI 诊断只读结论 |
| **连接与工具(MCP)** | MCP 服务器管理(远程 / 本地 STDIO)· 工具按需加载 · 图像 / 媒体工具 · [手机相册 MCP](#手机相册-mcp配套能力) |
| **技能** | 可复用的处理方法,AI 按需读取正文执行 · 对话中可自建 |
| **通知中心** | Kafka 事件总线 · 任务完成 / 索引完成 / 进程异常推送 |
| **访问控制** | 网关令牌鉴权(单用户)· 未配置即免登录 |

## 技术架构

```
┌─────────────────────────────────────────────────────────┐
│  浏览器 (nora-web)                                        │
│  React 18 · Vite · Tailwind · Zustand · SSE             │
└────────────────────────┬────────────────────────────────┘
                         │ HTTP / SSE（令牌鉴权）
┌────────────────────────▼────────────────────────────────┐
│  网关 (gateway:18080)  Spring Cloud Gateway + Nacos       │
└──┬────────┬────────┬────────┬────────┬────────┬─────────┘
   │        │        │        │        │        │
┌──▼──┐  ┌──▼──┐  ┌──▼──┐  ┌──▼──┐  ┌──▼──┐  ┌──▼──┐
│file │  │ rag │  │agent│  │data │  │ env │  │auto │
│18081│  │18082│  │18083│  │18084│  │18085│  │18086│
└──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘  └──┬──┘
   │        │        │        │        │        │
┌──▼────────▼────────▼────────▼────────▼────────▼──────┐
│  PostgreSQL 16 + pgvector      Redis         Kafka   │
│  （业务数据 + 向量检索）      （缓存/票据）  （通知总线） │
└───────────────────────────────────────────────────────┘
     另有 notification-service(18087):Kafka → 通知落库
```

| 层 | 选型 |
|---|---|
| 前端 | React 18 · Vite 7 · TypeScript 5 · Tailwind CSS 3 · Zustand 5 |
| 后端 | Java 21 · Spring Boot 3.3 · Spring Cloud Alibaba(Nacos)· Maven 多模块(8 个服务) |
| 数据 | PostgreSQL 16 + pgvector · Redis(可选降级)· Kafka(通知) |
| 模型接入 | OpenAI 兼容协议(自定义 endpoint / key)· 流式 SSE · 思考等级注入 |

## 快速开始

### Docker 一键部署(推荐)

```bash
git clone https://github.com/yizhiakuya/nora.git && cd nora/nora-api
cp .env.example .env                                  # 必须设置 DB_PASSWORD,按需改端口/密钥
docker compose -f docker-compose.prod.yml up -d       # 镜像从 GHCR 拉取
# 打开 http://<主机>:13001
```

完整部署说明(端口设计/环境变量/备份/跨平台)见 [docs/deployment.md](docs/deployment.md)。

### 本地开发

环境要求:**Java 21**、**Maven 3.9+**、**Node 22.12+**、**pnpm 11.23.0**、**Docker**(基础设施)。

```bash
# 1. 基础设施(PG+pgvector / Redis / Nacos / Kafka)
cd nora-api && docker compose --profile dev-basic up -d

# 2. 后端(8 服务,并发拉起 + 健康轮询)
./nora.sh start          # 或 ./nora.sh deploy(自动检测变更并重建)
./nora.sh status         # 端口/健康一览
```

<details>
<summary>不用脚本时的手动方式</summary>

```bash
mvn -q -pl services/gateway-service package -DskipTests
java -jar services/gateway-service/target/gateway-service-0.1.0-SNAPSHOT.jar
# 其余服务同理:file / rag / agent / datasource / env / automation / notification
```

</details>

### 启动前端

```bash
cd nora-web
pnpm install
pnpm dev                 # http://localhost:3001(所有域直接接入真实后端)
```

### 配置模型(首次)

打开 `设置 → 模型`,添加一个 OpenAI 兼容的模型服务商(端点 + API Key),即可开始对话。

<details>
<summary>服务端配置(.env.local,可选)</summary>

`nora-api/.env.local` 支持(最低优先级,真实环境变量优先):

```bash
NORA_AUTH_TOKEN=<访问令牌,留空则免登录>
NORA_EMBEDDING_API_KEY=<Jina 嵌入 Key>
NORA_PROXY_ENABLED=true
NORA_PROXY_HOST=127.0.0.1
NORA_PROXY_PORT=7897
```

</details>

## 质量检查

前端执行 `pnpm typecheck`、`pnpm lint`、`pnpm test`、`pnpm build`;后端执行 `mvn verify`(含单测与 Spotless)。CI 在 PR/分支推送时检查,镜像发布必须先通过同一套检查。

模型配置仅来自「设置 → 模型」保存的数据库记录,无静态环境变量兜底。生产 PostgreSQL/Redis/Nacos 管理端口仅绑定 `127.0.0.1`,远程管理使用 SSH 隧道。

## 目录结构

```
Nora/
├── nora-web/         # 前端 — React 18 + Vite + Tailwind + Zustand
├── nora-api/         # 后端 — Java 21 微服务（8 服务 · Maven 多模块）
│   ├── services/     #   gateway / file / rag / agent / datasource / env / automation / notification
│   ├── common/       #   共享基座（日志/异常/Redis/事件总线）
│   └── docs/         #   架构与设计文档
├── docs/demo/        # 功能演示视频（分场景短片）
├── .claude/          # AI 辅助开发配置（见下节）
└── phone-album-mcp/  # 手机相册 MCP（独立仓库:Android App + 中继服务）
```

## AI 辅助开发（.claude/）

本项目**用 AI 深度参与开发**(Claude Code),开发配置随仓库共享——这是项目工程方法的一部分:

| 位置 | 内容 |
|---|---|
| `.claude/skills/nora-dev/` | **开发维护手册**:架构、服务端口、启动方式、数据契约、已知坑(改代码前后必读) |
| `.claude/skills/nora-agent-tools/` | Agent 工具设计权威参考(权限三档 / 风险分级 / 审批协议) |
| `.claude/skills/flyway/` · `restart-service/` | 迁移检查、服务重启的标准流程 |
| `.claude/agents/` | 代码审查子代理(后端:SSE/审批/重启坑;前端:双路径/持久化/暗色模式) |
| `.claude/hooks/` | 自动化守卫:构建前 jar 锁检查、前端改动后自动 eslint --fix、会话结束前 tsc 把关 |
| `.claude/workflows/` | 项目脚手架与 RAG 实施的工作流定义 |

## 文档

| 文档 | 内容 |
|---|---|
| [docs/demo/](docs/demo/) | 功能演示视频(分场景短片) |
| [nora-api/docs/architecture-v2.md](nora-api/docs/architecture-v2.md) | **微服务架构设计**(服务划分 / 数据流 / 路线图) |
| [nora-api/docs/agent-implementation-spec.md](nora-api/docs/agent-implementation-spec.md) | **Agent 实施规格**(ReAct 协议 / 工具契约 / 审批协议) |
| [nora-api/docs/agent-permission-and-tools-design.md](nora-api/docs/agent-permission-and-tools-design.md) | 权限与风险分级权威参考 |
| [docs/dev/](docs/dev/) | 开发文档:设计评审 / 改造方案 / 实施记录(演进档案) |
| [nora-web/README.md](nora-web/README.md) | 前端技术栈与项目结构 |
| [nora-api/README.md](nora-api/README.md) | 后端服务清单与端点 |
| [phone-album-mcp/README.md](phone-album-mcp/README.md) | 手机相册 MCP(Android App + 中继部署) |

## 开源许可

[MIT License](LICENSE) — 可自由使用、修改、分发,保留版权声明即可。
