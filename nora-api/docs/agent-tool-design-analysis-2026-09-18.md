# Nora Agent 工具设计全面分析（2026-09-18）

> 范围：各系统（对话/知识库/数据源/环境/文件/自动任务/MCP/技能/设置）对 agent 暴露的工具面。
> 方法：代码与运行时数据（schema_agent.agent_step 14 天真实调用）+ 业界一手资料调研（文末来源）。
> 本文是**分析报告**；权限/风险/工具权威清单仍以 `agent-permission-and-tools-design.md` 为准。

---

## 一、现状全貌（实测数据）

### 1.1 工具面规模

| 来源 | 数量 | 说明 |
|---|---|---|
| 内置工具（ChatToolsSpec） | 12 | execute_sql / read_service_logs / execute_write_sql / manage_container / manage_datasource / manage_service / read_file / manage_workspace / fetch_media / manage_skill / manage_mcp / run_command |
| MCP 挂载（3 个启用服务器） | 64 | github 44 + megumin-ssh 14 + phone 6（另有 3 个软删的旧 phone/album 不再挂载） |
| **合计每轮注入** | **76** | tools spec 实测 ≈74KB 字符 ≈9K tokens，**每轮请求固定成本** |

### 1.2 真实使用分布（14 天 / 162 会话 / 2608 次调用 / 失败率 2.8%）

| 工具 | 调用 | 失败 | 平均耗时 | 备注 |
|---|---|---|---|---|
| fetch_media | 619 | 0 | 75s | 批量媒体一等工具（设计成功案例） |
| mcp__phone__photo_content | 344 | 2 | 4.0s | 取图给视觉模型 |
| run_command | 285 | 5 | 4.2s | 终端 |
| mcp__phone__photos_review | 226 | 3 | 1.1s | 拼图扫看 |
| manage_workspace | 186 | 18 | 45ms | 失败率 9.7%，见 §3.3 |
| execute_sql | 134 | 13 | 36ms | |
| mcp__megumin-ssh__ssh_exec | 108 | 10 | 8.7s | |
| 其余 23 个工具 | ~700 | — | — | github 全部工具合计仅 10 次调用 |

**关键观察**：
- **76 个工具中 30 个被用过（39%）**；github 的 44 个工具 30 天只调用了 3 个（get_me / search_repositories / search_users），却占用 ~1.5 万字符的固定上下文；
- 使用高度集中：前 5 个工具占 60%+ 调用量；
- 高耗时工具（fetch_media 75s、execute_write_sql 60s）证明「一等工具 + 进度反馈」路线是对的——把它们留给模型自己拼多步调用会灾难性失败（407 文件 400+ 轮的历史事故）。

---

## 二、业界共识（2026-09 调研）

### 2.1 工具数量与选择准确率

- **准确率拐点**：MCP 架构论文（ICSME 2026）实测 **Haiku 类模型 10–15 个工具**、Sonnet 类 **20–30 个** 时选择准确率跌破 90%；另一研究测到工具目录增大时 7–85% 的准确率跌幅。
- OpenAI 官方建议：**单轮初始可用 <20 个**；>10 个工具或定义 >10K tokens 就该考虑延迟加载。
- Anthropic Tool Search：多服务器场景（GitHub+Slack+Sentry+Grafana+Splunk）定义 ~55K tokens；**按需加载 3–5 个可省 85%+**。
- Claude Code 的答案：**工具数克制在 ~20 个，新工具门槛极高**；扩展靠渐进披露（skills / ToolSearch）而非加工具。

### 2.2 设计原则（Anthropic《Writing effective tools for agents》+ 各方共识）

1. **不要包装 API，要包装工作流**。反例：`list_users` + `list_events` + `create_event` → 正例：`schedule_event`。工具数减少的同时把多步编排从模型上下文挪进工具实现。
2. **每个工具有清晰、互斥的用途**；描述里若必须写「与 X 不同…」才能解释，就是设计问题。
3. **命名是模型最强的信号之一**；相关工具用前缀命名空间分组（`asana_search` / `jira_search`）。
4. **schema 纪律**：enum 收敛有限值（让无效状态不可表示）、参数名对齐领域语言（`user_id` 而非 `user`）、必填最小化、`additionalProperties:false`、默认值给最常见值。
5. **输出高信号**：过滤字段（实测 802→79 字符，-90%）、默认 concise + 可选 detailed、分页/截断给指针而非无界返回。
6. **错误=可操作的出路**：「发生了什么 + 违反哪条约束 + 正确示例」，绝不裸抛异常码。
7. **写操作幂等**（agent 会重试）；不可逆操作走结构化两段确认（Nora 的审批门即此模式）。
8. **评测驱动**：四层评测（schema 合规 / 参数正确 / 序列合理 / 终态正确），用真实任务集而非拍脑袋；跟踪调用数、错误率、token 消耗，从失败转录里改进工具。
9. **渐进披露**（工具多时的正解）：目录（search_tools）→ 详情（inspect）→ 执行；或「代码即工具」（把 MCP 当代码 API 在沙箱里调用，Anthropic 实测 150K→2K tokens）。MCP 官方 Client Best Practices：工具定义占窗口 1%–5% 以上就该切换。
10. **Mask, don't remove**：会话中动态增删工具数组会毁 prompt 缓存并引发幻觉动作；延迟加载要用「搜索+引用」而非直接改数组（Anthropic Tool Search 的做法：deferred 工具不占前缀，发现后以 tool_reference 内联追加，缓存不破）。

