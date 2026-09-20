# Nora 产品改造实施记录

> 按 `NORA-PRODUCT-REFACTOR-PLAN-2026-09-20.md` 执行。只记录**真实已完成**的内容。
> 基线提交：`3a0e548`。执行顺序：M0 → M1 → M2 → M3 → M4 → M5。

---

## M0：基线与业务真实性（进行中）

### M0-01 基线核查（完成）

复核结论（对照基线 `3a0e548` 的当前源码）：

| 项 | 现状 | 结论 |
|---|---|---|
| R01 | `RagIndexClient.deliverLifecycle` 的 `onStatus` 只 log 不抛 → 4xx/5xx 仍 `return true` | **仍然存在**，需修 |
| R02 | `pending_rag_sync` 唯一键 `(file_id, mode)`，无版本/目标状态 | **仍然存在**，需修（每文件最新目标状态 + 单调版本） |
| R03 | `subscribeDraining` 在轮次锁内完成，但 backlog 在**锁外**发送（TurnStreamController 注释承认"调用方在锁外发送积压"） | **仍然存在**，需修（历史排空后才交付实时事件） |
| R04 | `snapshotWithGap` 只在 `afterSeq > 0` 时检测缺口；cursor=0（初次接续）不检测；且 8002 条时 oldest=3 却 cursor=0 报 gap=false | **仍然存在**，需修 |
| R05 | `cancelTurn` 立即 `activeTurns.remove` → 新轮可能在旧工作退出前启动 | **仍然存在**，需修（目标轮实际结束才释放） |
| R06 | `toggleRule` 乐观更新 + `.catch(() => {})` 静默吞错 | **仍然存在**，需修 |

本方案与当前代码的差异：P1（3 项）/P2（7 项）/工程门禁已在 09-19/20 落地（见 git log `5eeb04b`…`3a0e548`），
本记录不再重复。M0 只处理 R01–R06 的**剩余**缺陷与业务文案。

### 已完成任务

- **M0-01 基线核查** — 复核 R01–R06,全部仍然存在(见上表);P1/P2 已修项确认不重复。
- **M0-02 R01(通知吞错)** — `RagIndexClient.deliverLifecycle` 的 `onStatus` 改为抛异常(非 2xx → false → 保留待重试记录)。
  - 验证(隔离 E2E):停 rag-service → 删除文件 → `pending_rag_sync` 记录保留(mode=soft, version=1);恢复 rag 后自动送达、文档软删、pending 清空。
- **M0-02 R02(乱序复活)** — `pending_rag_sync` 改「每文件最新目标状态 + 单调版本」(V7 迁移,file 侧 upsert version+1);
  rag 侧新增 `rag_lifecycle_version` 表 + `applyIfNewerVersion` 版本条件确认(旧版本请求直接忽略)。
  - 验证(隔离 HTTP):先 `soft&version=10`(生效、applied_version=10)→ 后 `restore&version=5`(返回 0,文档保持软删)。
- **M0-02 R03(实时超车积压)** — `TurnStreamRegistry.subscribeGated` + `ReplayGate`:积压发送期间实时事件排队,`open()` 后按序补发再切直发;控制器已切换。
  - 验证(隔离):排队期间无事件交付;open 后 `[1:A,2:B,3:C,4:D]` + 实时 E 单调递增,无超车。
- **M0-02 R04(cursor=0 漏报缺口)** — `snapshotWithGap` 改为 `afterSeq < oldest-1` 全量判定(含 cursor=0)。
  - 验证(隔离):8002 条后 `cursor=0 → gap=true oldest=3`;`cursor=2 → gap=false`;`cursor=1 → gap=true`。
- **M0-02 R05(取消即释放占位)** — `cancelTurn` 改为只中断不移除;占位由轮次 finally 释放;「取消先于启动」改用占位句柄 `isCancelled()` 精确判定(修掉会话级标志残留误伤新轮的回归)。
  - 验证(E2E):长轮取消 → 旧轮发 `done(stopped)` 后 43ms/5ms 新轮才启动(无并行);短轮完成后点取消 → 新轮真实执行(7 delta,stopped=false),不被残留标志误伤。
