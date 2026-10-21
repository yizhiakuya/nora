# Nora 前端规范

先遵守 [根项目规范](../AGENTS.md)。本文件只维护前端约束；技术栈、目录和运行命令见 [README](README.md)，业务接口定位见 [接入参考](docs/api-integration-status.md)，验收步骤见 [真实服务验收](../docs/dev/acceptance.md)。

## 职责与组织

- `src/App.tsx` 集中注册路由，`src/app/*/page.tsx` 只组装页面；业务 UI 放 `components/{domain}`，请求编排放 `hooks`，共享业务类型放 `types`，局部 props 和契约类型就近维护。拆分按职责，不按行数。
- 路由使用 React Router 的 `Link` / `useNavigate` / `useLocation`，懒加载使用 React `lazy` / `Suspense`，主题复用 `components/ThemeProvider.tsx`；不恢复 Next.js shim 或别名。
- 前端负责呈现与交互，不直连数据库、不承担后端权限或业务校验。需要改接口时阅读后端规范，同步两端契约及调用方。

## 请求、错误与认证

- 组件和 Hook 调用现有 `lib/api/` 或 `lib/services/` 领域模块；普通 JSON 请求统一经 `lib/api/client.ts` 的 `requestJson`。SSE、上传和下载复用已有专用入口，不在组件中直接 `fetch`，不再包装一套 HTTP 客户端。
- 新接口优先扩展所属领域模块；只有新增独立领域才新建契约文件。DTO 与前端类型不一致时先确认目标契约，不用 `any`、强制断言或字段别名掩盖差异。
- loading、空结果、失败分别展示；后台刷新可保留旧数据，但不能把旧数据当作刚刚成功获取的结果。未接通的能力明确不可用，不用 Mock 或默认成功代替。
- 同一错误展示一次，使用契约提供的正文，不自行翻译、截断、折叠或添加猜测性建议；保留已有 traceId 等定位信息。服务端负责对错误脱敏，前端不展示凭据。
- 需要认证的本站媒体/下载入口使用 `withAuthToken()`；不得给外部链接附加 Nora 令牌。

## 状态与异步生命周期

- 页面临时状态就近管理；跨页面数据复用已有 Zustand store。同一实体只保留一个可写来源，API 层只传输和解码，不维护第二份业务状态。
- `persist` 只保存确需跨刷新保留的偏好、草稿等字段；服务端事实不全量复制到 localStorage，运行状态不以本地缓存为准。变更已有存储结构时处理旧数据迁移与损坏恢复。
- 乐观更新须能回滚且不覆盖后续操作；权限、思考等级等运行设置保存成功后再更新共享状态。
- 切换实体、卸载组件时清理订阅与计时器，取消或忽略过期请求；迟到响应不能覆盖新状态，重复提交须有执行中的反馈。轮询优先复用 `usePollingResource`。
- 聊天发送和恢复共用 `ChatStreamEvent` 解码入口与 `useChat` reducer；文字缓冲先于控制和终态事件刷新。快照与增量衔接保持顺序、不重复应用，恢复历史不自动打开旧文件。

## 跨入口一致性

- 输入引用协议由 `lib/chatRefs.ts` 与后端 `MessageRefResolver` 共同维护；改格式时同步发送、历史解析与上下文注入，验证已有会话可读。
- 执行状态复用 `lib/executionStatus.ts`；unknown、取消、中断不能简化为成功/失败。环境状态保留观察主机和执行域，日志存在不等于服务健康。
- 成果保存复用 `savedArtifactsApi.ts`，分别反映文件保存与成果登记结果；登记失败不能显示全部成功。附件、工作区、媒体缓存继续归入根规范的文件中心体系。
- Markdown 复用 `components/shared/Markdown`；文件、网页和产物沿用已有查看器、深链接及渲染组件。
- 网页与文件统一进入 `components/layout/SideWorkspace` 的标签面板，共用收起、展开和宽度调整；浏览器状态由该布局提供，对话从 `WorkspaceContext` 读取。
- 内容中的网页链接复用 `components/shared/BrowserLink`，按 `usePreferences` 的偏好打开，默认在内置浏览器新建标签；文件引用、下载和外部授权保留专用流程。

## 界面与无障碍

- 颜色、圆角、阴影复用 `globals.css` 与 `tailwind.config.ts` 的主题变量；按钮、输入、下拉、分类切换和弹窗使用现有 `ui/` 控件，通用弹窗与上传复用 `ui/custom/Modal`、`UploadModal`。
- 管理页复用 `page-content`、`page-container`、`page-heading`、`page-description`，卡片用 `surface`；聊天内容和输入框共同保持 820px 最大宽度。
- 白色或中性色承载内容，蓝色表示主要操作；成功、警告、失败保留语义色，避免随机彩色卡片和装饰渐变。
- 正文和控件以 13–14px 为主，辅助信息不低于 11px，手机文本输入使用 16px。卡片随内容增长，列数随实际容器宽度调整，窄屏内容能换行。
- 图标操作有可访问名称，交互支持键盘；弹窗关闭后恢复焦点，关闭的手机导航从可访问树隐藏。布局和样式改动实测窄屏、明暗主题与焦点行为。
- 动效复用 `globals.css` 的时长与缓动，主要使用透明度和位移；弹层复用 `motion-panel`/`motion-overlay`，手机导航使用 Radix Dialog。避免动画宽高使主内容反复重排，遵守系统减少动态效果；加载态保留基本布局与可读状态。

## 运行与验收

- 包管理器和完整质量检查遵守根规范；命令清单以 `package.json` 和 README 为准。开发/预览固定 3001；切换到生产预览前先构建并停止占用该端口的本项目开发进程，完成后恢复原开发状态。
- 依赖变更检查 `pnpm-workspace.yaml` 的构建脚本许可及 `patches/`；升级 sonner 时保留或验证后台页面通知计时补丁的等价行为。
- Knip 删除候选还需核实路由、动态导入、运行时注册和测试引用，不用忽略列表掩盖废弃实现。
- 按影响范围验收刷新恢复、切页、重复提交、失败回滚与取消。测试应等待实际异步路径结束，不以未捕获异常被忽略的空跑作为冒烟通过。
- 业务能力或契约变化时更新 [接入参考](docs/api-integration-status.md) 的相关行；验收结果写开发记录，规范只保留长期约束。
