# 本站媒体鉴权修复

## 问题与最小方案

工具结果图片和媒体画廊通过 `mediaCacheUrl` 构造图片地址。此前该函数只给外部 HTTP(S) 媒体代理地址附加令牌，本站 `/api/...` 地址直接返回，开启鉴权时会得到 401；本站完整 URL 又会被误当成外部媒体代理。

共享入口现在识别同源 API 并直接附加令牌，外部 HTTP(S) 媒体仍使用原缓存代理。`withAuthToken` 使用平台 URL 解析，仅向同源 `/api/` 添加令牌，保留查询参数、已有令牌和片段，避免令牌被拼到 `#` 后而失效。外部、静态、data/blob 和无效地址不追加令牌。

调用链已核对工具结果、媒体画廊、Markdown、查看器、文件网格/列表、引用缩略图、下载、浏览器帧、会话与日志流。无需修改调用方和后端契约，不新增存储或依赖。

## 验证

- `pnpm typecheck`、`pnpm lint`、`pnpm deadcode`、`pnpm test`、`pnpm build` 全部通过；44 个测试文件、159 项冒烟。
- 开启鉴权的本地真实后端下，浏览器运行 `/src/e2e/media-auth.html`，18 项结果检查通过。包含真实拒绝未授权访问、上传字节与读取字节一致、两个本站地址在真实 MediaGallery 解码成功；临时图片已清理。
- 源码提交 [`a6b5916`](https://github.com/yizhiakuya/nora/commit/a6b5916aa0a822e65c7cabf18c28f7788161f636) 的 [GitHub Quality](https://github.com/yizhiakuya/nora/actions/runs/39681489293) 全部通过，包括前端五项检查、后端 `mvn verify` 与真实 HTTP 验收。
- 生产构建仍有既存的 `useChatSessions` 静态/动态导入提示，不影响构建成功。

## 发布结果

已发布到 megumin 的现有 Nora Compose，入口为 [Nora](https://nora.rainaki.top:13001/)。本轮只更新前端镜像引用；核对解析后的 Compose，其他配置完全一致。后端仍为 `20261018-question-steering`，后端与 PostgreSQL 容器 ID 未变，没有执行迁移或修改生产业务数据。

- 前端镜像：`ghcr.io/yizhiakuya/nora/nora-web:20261022-media-auth-a6b5916`。
- GHCR digest：`sha256:d441e14534e38455f2c911c4334fc21e8bf6e5c7cde404d2d647955212d31baa`；服务器拉取后核对镜像 ID 和源码 revision 标签一致。
- 生产入口：`index-3bnU3CAF.js`；Docker 标准构建与本地已验证产物一致。
- 备份目录：`/opt/nora/backups/20261022-media-auth-a6b5916/`，包含原 Compose、运行配置及前端镜像记录，仅 root 可读。旧镜像保留为 `nora-rollback/nora-web:20261022-media-auth-a6b5916`。
- 发布记录：`/opt/nora/releases/20261022-media-auth-a6b5916/verification.json`；本地截图与部署脚本在忽略目录 `logs/media-auth-20261022/`。

切换前确认聊天、命令与自动任务空闲；21 项部署前后检查通过。核对前后端健康、后端/数据库未重启、HTTPS 证书与入口、JS/CSS 字节与镜像一致、无令牌访问返回 401、已有图片的 query-token 读取及下载与授权原件字节一致。工作站访问正式 HTTPS 入口也成功。生产验收只读取既有文件。

如需回退，将备份中的 `docker-compose.prod.yml` 恢复到原路径，使用 `/opt/nora/.env` 执行 `docker compose ... up -d --no-deps --pull never --wait nora-web`，然后重复健康与 HTTPS 检查。本轮不涉及数据库或文件迁移，前端回退无需恢复业务数据；部署脚本已配置失败时恢复旧前端，本次未触发回退。

## 验证边界

未在本机重跑 Maven，CI 已完成；未触发真实模型套件。未改变界面布局，不将媒体回归视为全站视觉验收。线上浏览器打开与恢复两次超时，线上仅确认真实 HTTP 和资源结果；本地真实浏览器验收已完成。通用主机清单在无关 OpenClaw 检查处挂起并已结束，本轮服务器状态以 Nora 定向检查为准。