- **M0-02 R06(暂停/启用假成功)** — `toggleRule` 返回 `Promise<boolean>`,等服务器结果;失败回滚 + toast;调用方只在真实成功时提示。
  - 验证:代码路径 + 类型检查(浏览器 E2E 归入 M5 验收)。
- **M0-03 业务误导文案** — 「运行中」→「已启用」(规则状态);「创建修复任务」→「生成诊断报告」(只分析);
  「已触发」→「已完成」(结果已落库);「知识库 (Context Pipeline)」→「知识库」。
- **M0-04 结果全文** — `ExecutionRecord.detail` 保留全文 + `detailSummary` 派生摘要;执行历史列表展示摘要、点击行打开全文详情面板(可复制)。
  - 验证:类型检查通过;浏览器 E2E 归入 M5。

### 验证方式汇总(M0)

| 项 | 方式 | 结果 |
|---|---|---|
| R01 | 隔离 E2E(停 rag → 删除 → 查表 → 恢复 → 观察送达) | 通过 |
| R02 | 隔离 HTTP(高版本后低版本被拒) | 通过 |
| R03 | 隔离单测(门闩顺序) | 通过 |
| R04 | 隔离单测(cursor=0/1/2 缺口) | 通过 |
| R05 | E2E(取消/并发/残留标志三场景 + 日志时间线) | 通过 |
| R06 | 代码路径 + tsc | 通过(浏览器 E2E 在 M5) |

### 未验证 / 受阻项

- R06 的浏览器端真实失败回滚未做浏览器实测(M5 验收覆盖)。
- M0 未涉及 S1/S2/S3 场景(归 M2/M3/M4)。

---

## M1：四入口与资料视图（完成）

### 已完成任务

- **M1-01 四入口主导航** — Sidebar 从 10 个技术模块入口收敛为「助手 / 资料 / 任务 / 设置」;
  高级工具(数据源/环境控制台)保留路由 + 侧栏底部弱化直达,不占主导航。
  - 验证(浏览器):导航仅显示三项(设置单独在底部);各页激活态正确。
- **M1-02 助手首页与无会话输入** — 首页重写为「输入需求(首要焦点)+ 继续处理 + 最近成果 + 常用操作」;
  「开始新需求」创建新会话并带 prompt 跳转(**不自动发送**,用户可编辑/加资料);
  新增 `RunningTasks`(真实探测 /turn/live 聚合运行中轮次)、`RecentResults`(最近执行结果,可开全文)。
  - 验证(浏览器):输入 → 点击发送 → 跳转 `/chat?prompt=...&session=...`,输入框预填且未自动发送。
- **M1-03 资料页三视图** — `/files?view=files|knowledge|results`:
  全部文件(原有管理)/ 长期知识(KnowledgeView 组件,原知识库页主体)/ 已保存成果(SavedResultsView,执行结果全文)。
  视图切换写 URL,刷新/返回/复制链接一致。
  - 验证(浏览器):三视图 Tab + 知识子视图(文档库/检索测试/索引状态)全部渲染。
- **M1-04 设置分组** — 设置页 9 分组(通用/模型/连接与工具/技能/知识库与 AI/通知/安全/环境变量(高级)/网络(高级));
  MCP 管理归入「连接与工具」(ConnectionsView);技能归入「技能」(SkillsView);
  深链 `?section=<英文>` 稳定契约 + 兼容旧 `?tab=<中文>`;路由变化同步状态(不再只 mount 读取)。
  - 验证(浏览器):`/settings?section=connections` 激活「连接与工具」并渲染 MCP 管理。
- **M1-05 旧链接兼容** — `/automations` → `/tasks?view=schedules`(tab=执行历史 → view=history);
  `/knowledge`、`/skills`、`/mcp` 保留直达(内容与设置/资料内视图一致);
  `/files?open=`、`/files?workspace=` 深链未受影响;CommandPalette 更新为新导航。
  - 验证(浏览器):`/automations` 重定向到 `/tasks?view=schedules`;`/skills` 直达渲染。

