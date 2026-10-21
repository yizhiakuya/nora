# Nora 部署记录：按需工具与流畅交互

日期：2026-10-18（北京时间）  
目标：megumin，`192.168.0.109`  
入口：[Nora](https://nora.rainaki.top:13001/)  
版本：`20261018-agent-tools-uiux`  
状态：已部署，前后端健康，29 项线上验收通过。

## 发布内容与来源

发布当前工作区的前后端，包含[流畅交互与焦点恢复](uiux-fluidity-2026-10-17.md)、[通用 Agent 按需工具](agent-tools-simplification-2026-10-17.md)及其依赖的当前实现。工作区已有未提交改动，未使用 Git HEAD 或服务器旧源码代替发布来源；源码归档与逐文件 SHA256 保存在发布目录。

后端复用已通过 `mvn verify` 的产物（301 项冒烟、Spotless、打包），发布前确认源码和构建输入未晚于产物。前端已有 typecheck、lint、deadcode、157 项冒烟和 build 通过；本次使用标准 Dockerfile 再构建镜像，产物入口与此前验收版本均为 `index-DUDmS3xG.js`。发布过程中再次核对源码与归档清单一致。

| 镜像 | GHCR digest |
|---|---|
| `ghcr.io/yizhiakuya/nora/nora-api:20261018-agent-tools-uiux` | `sha256:f62cf51edd4f522c09601fc8e46321508b30881f32d9cf77f44614e199900d94` |
| `ghcr.io/yizhiakuya/nora/nora-web:20261018-agent-tools-uiux` | `sha256:128219216b817a9ef82b1943e25b822b5b4002fce022229feac051aeb78cf436` |

镜像已推送 GHCR；服务器通过 SSH 接收同一 Docker 镜像归档，校验归档 SHA256 后载入，再核对镜像 ID。未修改 latest。

## 备份与切换

继续使用 `/opt/nora/.env`、`/opt/nora/repo/nora-api/docker-compose.prod.yml`、原端口及原数据卷。发布前核对数据库、鉴权、嵌入、代理、Java 参数与工作区路径一致，七个领域的历史迁移校验和一致。

唯一待执行迁移为 Agent V43：替换内置工作台手册中的旧服务工具指引；不改变用户业务表结构，不改历史迁移或用户自定义技能。启动后核对 V43 成功。

确认 Run、命令和自动任务空闲后停止 Nora 前后端，备份数据库（约 6.6 MiB）、文件与工作区（约 305 MiB）、运行配置、Compose、Nginx 与原镜像标记。通过 `pg_restore --list`、归档目录读取与 SHA256 验证后切换，等待两个容器健康。

- 发布目录：`/opt/nora/releases/20261018-agent-tools-uiux/`。
- 备份目录：`/opt/nora/backups/20261018-agent-tools-uiux/`，仅 root 可读。
- 旧前端：`20261013-chat-lifecycle`；旧后端：`20261013-session-delete`。原镜像另保留本轮 rollback 标签。
- 本地明细：忽略目录 `logs/deploy-20261018/`，含构建、发布清单、镜像校验与 `verification.json`。

切换后、创建验收会话前，88 个会话、278 条消息、20 个文件、2 个 Provider 的数量及 Provider/应用设置摘要一致。知识文档、向量块、自动规则均为零，未将其算作存量检索或定时执行验证。其他服务及旧基础设施未清理或重启。

## 线上验收

29 项检查通过：健康与固定镜像、V43、前端资源与 SPA 深链接、既有鉴权、七个业务域、原会话历史、工作区目录选择、原文件预览元信息、应用日志源、非 root Docker 访问、Nginx 与 HTTPS 证书、真实模型 SSE 及持久化。

额外使用唯一前缀临时会话：模型从自然任务描述中加载 `manage_service`，查询真实状态并返回观察主机信息；下一轮直接复用，无重复 `load_tools`；两轮均完成且工具结果持久化。仅执行只读状态查询，结束后取消核查并软删除验收会话。工作站直接访问正式 HTTPS，确认返回本次构建资源且证书校验正常。

## 回退与验证边界

旧镜像和配置已保留。回退须先停止新版并核对新写入；V43 已更新内置手册，旧应用回退时应按备份恢复该手册指引。不得直接整库覆盖发布后新增会话或文件，也不得删除 Flyway 历史。

未执行完整备份恢复演练、宿主重启或全部业务回归。内置浏览器与 Edge 控制通道超时，未完成本轮线上点击验收；前端交互依据此前真实后端下的本地浏览器验收，不能将 HTTPS 检查称为浏览器交互通过。
