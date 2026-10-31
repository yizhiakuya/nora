# PTC 模式生产发布

发布日期：2026-10-29（北京时间）；记录补齐与只读复核：2026-10-31。

## 来源与运行版本

源码为 [`1c978dc2ebba51eb18d8d04443ffa9c168fcb92e`](https://github.com/yizhiakuya/nora/commit/1c978dc2ebba51eb18d8d04443ffa9c168fcb92e)，目标为 megumin `192.168.0.109` 上既有 Nora Compose，入口为 [Nora](https://nora.rainaki.top:13001/)。前后端统一版本 `20261029-ptc-1c978dc`。

| 镜像 | 运行镜像 ID |
|---|---|
| `ghcr.io/yizhiakuya/nora/nora-api:20261029-ptc-1c978dc` | `sha256:ac46aad38f35f102bc82b389f3f33854ee44ffe8e12ef6de77bbeb5e4286d41a` |
| `ghcr.io/yizhiakuya/nora/nora-web:20261029-ptc-1c978dc` | `sha256:892ca9dea139329806d105a852ffb35168789a0cdbfccc2ec4971864b9af0f7a` |

镜像通过项目 `nora-api/build-push.sh` 构建并推送 GHCR。服务器拉取 GHCR 镜像无进度，随后采用既有发布方式，经 SSH 传输镜像归档、核对 SHA256 后载入。PTC 运行镜像固定为 `node:22-alpine@sha256:fe4f6d74e20370acb04ad889077d14d0f64a9c8ea6a42bc60cddf1e844699914`；归档载入后仍需按该引用从 Docker Hub 拉取，确认完整 digest 引用可被解析。

## 配置、迁移与回退经过

生产继续使用 `/opt/nora/.env` 和 `/opt/nora/repo/nora-api/docker-compose.prod.yml`。保留服务器本地定制，在配置差异比对中限定更新 API/Web 镜像和 PTC 运行镜像环境变量。

首次切换的新容器健康、Agent V44/V45 迁移成功，但模式接口返回 `ptcAvailable=false`，因此自动恢复旧 Compose、环境配置与旧 API/Web 镜像，旧容器恢复健康。原因是服务器旧 Compose 没有 `NORA_PTC_IMAGE` 环境映射，仅在 `.env` 添加值没有传入 API。补齐该映射后重新切换，模式接口返回 `ptcAvailable=true`。

V44 为会话和 Run 增加执行模式，V45 为步骤增加父步骤与调用序号。回退镜像没有撤销迁移，也没有整库恢复或覆盖用户业务数据；PostgreSQL 容器未重建。

## 备份与恢复边界

- 备份目录：`/opt/nora/backups/20261029-ptc-1c978dc/`，权限为 root 独占的 `0700`。
- 包含数据库自定义格式 dump、文件/媒体卷、工作区卷、日志卷、运行环境、Compose、Nginx 配置及发布清单。发布时使用 `pg_restore --list` 和归档读取验证可读性。
- 旧 API/Web 镜像保留在 `nora-rollback/nora-api:20261029-ptc-1c978dc`、`nora-rollback/nora-web:20261029-ptc-1c978dc`；对应旧版本分别为 `20261018-question-steering`、`20261022-media-auth-a6b5916`。
- 发布结果：`/opt/nora/releases/20261029-ptc-1c978dc/verification.json`；当次部署脚本和镜像归档保留于同一发布目录，仅供追溯。

**现存数据库备份不是迁移前快照。** 10 月 31 日核对 dump 中的 Agent Flyway 历史，已包含 V44/V45；当次重试复用了同一目录并重新生成备份。不要依赖此 dump 撤销这两项迁移，也不要重跑该历史脚本覆盖备份。后续每次发布尝试应使用独立备份目录，并保留首次迁移前快照。

旧镜像恢复健康只证明当时能够重新启动，不能证明它完整支持后续产生的 PTC 历史。需要回退时先核对在途任务、新写入和历史读取兼容性；数据恢复另行确定范围，不删除迁移历史或覆盖发布后的用户数据。

## 验收证据

10 月 28 日的前后端检查、Docker 运行时专项与隔离 JVM 重启验收见 [PTC 实施记录](ptc-mode-design-2026-10-23.md)。10 月 29 日发布脚本再次通过 Maven verify；隔离真实模型 HTTP 套件通过工具发现、串并行、真实文件与历史恢复、子审批批准/拒绝、队列模式冻结、取消和后续恢复。

10 月 29 日部署后核对了前后端镜像与健康、PostgreSQL 容器未变、V45 成功、既有会话模式可读、`ptcAvailable=true`，以及公网 HTTPS 首页字节与容器内首页一致。生产检查读取既有会话，没有运行会写入用户文件的 PTC 套件。

10 月 31 日本轮仅修正文档并做只读复核：运行版本及镜像 ID 与上表一致，API/Web 均 healthy，后端健康为 UP，已鉴权模式接口仍返回 PTC 可用。工作站访问正式 HTTPS 返回 200；服务器绕过出站代理并将生产域名解析到回环地址后，证书校验成功且首页字节与运行镜像一致。服务器直接访问公网域名超时，未定位该网络路径原因；不能由此判定网站整体不可访问。

本轮检查文档差异、相对链接和所述命令与实际配置的一致性；没有修改运行代码或重新部署镜像。

## 未完成项

生产浏览器点击验收仍未完成：10 月 29 日 Computer Use 因无法可靠确认 Edge 当前 URL 停止操作，本轮未重试。没有进行完整备份恢复演练、生产模型工具执行或全站 UI 回归；API 可用性检查不替代这些验收。