### 验证方式汇总(M1)

| 项 | 方式 | 结果 |
|---|---|---|
| M1-01 | 浏览器(导航项 + 激活态) | 通过 |
| M1-02 | 浏览器(输入→新会话→预填未发送) | 通过 |
| M1-03 | 浏览器(三视图 + 知识子视图) | 通过 |
| M1-04 | 浏览器(section 深链 + 分组渲染) | 通过 |
| M1-05 | 浏览器(/automations 重定向、/skills 直达) | 通过 |
| 工程门禁 | tsc / lint / 126 测试 / build(gzip 97.34KB) | 通过 |

### 已知限制

- 「正在处理」视图只探测最近 10 个会话的运行中轮次(单用户量级足够;M3 引入运行持久化后可换后端聚合)。

---

## M2：任务上下文与成果（完成）

### 已完成任务

- **M2-01 结构化 context + 旧引用兼容** — 新增 `TaskContext` DTO(方案 §6.2 契约);
  `MessageRefResolver.resolveContext`:结构化 refs 优先(按 kind/id 解析,不信任 label),
  旧正文行仅回退且按 (kind,id) 去重;datasource 为声明类引用(注入"本次指定数据源");
  缺失/坏 id 产生**可见失败条目**。
  - 验证(E2E):文件引用注入正文;缺失文件/坏 id 均产生可见失败条目(不静默丢弃)。
- **M2-02 跨页「交给助手」统一交接** — `lib/handoff.ts` 统一 URL 协议(`prompt` + `refs` JSON);
  文件页批量栏新增「交给助手」;QueryConsole「让 AI 帮我写 SQL」携带 connectionId;
  chat 页解析 refs 预填引用 chip(可增删后再发,不自动发送)。
  - 验证(E2E + 浏览器):选中 2 文件 → 交给助手 → 指令预填 + 2 个引用 chip;
    数据源引用行注入「本次任务指定数据源」。
- **M2-03 成果保存** — 助手回答新增「保存为文件」:写入工作区真实 Markdown,
  **回读校验**后才报成功(方案要求"返回验证过的路径");与「保存到知识库」两个动作分别说明。
  - 验证(E2E):写入 `reports/m2-save-test.md` → 回读内容一致。
- **M2-04 最近成果 / 已保存成果** — `RecentResults`(助手首页,最近 5 条)与
  `SavedResultsView`(资料页视图,50 条,全文详情)共用 execution_record 权威数据,不复制正文。
  - 验证(浏览器):两处视图渲染正常,详情面板可打开全文。
- **M2-05 偏好真实持久化并应用** — 新端点 `/api/user-preferences`(白名单:报告语言/
  默认成果目录/命名习惯),app_setting 持久化;`ChatContextAssembler.userPreferencesSummary`
  注入每轮系统提示;设置页「通用」新增偏好表单。
  - 验证(E2E):保存三项偏好 → 对话请求体日志确认注入(语言/目录/命名全部出现)。

### 验证方式汇总(M2)

| 项 | 方式 | 结果 |
|---|---|---|
| M2-01 | E2E(结构化 refs 注入 + 失败可见) | 通过 |
| M2-02 | E2E + 浏览器(交接 URL → 预填 chip) | 通过 |
| M2-03 | E2E(写工作区 + 回读校验) | 通过 |
| M2-04 | 浏览器(两处视图) | 通过 |
| M2-05 | E2E(保存 → 注入确认) | 通过 |
| 工程门禁 | tsc / lint / 126 测试 / agent 测试 0 失败 | 通过 |

### 已知限制

- refs 的「资料范围即权限」语义未启用:当前 refs 是"本次参考资料",不限制工具访问(方案允许:
  不声称"仅访问这些资料"即可)。

---

## M3：运行状态与任务聚合（完成）

### 已完成任务