---

## 三、逐系统分析

### 3.1 对话 / 编排（已有工具面：全部）

无缺口。值得保留的既有好实践：
- 工具 `description` 参数（「一句话描述…作为时间线标题」）——对齐 Claude Code「审批卡正文数据驱动」；
- 描述含正反例（「不要出现『复杂』『风险』等主观词」）——对齐 Anthropic 的「失败条件前置」；
- 跨轮工具链重建（assistant(tool_calls)+tool(result) 重放）——「回填即历史」；
- fetch_media 式「一等工作流工具」——本次分析中最重要的正面样本。

### 3.2 知识库（无工具面）

- **现状**：RAG 检索是**每轮自动注入**（ChatOrchestrationService → RagRetrievalClient），agent 没有主动检索工具；引用注入（`@` 文件 / `#` 知识库）走 MessageRefResolver。
- **缺口**：
  1. **二次检索**：自动检索召回不佳时，agent 无法换关键词/换范围重查（只能答「没找到」）；
  2. **知识库管理**：索引（`POST /api/rag/index`）、删除文档、重建索引、统计——目前只有 UI 能做，agent 不能（用户说「把这份文档加进知识库/删掉那个旧文档」时只能指引 UI）。
- **建议**：补 `search_knowledge`（query/topK，主动检索）与 `manage_knowledge`（list/index/remove/reindex/stats）。前者风险 LOW；后者索引/删除归 HIGH/CRITICAL 分级。

### 3.3 文件（三工具 + 一个真实痛点）

- **现状**：`read_file`（工作台文件 list/read/import）+ `manage_workspace`（工作区+整机 list/read/write/append/delete/import/move/copy/mkdir）+ `fetch_media`（批量媒体）。覆盖面在「读、写、整理、批量拉取」四个方向上都已比较完整。
- **真实痛点（14 天失败数据）**：
  - `manage_workspace` 18 次失败中 **7 次是「缺少 path 参数」**——模型忘填必填语义参数（schema 里 path 是可选，靠描述约束）;
  - **模型两次尝试 `action:"edit"`**、一次 `action:"download"`——模型按 Claude Code 直觉想「精确编辑」，但 Nora 只有全量 `write`（覆盖式）。模型被迫 read 全文 + write 全文，既费 token 又有覆盖风险；
  - 一次 `agent-workspace\agent-workspace\MEMORY.md`（路径重复拼接）——路径语义（相对=工作区）与模型直觉冲突的实例。
