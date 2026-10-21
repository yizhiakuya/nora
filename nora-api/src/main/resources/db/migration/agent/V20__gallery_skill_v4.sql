-- 产物画廊技能对齐 v4 协议(2026-09-19):V19 的正文还是 v2/v3 的
-- sections[{kind,...}] 格式,而 v4 前端协议是 {gallery: "...", ...}(一条围栏
-- 一个画廊,按业务选组件)。前端有兼容层不破历史消息,但全新部署(迁移重建)
-- 会装回旧协议技能,agent 学错格式——本迁移把技能正文对齐到当前线上 v4 版本
-- (E2E 期间经 API 更新的实际内容,含画廊类型目录表)。
UPDATE agent_skill SET
    instructions = '# 产物画廊指南

你完成**多文件操作**(建文件夹、批量移动/改名、导入素材、整理归档等)后,
除了文字汇报,还应附一个**产物画廊**——在最终回答末尾放 ```nora-artifacts 围栏
(结构化 JSON),前端会把它渲染成精美的卡片:统计条、图片网格(点击开灯箱)、
文件列表(点击直接打开文件页)。**你只填数据,样式与交互由前端原生组件负责**。

## 何时出画廊

- **出**:多文件操作(≥5 个文件增删改)、批量整理/归档、导入素材、对比类结果;
- **不出**:单文件小改动、纯查询回答、日常对话——直接文字说清楚即可。

## 协议(回答末尾的围栏)

**一条围栏 = 一个画廊**(`gallery` 字段选类型,其余字段是该画廊自己的数据)。
需要多个视图时放多条围栏(每条独立渲染)。

````text
【围栏格式(示意,实际输出时用 ``` 包裹)】
nora-artifacts
{
  "gallery": "media",
  "title": "「任务名」整理完成",
  "summary": "N 个文件从 源目录 移入 目标目录",
  "stats": [{"label": "文件", "value": "N"}, {"label": "大小", "value": "X MB"}],
  "items": [
    {"kind": "image", "url": "原图/压缩流地址(灯箱用)", "thumbUrl": "缩略图地址(网格用)",
     "caption": "「说明」", "meta": "X MB · 时间"}
  ],
  "note": "可选:补充说明"
}
````

````text
【另一个例子:table 画廊】
nora-artifacts
{
  "gallery": "table",
  "title": "各分类技能数量",
  "columns": [{"key": "cat", "label": "分类"}, {"key": "n", "label": "数量", "align": "right"}],
  "rows": [{"cat": "工作台", "n": "2"}]
}
````

## 画廊类型目录(按业务选 `gallery` 值)

| gallery | 用在哪 | 字段 |
|---|---|---|
| `media` | 相册/图片/视频展示(手机相册同款效果) | items: url(灯箱原图/压缩流)/thumbUrl(网格缩略图)/kind/caption/meta |
| `files` | 文件增删改/归档 | items: name/note(动作)/meta/open(深链) |
| `table` | SQL 查询/统计对比/清单 | columns:[{key,label,align?}] + rows:[{key:值}] |
| `diff` | 设置变更/配置修改 | items: name/before(改前)/after(改后) |
| `timeline` | 任务执行/事件序列 | items: name/status(done|failed|running|pending)/meta(时间) |
| `keyvalue` | 单对象详情(如新建的数据源) | items: name(键)/meta(值) |
| `text` | 长文报告/分析结论 | text: "Markdown 正文" |
| `list` | 兜底(不确定时用,永不报错) | items: name/caption/meta/open |

**选择原则**:一个任务需要多个视图就放**多条围栏**(每条一个 gallery);
拿不准时用 `list`(通用兜底)。

## 图片/视频引用(media 画廊)

- **工作区文件**:`/api/workspace/file/raw?path=<URL编码的相对路径>`
  (如 `/api/workspace/file/raw?path=photos%2F2026-09-16-show%2FVID_1.mp4`);
- **手机相册(经 MCP 返回的 URL)**:直接用工具结果里的 URL 原样填
  (前端会自动走磁盘缓存代理;视频会走压缩流转码播放;photos_showcase 的
  结果本身就是统一协议围栏,可直接复用其中的 url/thumbUrl);
- **文件中心**:用 `file:<id>` 深链(放在 files 画廊里)。

## 数据纪律(重要)

- **全部数据来自真实工具结果**:数量/大小/路径不许编造;不确定就再 list 一次;
- media 画廊的图片必须是**真实可加载的地址**(工具返回过的 URL 或工作区路径),
  否则灯箱会空白;
- files 画廊的 open 深链必须指向**真实存在的路径/id**(猜的路径点开是错误页)。

## 示例场景

- 「把相册最近一个月整理到工作区」→ 两条围栏:media 画廊(下载的图片缩略图)
  + files 画廊(目标目录 open=workspace:<路径>);
- 「批量重命名」→ files 画廊逐条(旧名 → 新名,open=workspace:新路径);
- 「导出一批文档」→ files 画廊(open=workspace:... 或 file:<id>)。

## 反例

- 把画廊当装饰:纯聊天也附一个空画廊 → 无意义(空 items 会被前端丢弃);
- 在回答里手写 HTML/CSS → 不要(v2 协议只收 JSON,样式由前端负责);
- 编造 meta 数据("大约 500MB") → 必须来自工具真实输出。',
    updated_at = NOW()
WHERE name = '产物画廊指南' AND deleted_at IS NULL;
