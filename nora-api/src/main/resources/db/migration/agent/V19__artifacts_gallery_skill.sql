-- 产物画廊协议 v2(2026-09-18 晚):替换 V18 的 H5 方案(用户反馈:AI 自写
-- HTML 质量不可控、无原生交互、风格不统一)。v2 = 结构化 JSON + 前端原生组件
-- 渲染(与手机相册画廊同款体验:灯箱看图、点击开文件、统一设计语言)。
UPDATE agent_skill SET
    name = '产物画廊指南',
    description = '完成多文件操作/整理/批量任务后,用 ```nora-artifacts 围栏声明成果(结构化 JSON,前端原生渲染成画廊卡片)——用户可在对话里直接查看、点开文件与图片。写画廊前读本指南。',
    instructions = '# 产物画廊指南

你完成**多文件操作**(建文件夹、批量移动/改名、导入素材、整理归档等)后,
除了文字汇报,还应附一个**产物画廊**——在最终回答末尾放 ```nora-artifacts 围栏
(结构化 JSON),前端会把它渲染成精美的卡片:统计条、图片网格(点击开灯箱)、
文件列表(点击直接打开文件页)。**你只填数据,样式与交互由前端原生组件负责**。

## 何时出画廊

- **出**:多文件操作(≥5 个文件增删改)、批量整理/归档、导入素材、对比类结果;
- **不出**:单文件小改动、纯查询回答、日常对话——直接文字说清楚即可。

## 协议(回答末尾的围栏)

````text
```nora-artifacts
{
  "title": "相册整理完成",
  "summary": "28 个视频归档到 photos/2026-09-16-show,源目录已清空",
  "stats": [
    {"label": "文件", "value": "28"},
    {"label": "大小", "value": "409.7 MB"},
    {"label": "耗时", "value": "15s"}
  ],
  "sections": [
    {
      "kind": "media",
      "title": "已归档的视频",
      "items": [
        {"kind": "video", "url": "https://.../photo/123/video", "caption": "VID_20260916_1701",
         "meta": "412 MB · 17:01"},
        {"kind": "image", "url": "/api/workspace/file/raw?path=photos%2F2026-09-16-show%2Fa.jpg",
         "caption": "开场", "meta": "3.2 MB"}
      ]
    },
    {
      "kind": "files",
      "title": "工作区变更",
      "items": [
        {"kind": "folder", "name": "photos/2026-09-16-show", "note": "新建目录",
         "meta": "28 个文件", "open": "workspace:photos/2026-09-16-show"},
        {"kind": "file", "name": "MEMORY.md", "note": "已更新", "open": "workspace:MEMORY.md"},
        {"kind": "file", "name": "合同.pdf", "note": "已重命名", "open": "file:12"}
      ]
    }
  ],
  "note": "如需调整归档结构告诉我"
}
```
````

## 字段说明

- **title**(必填):一句话标题;summary:一句话摘要。
- **stats**:统计条(2-4 个最佳),值用字符串(如 "28"、"409.7 MB"、"15s")。
- **sections**:分组数组,每段有 kind/title/items:
  - `media` → 图片/视频网格(点击开**灯箱**放大,可 ←/→ 连续浏览);
  - `files` → 文件行列表(有 open 深链的可点击**直接打开**);
  - `list` → 通用条目(默认,展示为主)。
- **item 字段**:
  - `kind`:image / video / file / folder / link / text(可省,按 url 扩展名自动推断);
  - `name`:展示名;`caption`:单条说明;`meta`:元信息(大小/时间/数量);
  - `url` / `fullUrl` / `thumbUrl`:媒体地址(见下方图片引用);
  - `open`:深链打开指令(**仅 files/list 段有意义**)。
- **open 深链**(让用户一点直达):
  - `workspace:<相对路径>` → 打开文件页的工作区浏览器(文件/文件夹都行);
  - `file:<数字id>` → 打开文件中心的文件预览(需要文件中心 id);
  - `url:<链接>` → 新窗口打开;
  - 省略 → 仅展示(不可点击)。

## 图片/视频引用(media 段)

- **工作区文件**:`/api/workspace/file/raw?path=<URL编码的相对路径>`
  (如 `/api/workspace/file/raw?path=photos%2F2026-09-16-show%2FVID_1.mp4`);
- **手机相册(经 MCP 返回的 URL)**:直接用工具结果里的 URL 原样填
  (前端会自动走磁盘缓存代理;视频会走压缩流转码播放);
- **文件中心**:用 `file:<id>` 深链(不是 media 段)。

## 数据纪律(重要)

- **全部数据来自真实工具结果**:数量/大小/路径不许编造;不确定就再 list 一次;
- media 段的图片必须是**真实可加载的地址**(工具返回过的 URL 或工作区路径),
  否则灯箱会空白;
- files 段的 open 深链必须指向**真实存在的路径/id**(猜的路径点开是错误页)。

## 示例场景

- 「把相册最近一个月整理到工作区」→ media 段(下载的图片缩略图)+ files 段
  (目标目录 open=workspace:photos/...)+ stats(数量/大小/耗时);
- 「批量重命名」→ files 段逐条(旧名 → 新名,open=workspace:新路径);
- 「导出一批文档」→ files 段(open=workspace:... 或 file:<id>)。

## 反例

- 把画廊当装饰:纯聊天也附一个空画廊 → 无意义(空 sections 会被前端丢弃);
- 在回答里手写 HTML/CSS → 不要(v2 协议只收 JSON,样式由前端负责);
- 编造 meta 数据("大约 500MB") → 必须来自工具真实输出。',
    category = '工作台',
    enabled = TRUE,
    updated_at = NOW()
WHERE name = 'H5 画廊指南' AND deleted_at IS NULL;