- **M3-01 对话运行持久化** — 新增 `chat_run` 表(V22):每轮开始落 running(稳定 runId =
  liveTurn.turnId,与 SSE 游标同源),结束**更新同一行**为终态(方案 §6.4 约束);
  `StaleRunRecovery` 启动时把遗留非终态标 `interrupted`(不自动重放有副作用任务);
  `GET /api/chat/runs`(状态过滤 + 会话标题 JOIN)。
  - 验证(E2E):正常轮 → completed(单行,started/finished 都有);取消轮 → cancelled;
    手动插入 running 行后重启 → interrupted(日志确认 "marked interrupted: 1")。
- **M3-02 状态衔接** — awaiting_approval(审批卡下发时)/ cancelling(cancel 端点受理时)/
  批准后回 running(工具重发 running 步骤时);终态:cancelled(用户取消)/
  partial(有失败工具步骤)/ completed / failed(编排异常)。
  - 验证(E2E):取消轮终态 = cancelled;`/runs` 状态过滤正确。
- **M3-03 任务页聚合** — 「正在处理」视图改用后端 `/runs` 持久化数据(刷新/换页/
  重启后都能找到);中断的运行单独呈现(提示"需手动决定是否重试")而非假装完成。
  - 验证(浏览器):空态正确(无假数据);active tab 正确。

### 验证方式汇总(M3)

| 项 | 方式 | 结果 |
|---|---|---|
| M3-01 | E2E(completed/cancelled/interrupted 三态 + 重启恢复) | 通过 |
| M3-02 | E2E(cancelling → cancelled) | 通过 |
| M3-03 | 浏览器(任务页正在处理) | 通过 |
| 工程门禁 | tsc / lint / 126 测试 / agent 测试 0 失败 | 通过 |

### 已知限制

- 任务页聚合目前覆盖对话运行(chat_run);自动任务执行记录仍在「执行记录」视图
  (两套权威存储,前端薄适配,方案 §6.4 第 4 条允许)。
- `partial` 判定基于"有失败工具步骤"的启发式;更精细的语义(如部分文件导入成功)
  由 S2 场景(M3-04 范围)在 fetch_media 结果里体现。

---

## M4：可用的定期任务（完成）

### 已完成任务

- **M4-01 日程契约与预览** — `schedule` JSONB 为唯一权威(frequency/localTime/
  dayOfWeek/timezone),`next_run_at` 为服务端计算的派生字段;`ScheduleCalculator`
  用 Java 时间 API 处理 DST;`POST /automations/schedule-preview` 返回未来 3 次;
  前端新建弹窗增加时间/星期/时区字段 + 预览按钮。
  - 验证(E2E):daily 09:00 预览 = 未来三天 09:00;创建 daily → nextRunAt 正确;
    weekly dayOfWeek=5 → nextRunAt 落在周五(9-25);浏览器预览显示"未来三次"。
- **M4-02 从成果创建定期任务** — 执行详情新增「设为定期任务」(带入名称与结果摘要,
  用户在弹窗确认日程,不静默继承)。
  - 验证(浏览器):按钮出现在详情 footer,跳转任务页并打开预填弹窗。
- **M4-03 调度语义** — `runDueScheduled` 按 nextRunAt 扫描:宽限内执行并推进;
  超宽限落 `missed_schedule` 记录(不自动补跑,方案 §7.2)。
  - 验证(E2E):拨 nextRunAt 到 1 分钟前 → 恰 1 条执行 + nextRunAt 推进;
    拨到 30 分钟前 → missed_schedule 记录 + 推进,不执行。
- **M4-04 去重** — 领取计划点用条件 UPDATE(仅当 nextRunAt 仍等于读取值)——
  重复扫描/双实例只有一个能领到(方案 §7.3 (ruleId, scheduledAt) 去重等效实现)。
  - 验证(E2E):一次触发恰一条执行记录。
- **M4-05 存量迁移** — V5 迁移:旧 daily/weekly 无日程规则暂停并标 `needs_config`;
  旧 file/error 规则同标;列表显示「需配置」徽章。create 拒绝 file/error 类型。
  - 验证:迁移已应用(现有 manual 规则不受影响);UI 徽章代码就位。

### 验证方式汇总(M4)

