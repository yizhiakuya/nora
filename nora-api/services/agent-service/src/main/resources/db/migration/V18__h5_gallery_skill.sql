-- H5 画廊指南技能(2026-09-18):agent 自写 HTML 展示页的权威指引。
-- 完成多文件操作后,agent 按本指南写自包含 HTML 存到 galleries/,并在回答里
-- 放 ```nora-artifact 围栏——前端沙箱内嵌渲染(ArtifactBlock)。
-- 种子用 ON CONFLICT DO NOTHING:用户/agent 改过正文不会被迁移覆盖。
INSERT INTO agent_skill (name, description, instructions, category, enabled)
VALUES (
    'H5 画廊指南',
    '完成多文件操作/整理/批量任务后,用自包含 HTML 画一个成果展示页(画廊),用户可在对话里直接查看与交互。写画廊前读本指南。',
    '# H5 画廊指南

你完成**多文件操作**(建文件夹、批量移动/改名、导入素材、整理归档等)后,
除了文字汇报,还应画一个**自包含 HTML 展示页**——用户在内嵌沙箱里直接看,
不必点进文件页逐个核对。

## 何时画

- **画**:多文件操作(≥5 个文件增删改)、批量整理/归档、导入素材、对比类结果(前后差异);
- **不画**:单文件小改动、纯查询回答、日常对话——直接文字说清楚即可。

## 三步流程

1. **收集事实**:确认产物清单(路径、大小、数量)——数据必须来自真实工具结果,
   不许编造。可用 manage_workspace list 拿目录摘要。
2. **写 HTML**:manage_workspace write 存到 `galleries/<日期>-<主题>.html`。
3. **回答附围栏**(回答末尾,别忘):
   ````text
   ```nora-artifact
   {"path": "galleries/2026-09-18-相册整理.html", "title": "相册整理结果", "height": 560}
   ```
   ````

## HTML 写法(硬性约定)

- **自包含**:数据/样式/脚本全部内联在一个文件里。**禁止 fetch/XMLHttpRequest**
  (沙箱里调不到 Nora API);**禁止外链 CDN**(断网即白屏)。
- **样式用 Tailwind class**:沙箱已注入 Tailwind 运行时,直接用 class
  (如 `flex gap-3 rounded-xl border p-4`)。少量特殊样式可用 `<style>` 补充。
- **数据嵌入**:用 `const DATA = [...]` 内联 JSON,再 `render()` 生成 DOM——
  便于交互(筛选/排序/搜索)。
- **图片引用**:工作区图片用 `/api/workspace/file/raw?path=<相对路径>`(URL 编码);
  媒体缓存图片用 `/api/media/cache?url=<原始URL>`。
- **主题适配**:根元素 `<html class="dark">` 已由宿主按 Nora 主题设置;
  写颜色时同时给亮/暗两套(如 `bg-white dark:bg-zinc-900 text-zinc-900 dark:text-zinc-100`)。
- **高度**:内容区自适应,不用写 `100vh`(宿主 iframe 高度由围栏 height 控制);
  长列表可内部滚动 `max-h-96 overflow-auto`。

## 交互能力(你可以尽情用)

- 筛选/排序/搜索(纯 JS 操作 DATA 重渲染);
- Tab 切换(如「新增 / 修改 / 删除」分栏);
- 点击卡片展开详情(display 切换);
- 复制路径到剪贴板(`navigator.clipboard` 在沙箱里可用)。

## 结构模板(按需取用)

```html
<div class="p-5 max-w-4xl mx-auto">
  <!-- 标题区 -->
  <h1 class="text-lg font-bold text-zinc-900 dark:text-zinc-100">相册整理结果</h1>
  <p class="text-xs text-zinc-500 mt-1">2026-09-18 · 28 个视频 → photos/2026-09-16-show</p>

  <!-- 统计条 -->
  <div class="grid grid-cols-3 gap-3 mt-4">
    <div class="rounded-xl border border-zinc-200 dark:border-zinc-800 p-3">
      <div class="text-2xl font-bold text-zinc-900 dark:text-zinc-100">28</div>
      <div class="text-xs text-zinc-500">文件</div>
    </div>
    <!-- … -->
  </div>

  <!-- 卡片网格(交互:点击筛选/展开) -->
  <div id="grid" class="grid grid-cols-2 md:grid-cols-4 gap-3 mt-4"></div>
</div>
<script>
  const DATA = [
    { name: "VID_20260916_1701.mp4", size: "412 MB", url: "/api/workspace/file/raw?path=photos%2F2026-09-16-show%2FVID_20260916_1701.mp4" },
    // …真实数据
  ];
  const grid = document.getElementById("grid");
  grid.innerHTML = DATA.map((f, i) => `
    <div class="rounded-xl border border-zinc-200 dark:border-zinc-800 overflow-hidden cursor-pointer hover:shadow-md transition-shadow">
      <img src="${f.url}" class="w-full h-32 object-cover" loading="lazy">
      <div class="p-2">
        <div class="text-xs font-medium text-zinc-900 dark:text-zinc-100 truncate">${f.name}</div>
        <div class="text-[10px] text-zinc-500">${f.size}</div>
      </div>
    </div>`).join("");
</script>
```

## 反例(会被沙箱拒绝或白屏)

- `fetch("/api/workspace/files")` → 沙箱无同源,调用失败;
- `<script src="https://cdn.tailwindcss.com">` → 外链,断网白屏;
- 依赖 localStorage/cookie → 沙箱 opaque origin 读不到;
- 把 10MB 图片 base64 嵌进 HTML → 单文件过大,加载卡顿(用 raw URL 引用)。

## 打磨建议

- 画廊是给用户**核对成果**的,不是炫技:信息密度优先(路径、大小、数量、时间);
- 配色克制,复用 Tailwind 中性色 + 一个主题色;
- 交互要有目的(筛选、定位、复制路径),不做无意义动画。',
    '工作台',
    TRUE
)
ON CONFLICT (name) WHERE deleted_at IS NULL DO NOTHING;