- **建议**：
  1. 加 **`edit` action**（old_string/new_string 精确替换，对齐 Claude Code Edit：失败条件写进描述「old_string 必须唯一匹配」）——最高价值缺口；
  2. `path` 的处理：或在描述首句强化「除 list 外所有 action 必填 path」，或对 write/read 单独建工具（工具数+2，但 schema 强制必填）——权衡后推荐前者 + 错误消息带示例（已有示例，强化即可）；
  3. 路径重复拼接（`agent-workspace\agent-workspace\`）值得在错误消息里加 hint：「路径已相对工作区，不要重复包含工作区目录名」。

### 3.4 数据源（工具面完整）

- **现状**：`manage_datasource`（list/create/test/schema/remove）+ `execute_sql` + `execute_write_sql`。失败数据里错误消息质量高（MySQL SHOW 语法 → 给出 PostgreSQL 正确写法；relation does not exist → 提示查 pg_tables / schema 前缀）——**这是全系统错误设计的样板**。
- **小缺口**：`{id}/history`（查询历史）无工具面；价值不高，可不动。

### 3.5 环境控制台（工具面完整）

- **现状**：`read_service_logs`（DOCKER/FILE/PROC 三类源都支持，按名称寻址）+ `manage_container`（start/stop/restart）+ `manage_service`（纳管源 CRUD）。
- **缺口**：**健康快照无工具**——`GET /api/environment/services` 返回每个源的状态/health/cpu/memory/uptime（agent 的诊断入口），但 agent 只能逐个 read_service_logs 猜哪个服务有问题。用户问「现在系统状态怎么样」时没有一步到位的工具。
- **建议**：补 `environment_status`（一次返回全部纳管源状态摘要，LOW 风险）——对齐「工具=工作流」原则（人做诊断先看面板，不是先翻日志）。

### 3.6 自动任务（无工具面——最大缺口之一）

- **现状**：automation-service 有完整 REST（list/create/toggle/delete/run/executions），agent 完全不可见；工作台手册里写的指引是「用户说『建个每天 9 点的任务』→ 指引用户到自动任务页创建」。
- **问题**：这正是「agent 是工作台操作员」定位的反例——最需要自动化的场景（用户说「每天帮我查一次 X」）agent 反而不能做。
- **建议**：补 `manage_automation`（list/create/toggle/remove/run/executions）。风险分级：list=LOW；create/toggle/run=HIGH；remove=CRITICAL（或按需）。注意 create 的 prompt 字段是「未来无人值守轮次的指令」，注入面需在审批卡里完整展示（对齐 run_command 的展示原则）。

### 3.7 MCP（管理面完整；挂载面需要治膨胀）

- **现状**：`manage_mcp`（list/refresh/enable/disable/register/remove）+ 动态挂载 `mcp__<server>__<tool>`。GitHub OAuth 一键登录。注册时名字校验严格（防挂载名解析错位）。
- **问题**：
  1. **固定开销**：64 个挂载工具 ≈ 大部分 9K tokens 的 tools spec；github 44 个工具 14 天用 3 个；
  2. **畸形名无兜底**：模型幻觉出 `mcp__phone_photos_review`（少一个下划线）→ 报「找不到服务器」——错误消息没提示「名字格式应为 mcp__<服务器>__<工具>，可用工具列表见 manage_mcp list」；
  3. **toolsSpecTokensCache 永不失效**：运行期注册/刷新 MCP 后 token 估算不重算（`toolsSpecTokensCache` 只在首次计算）——压缩触发会偏晚，是个真实小 bug；
  4. **模型对挂载工具的调用纪律**：phone 工具描述很详尽（141–678 字符），但 226 次 photos_review 里仍出现传 album="全部" 这种字面值错误（描述里已警告）——说明「描述警告」不如 schema enum 有效。
- **建议**（按优先级）：
  1. 修复 cache 失效（refresh/register/disable 时 invalidate）；
  2. 畸形名错误消息给格式示例；
  3. **中期**：引入「MCP 工具延迟加载」——挂载工具先只给「服务器目录 + 每服务器一句摘要」，模型需要时用 `manage_mcp action=tools server=X` 拉取该服务器完整工具定义后再调。这是 Anthropic Tool Search 的轻量版（Nora 目前没有原生 tool_search 通道，可以先用 harness 层模拟：tools 数组只放内置 + 高频 MCP 工具，其余按需注入）。前提：注意 prompt 缓存（Anthropic：deferred 不占前缀、发现后内联追加；Nora 的 OpenAI 兼容通道没有等价机制，需权衡——也可先用「服务器级开关」让用户按需启用/停用服务器，把选择权给用户）。

### 3.8 技能（工具面完整）

- **现状**：`manage_skill`（list/read/create/update/remove）+ 目录注入 + 按需 read 全文。这是渐进披露的正确实现（目录常驻、正文按需），对齐 Anthropic 的 skills 模式。
- **小改进**：技能正文可以放「配套文件」吗？现在只有纯文本正文。低优先级。

### 3.9 设置（故意无工具面——正确）

模型供应商/代理/环境变量是用户配置域，agent 不给工具是合理的边界决策（防止 agent 改自己的模型配置）。维持。

### 3.10 手机端 MCP（phone：6 个工具——工具收敛的正面样本）

- photos_search 吸收 photos_export（urls 参数），photo_get 退役——**8→6 的收敛方向与业界「少而完整」一致**；旧名保留兼容别名。
- 工具描述质量全仓库最高（触发场景、典型流程、反例都写了）——但如 §3.7 所述，描述不敌 enum。

---

## 四、横切问题（按证据排序）

| # | 问题 | 证据 | 影响 |
|---|---|---|---|
| 1 | **76 个工具/9K tokens 固定成本**，其中低使用率 MCP 工具占大头 | tools spec 74KB；github 44 工具 30 天用 3 个 | 每轮都付；选择准确率随目录增大下降 |
| 2 | **0 个 enum**：所有有限动作集都是自由字符串 | `grep enum ChatToolsSpec` = 0 | 模型打错 action/album 值 → 失败重试（photos_review 传「全部」等） |
| 3 | **缺 `edit` 原语**：只有全量 write | 模型 2 次尝试 `action:"edit"` 被拒 | read+write 全文，费 token、覆盖风险 |
| 4 | **缺自动任务工具面** | 手册指引「让用户去 UI 建」 | 「agent 是操作员」定位的破绽 |
| 5 | **缺主动知识库工具面**（检索+管理） | 无工具；检索仅自动注入 | 二次检索/索引管理不可做 |
| 6 | **缺环境健康快照** | /services 无工具面 | 诊断入口绕路（先翻日志） |
| 7 | 参数漏填（path ×7）/畸形挂载名无兜底 | agent_step 失败数据 | 2.8% 失败率中的大头 |
| 8 | toolsSpecTokensCache 永不失效 | 代码：仅首次计算 | 注册 MCP 后压缩触发偏晚 |
| 9 | 同名/软删服务器堆积 | phone ×3 软删 + album 软删 | 管理视图噪音（挂载无影响） |

---

## 五、改进路线图（建议）

> **实施状态（2026-09-18 当日）**：P0 全部完成；P1 的 ⑤⑥⑦⑧ 全部完成；P2 的 ⑨⑩⑪ 全部完成（⑩⑪ 为持续运营工具，脚本已就位）。

### P0（schema 纪律，改动小收益大）✅ 已完成
1. ~~**ChatToolsSpec 全面补 enum**~~ ✅：action（全部 manage_*）、kind、engine、shell、quality、type、transport 等全部有限值字段已加 `"enum"`（此前 0 个）；
2. ~~畸形挂载名错误消息补格式示例~~ ✅：区分「格式错」（给 mcp__<server>__<tool> 格式示例）与「服务器不存在/停用」两种情况，出路指向 manage_mcp list/refresh；
3. ~~`toolsSpecTokensCache` 失效~~ ✅：`McpServerService.toolsRevision()` 在任何挂载面变更（create/delete/setEnabled/refresh/updateRemoteCredentials）时递增，估算缓存按 revision 失效；
4. ~~manage_workspace 错误消息补「路径已相对工作区」hint~~ ✅：read/write/append 缺 path 时给出完整示例与「不要重复拼工作区目录名」提示。

### P1（补能力缺口）✅ 已完成
5. ~~`manage_workspace` 加 `edit` action~~ ✅：old_string 唯一匹配的精确替换（不唯一/不存在给可操作出路；区内 LOW / 区外 HIGH；审批明细含 old→new 预览）；
6. ~~新增 `manage_automation`~~ ✅：list/create/toggle/remove/run/executions（list/executions LOW；create/toggle/run HIGH；remove CRITICAL；create 审批卡完整展示无人值守指令）；
7. ~~新增 `search_knowledge` + `manage_knowledge`~~ ✅：主动二次检索（LOW）+ list/index/remove/reindex/stats（list/stats LOW；index/reindex HIGH；remove CRITICAL）；
8. ~~新增 `environment_status`~~ ✅：全部启用纳管源的健康快照（LOW），诊断入口对齐「先看面板再翻日志」。

### P2（结构性）
9. ~~**MCP 工具延迟加载**~~ ✅（2026-09-18）：服务器级 `tool_policy`（V17 迁移）——eager=工具直接挂载（现状）/ lazy=不挂载，agent 经 `manage_mcp action=tools`（读缓存快照查清单）与 `action=call`（按名调用）使用；设置页 MCP 卡片可切换（`PUT /{id}/tool-policy`）；lazy 服务器工具不进 tools spec（省每轮固定成本），对齐 MCP Client Best Practices「渐进披露 + 单一稳定 call_tool 元工具」（数组不变，不破 prompt 缓存）；`[引用MCP服务器]` 注入按策略区分文案。风险：tools=LOW / call=HIGH / setPolicy=HIGH；
10. ~~**工具使用数据驱动迭代**~~ ✅：`scripts/tool-usage-review.sh`（7 节复盘：调用量/零调用候选/高失败样例/高耗时/高轮次会话/MCP 服务器使用率/当前挂载面）。**首跑即发现真实缺口**：模型对 manage_datasource/manage_service 写 `add`/`delete` 被拒（无别名归一化）→ 已修复（`normalizeDatasourceAction`/`normalizeServiceAction`，分类器/执行层/审批明细三处共用）；顺带修复 `schema` action 白名单缺失（分支此前不可达）；
11. ~~**工具评测集**~~ ✅：`scripts/tool-eval.sh` + `scripts/tool-eval-cases.json`（10 用例：6 正例覆盖新工具 + 2 负例[闲聊/纯计算不应调工具] + guardrail 行为 + 媒体一等工具）。打真实对话链路、解析 SSE 验证工具选择层，不校验回答文本（避免过严验证器）；首跑 10/10 通过。用例含真实副作用提示（media 用例会下载文件）。

### 长期方向（记录备查）
- **代码即工具**（Anthropic code execution with MCP）：把 MCP 当代码 API，在沙箱里写脚本调用，中间结果不进上下文——Nora 已有 run_command，若未来工具面继续膨胀（>100），这是终极解法；
- **工具结果 response_format**（concise/detailed 枚举参数）：对大结果工具（fetch_media/ssh_exec）值得试点。

---

## 六、方法论：Nora 该如何设计工具（沉淀）

### 6.1 从业务到工具的三步映射

1. **找用户目标**（不是系统能力）：「把相册整理到工作区」是一个目标；「调 photos_search 再逐个下载」是两个 API。**工具=用户可陈述的完整目标**。
2. **划边界**：两个操作若总是成对出现（查清单→下载），合并成一个工具；若独立出现（查 SQL / 读日志 / 跑命令），保持独立。判断标准：**分开后模型是否更容易选错**。
3. **定风险**：工具实现内部的多步操作按**最高风险的副作用**定级（fetch_media 内部下载+落盘，但落点在区内→LOW）。

### 6.2 单工具设计清单（写新工具时逐条过）

- [ ] 名字是动词+领域对象（`fetch_media` 好，`media_util` 差）；相关工具共享前缀
- [ ] 描述三段：是什么 → 何时用（含正例）→ 何时**不**用/失败条件
- [ ] 有限值字段全部 enum；参数名对齐领域语言；必填最小但**语义必填的写清楚**
- [ ] 大结果默认截断 + 给续读指针；结构化摘要字段（rowCount/truncated）
- [ ] 错误消息三段：出了什么错 + 违反哪条约束 + 正确示例
- [ ] 写操作幂等（或明确标注非幂等）；不可逆操作走审批门 + 审批卡展示完整原文
- [ ] 长任务给进度（StepProgress）与取消支持（TurnCancellation 双轨）
- [ ] 新工具登记四处：RiskClassifier / buildApprovalRequest / 权威文档 / nora-agent-tools skill

### 6.3 何时不该加工具（反模式清单）

- 只包装一个 REST 端点、无工作流价值 → 不加（除非模型高频需要）；
- 与既有工具语义重叠 → 先改既有工具（加 enum/参数），别加兄弟工具；
- 需要「与 X 不同…」才能解释 → 重新划边界；
- 能力属于用户配置域（模型/代理/密钥）→ 不加（防自改配置）；
- 高频低价值操作 → 考虑并入既有工具（如 list 并入 read 的默认分支——Nora 已这么做）。

---

## 七、来源索引

**一手**：anthropic.com/engineering/writing-tools-for-agents（2025-09）· anthropic.com/engineering/code-execution-with-mcp（2025-11）· platform.claude.com/docs tool-search-tool（defer_loading / 85% 节省 / 30-50 工具拐点）· modelcontextprotocol.io develop/clients/client-best-practices（渐进披露 / 1%-5% 阈值 / 三层 catalog-inspect-execute / 缓存注意事项）· developers.openai.com function-calling best practices（<20 工具 / tool_search / 命名与 enum 纪律）· OpenAI《A practical guide to building agents》（Data/Action/Orchestration 三类工具）。

**二手（有数据）**：aws.amazon.com/blogs/machine-learning/mcp-tool-design-practical-approaches-and-tradeoffs（V1-V6 演进 / 参数 ≤8 / 按需 detailed 省 2/3）· arXiv:2606.30317 MCP Server Architecture Patterns（工具数 10-15/20-30 准确率拐点 / 反模式目录）· next.bump.sh 4-rules（<15-20 工具 / 90% 输出过滤 / 错误给恢复路径）· machinelearningmastery.com AI Agent Tool Design（enum / 幂等 / partial_success / 两段确认）· aievals.co 四层评测法（schema/参数/序列/终态 / τ-bench pass^8 <25%）。

**Nora 内部**：agent-permission-and-tools-design.md · harness-tool-calling-research-2026-09-05.md · context-management-design.md · schema_agent.agent_step 实测（2026-09-04 至 09-18）。
