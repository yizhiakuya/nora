-- 手机相册整理能力(2026-09-20,App 0.3.27):photos_manage 写操作上线,
-- 手册同步工具清单与常见任务(移动/复制/重命名/删除回收站/恢复)。
-- 删除语义:delete 进 App 回收站可恢复;trash_purge 才是彻底删除(仅回收站内)。
UPDATE agent_skill SET
    instructions = '# 工作台使用手册

Nora 是一个**自托管的个人工作台**:管理面板 + 可执行操作的 AI Agent。
下面九个功能面既是用户在界面上操作的对象,也是你(agent)通过工具操作的对象。

## 0. 权限与安全(先讲这个)
- **权限三档**(用户在对话框选择,随每条消息提交):
  - `ASK` 每次询问:任何有副作用的操作都要用户批准
  - `ASSIST` 高风险询问(默认):只读自动,写操作询问
  - `FULL` 全自动:常规操作直接执行;**CRITICAL 级(删数据源、注册/删除纳管源)任何档位都强制审批**
- **风险分级**:只读=LOW(自动);写 SQL/容器启停/数据源增删/纳管源变更/命令执行=HIGH;删除类=CRITICAL
- 用户看到的**审批卡**会展示完整操作原文(命令/ SQL 原文),你不需要在回复里复述
- 你在**自动任务通道**(无人值守)里执行时没有审批门:HIGH 按设计放行,CRITICAL 直接拒绝

## 1. 对话(chat)
- 用户在对话框发消息,你实时流式回答;支持「停止生成」「编辑重发」「跨轮工具链」
- 你有**工具**:execute_sql(只读 SQL)、execute_write_sql(写 SQL,需批准)、read_service_logs(容器日志)、manage_container(容器启停,需批准)、manage_datasource、manage_service、manage_file(文件中心:用户上传文件)、manage_workspace(工作区文件读写)、manage_skill、manage_mcp、run_command(本机终端命令)、fetch_media(批量媒体拉取——把手机相册等 MCP 媒体一批下载到工作区)、mcp__phone__photos_manage(整理手机相册:移动/复制/重命名/删除到回收站/恢复)、environment_status(环境健康快照)、search_knowledge(知识库主动检索)、manage_knowledge(知识库 list/index/remove/reindex/stats)、manage_automation(自动任务 list/create/toggle/remove/run/executions)
- 用户问「你能做什么」→ 读本手册回答,不要凭记忆编

## 2. 知识库(knowledge)
- RAG 知识库:用户上传文档 → 切分 → 向量化 → 检索召回;回答中引用来源用 `[[文档名]]` 标记
- 用户问「我的文档里…」:先用检索(RAG 自动召回);召回不够时用 `search_knowledge` 换关键词主动重查;要通读全文用 `manage_file`(先 list 拿 id)
- 文档管理:列表、删除、重建索引在知识库页;你也可以用 `manage_knowledge`(index=把文件加进知识库/remove=删文档/reindex=重建向量/stats=统计)直接操作

## 3. 数据源(data-sources)
- 管理数据库连接(PostgreSQL 等):增删连接、测试连通性、查看表结构、查询控制台
- 你操作:`manage_datasource`(list/create/test/schema/remove)+ `execute_sql`(查询)/`execute_write_sql`(写入)
- create/remove 需审批;查询结果最多 50 行(先 LIMIT 探查)

## 4. 环境控制台(environments)
- 本机 Docker 容器与进程的监控面板:服务状态、日志查看、启停/重启
- 纳管源(managed source)三种:FILE(日志文件)/DOCKER(容器)/PROC(宿主机进程+启动命令)
- 你操作:`environment_status`(健康快照:全部纳管源状态一眼看全)、`read_service_logs`(读容器日志)、`manage_container`(启停/重启容器,需批准)、`manage_service`(纳管源注册/启停,register/remove 为 CRITICAL)
- 诊断流程:先 `environment_status` 看谁异常 → 对异常源读日志定位问题 → 确认需要重启才用 manage_container → 重启后验证

## 5. 文件(files)
- **文件中心是 Nora 的统一文件系统入口**:所有文件相关的东西都在这里汇聚——
  用户上传的文件、Agent 工作区、媒体缓存,都是文件系统里的"文件夹",一个视图管理
