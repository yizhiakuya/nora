# 真实服务验收入口

从仓库根目录运行 `python scripts/acceptance.py <suite>`。前端先执行 `pnpm install --frozen-lockfile`，后端使用当前源码构建并启动；脚本不会模拟模型或启动第二套业务入口。

| suite | 前置条件 | 执行内容 |
|---|---|---|
| `standards` | Nora、PostgreSQL、独立 Redis 测试实例 | HTTP 错误、Provider 脱敏、数据源读写、技能冲突、调度、文件下载 |
| `core` | 隔离数据库/文件目录、真实可用模型 | 工具、资源、会话互斥、排队、取消、自动任务；复用 e2e-single-app.py |
| `builtins` | 同上，另外设置 `NORA_E2E_DB_NAME`（`nora_e2e_` 或 `nora_simplify_` 前缀）和 `NORA_E2E_WORKSPACE_ROOT` | 类型化文件/技能工具、普通及通配符移动复制、冲突预检、目录摘要、提问取消 |
| `annotated` | 独立的 `nora-tools-*` 测试容器与日志文件；设置 `NORA_E2E_CONTAINER`、`NORA_E2E_TOOL_LOG_FILE` | 容器、日志工具及审批拒绝/取消；配置细节见 e2e-single-app.py 的 annotated |
| `catalog` | 隔离数据库/工作区、真实模型；设置 `NORA_E2E_DB_NAME`（`nora_e2e_` 前缀）和 `NORA_E2E_WORKSPACE_ROOT` | 无工具提示的自然任务按需加载、后续复用、真实环境状态/日志、历史落库和非法加载名称 |
| `steering` | 隔离数据库/工作区、真实模型、Node 与前端依赖；设置 `NORA_E2E_DB_NAME`（`nora_e2e_` 前缀）和 `NORA_E2E_WORKSPACE_ROOT` | 提问等待中插入、普通排队不唤醒、两种插入入口、重复受理、旧票据、引用与再次回答；回放真实 SSE 和重连快照，核对问题卡清理 |
| `live` | 隔离数据库/文件目录、支持长输出及工具的真实模型、Node 与前端依赖 | 长输出、快照、游标续传、提问与历史；自动构建当前客户端并回放真实事件记录 |

默认后端为 `http://127.0.0.1:18080`，可以用 `--base` 或 `NORA_E2E_BASE_URL` 指定。令牌通过 `NORA_AUTH_TOKEN` 提供。

模型套件必须显式提供已启用 Provider 的 ID 和模型名：

```powershell
python scripts/acceptance.py standards
python scripts/acceptance.py core --provider 7 --model <实际模型名>
python scripts/acceptance.py builtins --provider 7 --model <实际模型名>
python scripts/acceptance.py live --provider 7 --model <实际模型名>
```

`standards` 的数据库和 Redis 连接参数沿用 `e2e-standards.py` 中的 `NORA_E2E_DB_*`、`NORA_DB_PASSWORD` 与 `NORA_E2E_REDIS_*`。测试记录使用唯一前缀并清理；core/live 必须使用隔离数据库及工作区，因为真实模型会执行工具。

live 的真实事件报告保存在被忽略的 `logs/live-turn-e2e/`，客户端编译产物放在系统临时目录并自动清理。报告可能包含任务内容，不提交到仓库。历史记录也可单独验收：`node scripts/e2e-live-turn-client.mjs <真实报告路径>`。

CI 默认执行 standards。core/live 需要真实模型配置，未执行时明确记录未执行；不可用冒烟测试代替。浏览器交互、审批、命令进程、图像和浏览器引擎专项继续使用对应 `scripts/e2e-*.py`，按改动选择，不在基础套件中无条件触发外部服务。

规范修复回归：隔离数据库与工作区下打开 `/src/e2e/standards.html`，通知初始必须为空，并配置可用的真实嵌入服务。检查缓存迁移、文件夹删除、同名知识文档、切换详情后的迟到响应、通知失败与重复操作、列表重试和令牌存储失败；清理本轮文件及文档。模型畸形响应由 `standards` 的本地 HTTP 协议夹具验收，仍调用真实 Nora 解析与错误映射，不替代真实业务服务。

命令进程回归：构建后执行 `python scripts/e2e-command-processes.py`，需要 Java 21、PowerShell 和已迁移的 PostgreSQL。连接沿用 `NORA_DB_URL`、`NORA_DB_USERNAME`、`NORA_DB_PASSWORD`（默认本地 15432 的 nora）。脚本在唯一临时 schema 和工作区验证命令、取消、输出上限、落库失败重试及新 JVM 恢复，并清理自身数据；不会重放已完成命令。

浏览器回归：启动前端开发服务，在独立验收环境打开 `/src/e2e/maintainability.html`，点击“运行验收”。页面直接使用当前 store、useChat 与真实 HTTP/模型，验证缓存竞态、技能失败、单一消息源、侧栏更新次数、切页接续、编辑重发、重试、排队撤回与停止；不会模拟 SSE。现有聊天存储迁移页为 `/src/e2e/chat-storage.html?history=<已有真实会话ID>&text=<历史中的验证文本>`，不再依赖某个开发者本地会话。

任务与请求契约回归：`node scripts/e2e-robustness-client.mjs` 编译当前客户端 store/服务层并直连真实 HTTP，使用同一组 `NORA_E2E_BASE_URL` / `NORA_AUTH_TOKEN`。需要可用默认模型，创建临时手动任务，仅要求回复固定确认，验证并发、迟到响应、重试及请求头与登记错误。结束删除临时规则和会话；软删除历史和通知遵循现有保留机制。该入口不启动浏览器，不替代 UI 验收。
