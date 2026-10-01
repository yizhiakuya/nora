# 规范性审查修复

- 生产基础设施仅绑定回环地址,数据库密码显式配置。
- 工具参数递归脱敏;非法 JSON 不记录原文。
- Spring MVC 错误保留标准 HTTP 状态与响应头;内部异常只返回通用说明。
- 前端写操作等待服务端确认,失败保留原数据并显示错误;服务端空数组覆盖缓存。
- 新增 `POST /api/models/providers/probe`:接收 `endpoint/apiKey/providerId`,仅发现模型,不创建或更新记录。编辑时仅在密钥留空时按 providerId 读取已存密钥,端点以当前表单为准。保存仍使用既有 CRUD 接口。
- PostgreSQL JDBC 超时用秒,MySQL 用毫秒。
- CI 执行前端四项质量检查与后端测试/格式检查,镜像发布依赖这些检查。
- 单元测试只冒烟;HTTP/E2E 验收检查真实服务响应和持久化结果。
- 文件页改为 5 行入口，文档库改为 53 行组合组件，状态与操作复用领域 Hook。后端工具执行器提取文件/工作区及 MCP 执行域，保留既有入口与协议。
- 统一 React 构建插件声明，固定 pnpm 版本与 Node 要求，开启 TypeScript/ESLint unused 检查；Java 使用 LF，Spotless 在 verify 阶段检查。
- 补齐用户偏好网关路由；Agent 健康检查携带既有鉴权头。

## 本地验收

| 检查 | 结果 |
|---|---|
| pnpm typecheck / lint / build | 通过 |
| pnpm test | 23 个文件，119 个冒烟测试通过 |
| mvn -q verify | 36 份报告，277 个测试，0 failures/errors/skipped；Spotless 通过 |
| 真实 HTTP 验收 | 6 组通过：错误语义、用户偏好路由、不落库探测、模型持久化与脱敏、真实 Redis 只读查询、删除一致性 |
| 编译类脱敏复验 | 嵌套对象、数组、JSON 字符串形式 MCP 参数、headers/env、非法 JSON 与标量均通过 |
| 浏览器验收 | 探测/保存失败显示错误且不新增记录；文件列表、操作弹窗、文档详情通过；生产 preview 中设置偏好与文档库正常加载，无运行时错误 |
| git diff --check | 通过 |

真实接口验收使用 `python scripts/e2e-standards.py`，默认网关为 `127.0.0.1:18080`、Redis 为 `127.0.0.1:16379`，鉴权通过环境变量 `NORA_AUTH_TOKEN` 提供。只创建和清理唯一前缀的验收记录，不修改既有记录。

已配置 PR/发布前检查与真实 HTTP CI 验收，GitHub 上的执行尚待推送触发。生产 Compose 已通过配置校验，未部署线上；部署前必须设置 `DB_PASSWORD`，已有 PostgreSQL 数据卷需同步调整实际密码。浏览器验收后恢复本地 dev 服务。
