# Nora 功能演示

真实浏览器录制的功能演示(Playwright 驱动真实对话,未经剪辑的完整操作流)。

**总览视频**(README 首页内嵌播放):

| 视频 | 内容 | 时长 |
|---|---|---|
| [nora-demo.mp4](nora-demo.mp4) | **完整演示**:① 手机相册 MCP → ② 对话交付文件闭环 → ③ 知识库问答 → ④ 数据源查询(含章节标题卡) | ~104s |

分场景短片(同一批录制的分段版):

| 视频 | 内容 | 时长 |
|---|---|---|
| [demo-0-phone-album.mp4](demo-0-phone-album.mp4) | **手机相册 MCP**:对话查看手机相册(相册列表 / 照片检索) → 从「猫」相册挑选 4 张真猫照片在对话中展示 | ~28s |
| [demo-1-chat-file-delivery.mp4](demo-1-chat-file-delivery.mp4) | **对话交付文件闭环**:对话让 Nora 写笔记 → 工具调用链展示 → 「已编辑 1 个文件 +23 -0」编辑卡片 → 文件查看器自动打开 | ~27s |
| [demo-2-knowledge-qa.mp4](demo-2-knowledge-qa.mp4) | **知识库问答**:基于 RAG 资料回答 + 引用来源标注(引用片段可展开) | ~16s |
| [demo-3-sql-query.mp4](demo-3-sql-query.mp4) | **数据源真实查询**:execute_sql 两次查询,按 schema 统计表数量,工具调用链完整可见 | ~21s |

> README 首页的内嵌播放器走 GitHub 附件(user-attachments),源文件即本目录的 `nora-demo.mp4`;分场景短片以仓库内文件形式提供,点击进文件页可播放。

## 录制方式

Playwright(系统浏览器)驱动 1280×800 视口,`recordVideo` 录制;真实后端(8 服务 + PG/Redis/Nacos/Kafka)+ 真实 LLM 对话,无 mock、无剪辑拼接(仅裁掉开头的页面加载白屏)。

总视频由分场景短片 + 标题卡用 ffmpeg concat 合成;README 内嵌用的附件通过 GitHub 网页编辑器上传(唯一能让 README 渲染原生播放器的通道——`<video>` 标签会被 GitHub 过滤,见 [GitHub 文档](https://docs.github.com/en/get-started/writing-on-github/working-with-advanced-formatting/attaching-files))。

录制脚本模板(在仓库外任意目录):

```js
import { chromium } from 'playwright';

const browser = await chromium.launch({ channel: 'chrome' });
const context = await browser.newContext({
  viewport: { width: 1280, height: 800 },
  recordVideo: { dir: 'videos', size: { width: 1280, height: 800 } },
});
await context.addInitScript(() => localStorage.setItem('nora-auth-token', '<令牌>'));
const page = await context.newPage();
// ... 驱动操作 ...
await context.close();  // 视频在 context 关闭时落盘
```

转 mp4:`ffmpeg -ss 2 -i page@xxx.webm -c:v libx264 -pix_fmt yuv420p -crf 23 -movflags +faststart demo.mp4`(webm 为 Playwright 原始格式;`-ss 2` 裁掉加载白屏)。

## 备注

- 演示数据为录制时的真实状态;Nora 是单用户自部署工作台,界面文案与交互以当前代码为准
- 视频文件提交在仓库中(共约 3MB),GitHub 上可直接点开播放