| 项 | 方式 | 结果 |
|---|---|---|
| M4-01 | E2E(daily/weekly nextRunAt + 预览)+ 浏览器 | 通过 |
| M4-02 | 浏览器(详情按钮 + 预填) | 通过 |
| M4-03 | E2E(宽限执行 + 错过记录) | 通过 |
| M4-04 | E2E(单次触发恰一条) | 通过 |
| M4-05 | 迁移应用 + UI 代码 | 通过 |
| 工程门禁 | tsc / lint / automation 测试 0 失败 / 126 前端测试 | 通过 |

### 已知限制

- 无人值守权限收敛(方案 §7.4:定期任务默认只读 + 受限保存)未在本阶段实现——
  当前 Agent 动作仍走 `/agent/run`(FULL 档)。此项涉及执行器权限模型改造,
  按方案 §7.4 最后一条:**现有依赖 FULL 的规则应标"需确认执行范围"**——
  留待 M5 验收时与用户确认范围后实施(属产品取舍,需用户决定)。

---

## M5：发布候选验收（进行中）

### 已完成

- **M5-01 工程检查** — 前端 tsc ✓ / lint ✓ / 126 测试 ✓ / build(主包 gzip 97.45KB ≤180KB 目标)✓;
  后端全量 `mvn test` 0 失败。
- **M5-01 场景验收（真实服务 E2E）**：
  - **S1 多资料比较报告** — 上传 2 份真实测试资料 → 结构化 refs 交给助手 →
    报告正确对比核心功能/性能/价格三项(两份资料均注入 `【用户引用的文件内容】`)→
    保存为工作区真实 Markdown(`reports/s1-对比报告.md`,837 字)并**回读校验一致**。
  - **S2 手机照片整理** — 手机当前离线(phoneOnline=false):工具如实失败
    (「无法连接。远程 MCP 服务器不可达(手机/中继离线)」),模型明确说明"不做猜测"
    并给出本地已有素材现状,不编造导入结果。**部分验证**(离线路径正确;在线全链路
    因手机不在线未验证——标注为未验证)。
  - **S3 成果转每周任务** — 创建 weekly 任务(周五 09:00)→ nextRunAt=9-25(周五)正确;
    手动试跑真实执行(如实报告手机离线)→ **nextRunAt 保持不变**(试跑不替代计划)。
- **M5-02 包体/懒加载** — 构建验证主包 gzip 97.45KB,页面独立 chunk,无整页崩溃。
- **M5-03 文档同步** — 根 README 重写为当前行为(四入口/核心闭环/文档索引);
  nora-web README/AGENTS 路由与目录描述更新。

### 未验证 / 受阻项（如实记录）

- **S2 在线全链路** — ✅ **已补测（2026-09-20 晚，手机 M332BF 在线）**：
  - MCP 工具在线可用：`photos_stats` 返回真实统计（1692 项/10.46GB）；
  - 「今天拍了多少」→ 准确区分拍摄视频与截图/转发图（21 条媒体明细）；
  - 「整理今天 4 段视频到工作区」→ `photos_search` 找候选 → `manage_workspace`
    查已有避免重复 → `fetch_media` **下载 4/4 成功**，263.8MB 真实落盘
    （文件大小与磁盘逐项一致）；报告准确（无失败无跳过）；
  - 复制操作（工作区内部）4/4 成功、字节一致。
  - 未覆盖：真实「部分失败→重试失败项」路径（本次 4/4 全成功，无失败项可重试）；
    大范围批量（数百张）的进度/续传此前已由 fetch_media 进度条与取消链路覆盖。
- **无人值守权限(方案 §7.4)** — **用户已确认决策(2026-09-20):维持 FULL 档**。
  定期任务到点自动执行时无审批,可调用本机终端等工具(与对话页「完全访问」档相同);
  仅删除/注册类不可逆操作(CRITICAL)被强制拒绝——此为现有设计,用户明确选择保留。
  配套:新建定期任务弹窗新增权限说明(明确"无人审批+可调用本机工具"范围),
  让每次创建都是"知道自己在选什么"。
- **备份恢复演练**(数据库/文件/工作区):涉及用户真实数据,未执行。
