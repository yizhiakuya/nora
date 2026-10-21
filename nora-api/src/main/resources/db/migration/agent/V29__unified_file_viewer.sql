-- 文件交付转为真实文件 + open_file，保留业务卡片和历史画廊兼容。
UPDATE agent_skill SET instructions = '# 产物画廊指南

## 文件交付

报告、代码、CSV 表格和其他成果先用 manage_workspace 等工具写成真实文件，再调用 open_file。
不要把报告正文放进 text 画廊，不再为文件入口输出 files 画廊。open_file 会生成原生文件卡片，用户可查看、下载、引用和编辑工作区文本。

示例工具参数：{"targets":["workspace:reports/分析.md","workspace:reports/明细.csv"],"focus":"workspace:reports/分析.md","intent":"deliver"}。

- targets：1–20 个真实引用，仅接受 workspace:相对路径、file:id、media:缓存键；不接受任意 URL、绝对路径、正文。
- focus：targets 中优先展示的文件；省略时用第一个有效文件。
- intent=deliver：登记工作区成果到已保存成果；intent=inspect：查看已有文件。
- 文件不存在时如实说明并修复，不要伪造文件或保存成功状态。
- open_file 不把文件内容发给模型；读取用 manage_file action=read target=对应引用。
- 最终回答说明结论与文件入口即可，不复述工具参数。

## 业务画廊

相册缩略图、查询表格、差异、时间线、键值信息和结构化列表继续用 nora-artifacts 围栏。
一条围栏一个画廊，gallery 字段指定类型。数据必须来自真实工具结果。

media：title/summary/note 可选，items=[{kind:"image|video",url:"原图或播放地址",thumbUrl:"缩略图",fullUrl:"可选原件",name,caption,meta}]。
媒体缩略图仍在聊天中显示，点击进入统一文件查看器。
table：columns=[{key,label,align:"left|right"}]，rows=[{对应 key:值}]。
diff：items=[{name,before,after,caption,meta}]。
timeline：items=[{name,status:"done|failed|running|pending",meta:"时间",caption}]。
keyvalue：items=[{name:"键",meta:"值",open:"可选深链"}]。
list：items=[{name,caption,meta,open:"可选深链"}]。

text/files 仅保留历史消息解析；新文件交付使用 open_file。
', description = '真实文件交付使用 open_file；相册、表格、差异等业务卡片保留画廊协议', updated_at = now()
WHERE name = '产物画廊指南' AND deleted_at IS NULL;

UPDATE agent_skill SET instructions = instructions || '

## 统一文件查看器
报告、代码、表格先保存为真实文件，再调用 open_file(targets=[workspace:相对路径|file:id|media:缓存键], intent=deliver 或 inspect)。工作区 deliver 自动登记已保存成果；用户可在文件卡片查看、下载和引用到对话。读取内容用 manage_file read，不再用 text/files 画廊交付文件。
', updated_at = now() WHERE name = '工作台使用手册' AND deleted_at IS NULL;
