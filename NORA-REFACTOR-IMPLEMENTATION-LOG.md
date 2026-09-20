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
