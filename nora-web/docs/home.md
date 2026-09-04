# 首页概览 · 工作台入口（2026-09-04）

> 状态：已实现 · 范围：纯前端 Mock · 遵循 AGENTS.md 前端边界

## 1. 背景与目标

首页是进入 Nora 后的第一屏，聚合“搜文件、快操作、最近动态、状态总览”，让用户 5 秒内知道能做什么、该做什么。

**非目标**：真实统计、后端聚合、SSE 推送。所有数据来自本地 Mock + Zustand 持久化。

## 2. 页面结构

```
app/page.tsx
├── Header（搜索入口 + 上传按钮）
├── 欢迎语（“早上好，Nora！”+ 副标题）
├── QuickActions（4 快捷入口）
└── 双栏
    ├── RecentFilesTable（最近文件，支持索引徽章/预览）
    └── HomeSidePanel（知识库/数据源/环境状态缩略）
```

- `max-w-6xl mx-auto` 居中，`animate-in fade-in slide-in` 入场动效
- 移动端 `lg:flex-row` 切换为单列，Header 搜索框响应式隐藏为图标

## 3. 关键交互

| 模块 | 行为 |
|------|------|
| 全局搜索 | `⌘K` / `Ctrl+K` 打开 `CommandPalette`，`ESC` 关闭；Header 搜索框本身 `readOnly`，点击即开面板 |
| 上传 | `useSimulatedUpload` 控制 `UploadModal`，支持 PDF/DOCX/XLSX/图片，文件名真实回写 files store |
| 快捷动作 | `QuickActions` 4 卡片：上传文件 / 新建对话 / 连接数据源 / 查看知识库 |
| 最近文件 | `RecentFilesTable` 展示最近 N 条，`indexed` 徽章区分已入知识库；点击文件名开 FileViewer 预览 |
| 侧栏状态 | `HomeSidePanel` 聚合数据源连通、环境健康、知识库索引数，均为 devData/knowledgeData 派生 |

## 4. 状态与数据

| Hook/数据 | 说明 |
|-----------|------|
| `useUpload` (`hooks/useUpload.ts`) | 模拟上传状态机（queued→uploading→done），真实文件名回写 |
| `useRecentFiles` | 最近文件派生（基于 files + knowledgeDocs） |
| `useNotifications` | 上传/索引完成时推送通知 |
| `knowledgeData.ts` / `devData.ts` | 侧栏统计来源 |

## 5. 验证

- `⌘K` / 点击搜索框 → CommandPalette 出现；`ESC` 关闭
- 上传文件后 RecentFilesTable 新增一行，知识库文档数 +1
- `pnpm --dir nora-web build` 正常
