# 功能设计文档：文件查看（File Viewer）

> 状态：已实现 · 范围：纯前端 Mock · 遵循 AGENTS.md 前端边界

## 1. 背景与目标

文件中心目前只有「列表 + 批量选择 + 上传」，文件本身点不开，列表是"只读死数据"。
本功能为文件补充**预览查看**能力：点击文件即可在弹窗中查看内容，不同类型给出差异化预览，
让文件中心形成「浏览 → 查看 → 操作（下载/收藏）」的完整闭环。

**非目标**（遵守前端边界，不实现）：真实文件上传与解析、后端存储、PDF 渲染引擎（如 pdf.js）、
Office 格式真实转换。所有内容均为 Mock 数据。

## 2. 交互设计

| 交互 | 行为 |
|------|------|
| 单击**文件名** | 打开预览弹窗（`stopPropagation`，不触发行选择） |
| 单击行内「眼睛」操作按钮 | 打开预览弹窗 |
| 单击行其余区域 | 保持现有行为：切换选中 |
| `ESC` / 点击遮罩 / 关闭按钮 | 关闭弹窗并复位状态 |
| 弹窗头部「下载」/「收藏」 | toast 提示（Mock） |

加载策略：打开即进入 `loading` 态（骨架行），Mock 延迟 500ms 后渲染预览；
连续快速打开不同文件时丢弃过期响应（请求序号守卫）。

## 3. 预览类型矩阵

| FileItem.type | kind | 预览形态 |
|---------------|------|----------|
| PDF 文档 | `pdf` | 模拟纸张页 + 底部页码导航（上一页/下一页） |
| Word 文档 | `word` | 文档正文（prose 排版，多段落） |
| Excel 表格 | `excel` | 表头 + 网格数据表 |
| PNG 图像 | `image` | 图像居中预览（内联 SVG 占位图，无外部请求） |
| 纯文本 | `text` | 等宽深色代码块 |
| 其他 | `unknown` | 不支持预览提示 + 下载引导 |

## 4. 数据模型（src/types/index.ts）

```ts
export type FilePreviewKind = "pdf" | "word" | "excel" | "image" | "text" | "unknown";

export interface FilePreview {
  kind: FilePreviewKind;
  pages?: number;                                  // pdf 页数
  text?: string;                                   // word / text 正文
  table?: { columns: string[]; rows: string[][] }; // excel
  imageUrl?: string;                               // image（data URI）
}
```

`kind` 由 `FileItem.type` 映射（`includes("PDF") → pdf` 等），映射收敛在 Mock API 内。

## 5. 数据层（src/lib/api/mockApi.ts）

```
MockAPI.files.getPreview(file: Pick<FileItem, "id" | "name" | "type">): Promise<FilePreview>
```

`delay(500)` 模拟网络；按 kind 生成内容（正文模板中嵌入文件名，保证每个文件看起来不同）。

## 6. 状态机（src/hooks/useFileViewer.ts）

```
idle ──open(file)──▶ loading ──成功──▶ ready ──close()──▶ idle
                        └──失败──▶ error ──close()/retry──▶ …
```

- `open` 时立即置 `activeFile` 并进入 `loading`（弹窗秒开，内容骨架屏）
- 请求序号 `requestSeq` 防竞态：仅接受最新一次请求的结果
- `close` 复位全部状态

## 7. 组件拆分（src/components/files/viewer/）

```
FileViewerModal.tsx      弹窗外壳（复用 ui/custom/Modal）：头部元信息 + 操作 + 按 kind 分发
preview/PdfPreview.tsx   纸张页 + 页码导航（内部 useState 管理当前页）
preview/WordPreview.tsx  正文排版
preview/ExcelPreview.tsx 网格表
preview/ImagePreview.tsx 图像预览
preview/TextPreview.tsx  等宽文本
preview/Unsupported.tsx  不支持提示
```

## 8. 接入点

1. **文件中心**（files/page.tsx）：`useFileViewer` 提升到页面，传给 `FileTable`
2. **首页最近使用**（home/RecentFilesTable.tsx）：文件名可点击，自持一套 viewer 实例

## 9. 验收清单

- [ ] 5 种 Mock 文件类型分别打开，预览形态符合矩阵
- [ ] loading 骨架屏出现，~500ms 后内容渲染
- [ ] PDF 页码导航可用（首/尾页按钮禁用态正确）
- [ ] 文件名点击不触发行选中；行空白处点击仍可选中
- [ ] ESC / 遮罩 / 关闭按钮均可关闭，关闭后再开另一文件状态干净
- [ ] 首页最近使用文件可打开同一预览
- [ ] `pnpm typecheck` / `next lint` / `pnpm test` / `next build` 全绿