- 用户上传的文件:可预览、重命名、移动到文件夹、下载(批量打 zip)、删除、加入知识库(索引);**这些你也能操作**:`manage_file` 的 rename/move/delete/folders/mkdir 动作(用户说「把上传的 XX 改名/移到 YY/删了/建个文件夹」时直接用,不用让用户去 UI)
- 文件夹:新建/重命名/删除(删除后文件回根目录,不删文件);上传时可选择目标文件夹
- 「Agent 工作区」:你的文件目录(默认 `D:/claude/Nora/agent-workspace`),SOUL/AGENTS/USER/MEMORY.md 常驻其中,你通过 `manage_workspace` 读写(含 `edit` 精确替换:改一小段不必 read 全文再 write 全文)
- 「媒体缓存」:相册等远程媒体的本地副本(查看秒开、手机离线可看);可删除/清空,也可「保存到文件中心」转为正式文件(可索引/可引用)。相册查询用 photos_search(urls=preview 浏览/original 原片直链);批量归档用 fetch_media
- 工具映射:`manage_file`(文件中心:list/read/import + rename/move/delete/folders/mkdir,先 list 拿 id)、`manage_workspace`(工作区与整机文件:list/read/write/append/delete/edit/import/move/copy/mkdir)
- **整理文件用 manage_workspace 的 move/copy/mkdir**(支持通配符批量,如 `VID_20260916_*.mp4` 一次移动),不要借 run_command 的 PowerShell
- **批量拉取媒体**:`fetch_media` 一次调用完成「把相册一批文件存到工作区」——自动拿清单+并发下载+链路自动选优(在家走局域网)。**不要用 run_command 逐个 URL 下载**(几百个文件要几百轮,且远端防护可能封 IP;实测踩过 407 个文件整轮失败)
- **整理手机相册**(移动/复制/重命名/删除):用 `mcp__phone__photos_manage`——move 移到相册 / copy 复制 / rename 重命名 / delete 移入回收站(可恢复) / trash_list 查看 / trash_restore 恢复 / trash_purge 彻底删除(仅回收站内,不可恢复)。**delete 不是销毁**(进 App 回收站可恢复);用户说「彻底删掉」时才 trash_purge。手机端需开「相册整理」开关 + 「所有文件访问」权限,未开时工具会返回明确提示

## 6. 自动任务(automations)
- 定时/触发式任务:用户配置 prompt + 计划,到点自动执行(无人值守通道)
- 执行记录可在自动任务页查看;执行走 `/api/chat/agent/run`(无会话=无审批;create 的 prompt 将在无人值守通道执行,写清楚动作与范围)
- 你操作:`manage_automation`(list/create/toggle/remove/run/executions)——用户说「建个每天 9 点的任务」时直接 create(参数:name + prompt + triggerType),不需要让用户去 UI 建

## 7. MCP(mcp)
- 接入外部 MCP 工具服务器(远程 url 或本地进程 STDIO),扩展你的工具面
- 已注册服务器:megumin-ssh(SSH 到本机服务器)、github(官方远程端点,44 工具)等
- 你操作:`manage_mcp`(list/refresh/enable/disable/register/remove/tools/call/setPolicy,风险跟随全局档)——**延迟加载(P2-9)**:服务器的工具可设为 lazy(不挂载、经 `action=tools` 查清单 + `action=call` 按名调用,省每轮上下文;设置页 MCP 卡片可切换);lazy 服务器的工具不在挂载名里,发现工具用 tools、调用用 call
- eager 服务器注册后工具挂载为 `mcp__<服务器名>__<工具名>`,刷新后即可调用;lazy 服务器工具按需经 `manage_mcp action=tools/call` 使用
- GitHub OAuth 一键登录在 MCP 页(设备码流程,无需手动建 token)

## 8. 技能(skills)
- 指令型技能库:可复用的任务指令(名称+描述+正文),启用后目录注入你的系统提示,任务相关时你 read 全文遵循
- 你操作:`manage_skill`(list/read/create/update/remove)
- **本手册就是一个技能**:用户问工作台功能时读它;用户教你一套可复用方法时用 create 沉淀

## 9. 设置(settings)
- 模型管理:LLM 服务商(端点/API Key/模型列表)、推理等级配置
- 知识库与 AI:embedding 相关配置
- 环境变量、代理设置(外网访问走代理)
- 这些是用户在界面上配置的;你不需要操作,但排障时要知道去哪看

## 常见任务怎么做(操作指引)
| 用户诉求 | 你怎么做 |
|---|---|
| 「库里有多少订单」 | execute_sql 查真实数据(先 manage_datasource list 确认连接) |
| 「服务怎么挂了」 | read_service_logs 读日志 → 分析 → 需要时 manage_container 重启(先征求同意) |
| 「帮我看下这个项目的代码」 | manage_workspace 读文件(绝对路径=整机) |
| 「把这个流程存下来」 | manage_skill create 沉淀为技能 |
| 「接个 XX MCP」 | manage_mcp register(远程给 url;本地给 command+args) |
| 「跑一下测试」 | run_command(工作目录用 cwd 参数,构建类命令给足 timeout) |
| 「记住我喜欢 X」 | manage_workspace 写 USER.md |
| 「把相册里最近一个月的整理出来/存到工作区」 | fetch_media(server=phone + from/to + folder,一次调用批量下载;不要逐个下载) |
| 「把手机里某批照片归到 XX 相册/删掉/改名」 | mcp__phone__photos_manage(move/copy/rename/delete;delete 进回收站可恢复) |
| 「工作台能干嘛」 | 读本手册,按功能面回答 |

',
    updated_at = now()
WHERE name = '工作台使用手册';
