package com.nora.agent.service;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import dev.langchain4j.model.output.structured.Description;

/** 方法的请求类型也是模型参数声明的唯一来源。 */
final class ToolInputs {
    private ToolInputs() { }

    public enum PhotoAlbumAction { list, read, create, update, feedback }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PhotoAlbumPhotosItem(
            @Description("真实 workspace:相对路径 / file:id / media:缓存键；手机照片先 fetch_media") String target,
            @JsonProperty(required = false) @Description("真实拍摄日期 YYYY-MM-DD，无可靠元数据就省略") String date,
            @JsonProperty(required = false) @Description("实际看图后的描述，不推测年龄或健康状态") String caption) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PhotoAlbumCaptionsItem(
            @Description("read 清单中已有的照片 hash") String hash,
            String caption) { }

    public enum PhotoAlbumStyleTheme { paper, night }

    public enum PhotoAlbumStyleLayout { timeline, grid }

    public enum PhotoAlbumStyleCaptionTone { plain, warm }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PhotoAlbumStyle(
            @JsonProperty(required = false) PhotoAlbumStyleTheme theme,
            @JsonProperty(required = false) PhotoAlbumStyleLayout layout,
            @JsonProperty(required = false) PhotoAlbumStyleCaptionTone captionTone) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PhotoAlbum(
            PhotoAlbumAction action,
            @JsonProperty(required = false) @Description("固定相册标识，读取和更新沿用同一值") String album,
            @JsonProperty(required = false) @Description("update/feedback 必填，来自最新 read") Long revision,
            @JsonProperty(required = false) String title,
            @JsonProperty(required = false) String summary,
            @JsonProperty(required = false) List<PhotoAlbumPhotosItem> photos,
            @JsonProperty(required = false) @Description("改写已选照片的文案；用于用户纠正语气后即时更新") List<PhotoAlbumCaptionsItem> captions,
            @JsonProperty(required = false) PhotoAlbumStyle style,
            @JsonProperty(required = false) @Description("feedback 动作必填，忠实记录用户明确说过的要求") String feedback,
            @JsonProperty(required = false) @Description("仅 feedback 可用；用户明确要求今后沿用时设 true") Boolean remember) { }

    public enum OpenFileIntent { inspect, deliver }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record OpenFile(
            List<String> targets,
            @JsonProperty(required = false) @Description("可选，必须属于 targets") String focus,
            @JsonProperty(required = false) OpenFileIntent intent) { }

    public enum DatasourceAction { list, create, test, schema, remove }

    public enum DatasourceEngine { postgresql, mysql, redis }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Datasource(
            @Description("list / create / test / schema / remove") DatasourceAction action,
            @JsonProperty(required = false) @Description("create 时:连接显示名;其他 action 时省略(用 target 定位)") String name,
            @JsonProperty(required = false) @Description("test/schema/remove 时:目标数据源的名称或 id") String target,
            @JsonProperty(required = false) @Description("create 时:postgresql / mysql / redis(redis 的 database 填逻辑库编号 0-15)") DatasourceEngine engine,
            @JsonProperty(required = false) @Description("create 时:数据库主机名或 IP") String host,
            @JsonProperty(required = false) @Description("create 时:端口(如 postgresql 5432 / mysql 3306)") Integer port,
            @JsonProperty(required = false) @Description("create 时:数据库名") String database,
            @JsonProperty(required = false) @Description("create 时:用户名(可选)") String username,
            @JsonProperty(required = false) @Description("create 时:密码(可选;仅存入数据源服务,不会出现在对话与日志)") String password) { }

    public record LoadTools(@Description("本次需要的工具名称数组，使用目录中的准确名称") List<String> names) { }

    public enum ServiceAction { list, status, logs, start, stop, restart, register, enable, disable, remove }

    public enum ServiceKind { FILE, DOCKER, PROC }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Service(
            @Description("list 列注册项；status 实时状态；logs 最近日志；start/stop/restart 控制服务；register 注册；enable/disable 控制监控；remove 移除注册") ServiceAction action,
            @JsonProperty(required = false) @Description("register 时:FILE / DOCKER / PROC") ServiceKind kind,
            @JsonProperty(required = false) @Description("register 时:纳管源唯一显示名;其他 action 时省略(用 target 定位)") String name,
            @JsonProperty(required = false) @Description("kind=FILE 时:日志文件绝对路径") String fileLogPath,
            @JsonProperty(required = false) @Description("kind=DOCKER 时:容器名") String containerName,
            @JsonProperty(required = false) @Description("kind=PROC 时:启动命令(绝对路径或可执行文件)") String command,
            @JsonProperty(required = false) @Description("kind=PROC 时:工作目录(可选)") String workDir,
            @JsonProperty(required = false) @Description("logs/start/stop/restart/enable/disable/remove 必填：list 返回的纳管源名称或 id") String target,
            @JsonProperty(required = false) @Description("logs 返回的最近行数，默认 50，最大 100") Integer limit) { }


    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record File(
            @JsonProperty(required = false) @Description("import 时:要下载并存成文件的 http/https 地址") String url,
            @JsonProperty(required = false) @Description("import 时可选:保存的文件名(不给则从 URL 推断)") String filename,
            @JsonProperty(required = false) @Description("list / read / import / rename / move / delete / folders / mkdir") String action,
            @JsonProperty(required = false) @Description("文件 ID 或路径字符串；move/delete 的多个数字 ID 以逗号分隔") String id,
            @JsonProperty(required = false) @Description("rename 时:新文件名(含扩展名);mkdir 时:新文件夹名") String name,
            @JsonProperty(required = false) @Description("move 时:目标文件夹 id(folders 动作查看;省略/null=移回根目录)") Long folderId) { }


    public enum WorkspaceUnit { lines, chars }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Workspace(
            @JsonProperty(required = false) @Description("import 时:要下载的 http/https 地址") String url,
            @JsonProperty(required = false) @Description("import 时可选:保存的文件名(不给则从 URL 推断)") String filename,
            @Description("list / read / write / append / delete / import / move / copy / mkdir / edit") String action,
            @JsonProperty(required = false) @Description("read/write/append/delete/import 时:相对路径=工作区内(如 USER.md);绝对路径=当前执行环境可见的文件系统(受权限约束)。import 不给 path 时默认存到工作区 imports/ 目录") String path,
            @JsonProperty(required = false) @Description("read 时可选:从 1 开始的起始位置;unit=lines 表示行号,unit=chars 表示字符序号。按回读提示的 unit/offset 续读") Integer offset,
            @JsonProperty(required = false) @Description("read 时可选:读取数量;lines 默认 500/上限 2000 行,chars 默认/上限 6000 字符") Integer limit,
            @JsonProperty(required = false) @Description("read 分页单位:默认 lines;单行 JSON 或完整工具记录使用 chars,无需 shell/Python 解码") WorkspaceUnit unit,
            @JsonProperty(required = false) @Description("move/copy 时:目标路径(相对=工作区内)。目标为已存在目录时,源会移入/复制进该目录;目标已存在(非目录)时拒绝,不覆盖。**支持通配符**:path 可写 \"photos/week-videos/VID_20260916_*.mp4\"(在父目录内匹配,批量移动/复制到 to 目录,一次调用完成)") String to,
            @JsonProperty(required = false) @Description("list 时:目录(相对=工作区内;绝对=整机;省略=工作区根目录)") String dir,
            @JsonProperty(required = false) @Description("write/append 时:文件内容(write 会覆盖整文件,先 read 再写)") String content,
            @JsonProperty(required = false) @Description("edit 时:要被替换的原文(文字、空格和缩进必须精确一致;兼容 LF/CRLF 换行;且在文件中唯一出现——多次出现会拒绝,请带足上下文使其唯一)") String old_string,
            @JsonProperty(required = false) @Description("edit 时:替换后的新文本(留空字符串=删除该段)。写不存在的文件用 write;整文件重写用 write;只改一小段用 edit(不必 read 全文再 write 全文)") String new_string,
            @JsonProperty(required = false) @Description("list 的目录范围") String target) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MediaItemsItem(
            String url,
            String filename) { }

    public enum MediaType { photo, video, all }

    public enum MediaQuality { high }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Media(
            @JsonProperty(required = false) @Description("只下载已经选中的真实URL，优先来自 photos_search(urls=original)。与 album/from/to/all 互斥") List<MediaItemsItem> items,
            @JsonProperty(required = false) @Description("仅用户明确要求整库备份时设 true；没有范围参数时必填") Boolean all,
            @JsonProperty(required = false) @Description("媒体 MCP 服务器名(默认 phone;以 manage_mcp list 的名称或 mcp__<名>__ 前缀为准)") String server,
            @JsonProperty(required = false) @Description("起始时间(ISO 8601,如 2026-08-17),含;不传=不限") String from,
            @JsonProperty(required = false) @Description("结束时间(ISO 8601),含。**传纯日期(如 2026-09-16)= 含当天一整天**(不是当天 0 点);不传=不限") String to,
            @JsonProperty(required = false) @Description("相册名(以 albums_list 返回的真实名称为准,如 Camera/Screenshots);不传=全部相册。**不要传「全部」「all」这类字面值**——会被当成不存在的相册名导致 0 条") String album,
            @JsonProperty(required = false) @Description("photo / video / all(默认 all)") MediaType type,
            @JsonProperty(required = false) @Description("工作区目标文件夹(相对路径,相对工作区根,如 photos/2026-09-16-show);媒体归档**建议放 photos/ 下**(先 list photos 看已有目录避免重复);不传=imports/") String folder,
            @JsonProperty(required = false) @Description("high=图片取原片(归档推荐);不传=手机按网络自动出图。视频始终原片") MediaQuality quality) { }

    public enum SkillAction { list, read, create, update, remove }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Skill(
            @Description("list / read / create / update / remove") SkillAction action,
            @JsonProperty(required = false) @Description("read/update/remove 时:技能名称或数字 id") String target,
            @JsonProperty(required = false) @Description("create/update 时:技能名称") String name,
            @JsonProperty(required = false) @Description("create/update 时:技能的一句话说明(何时该用这个技能)") String description,
            @JsonProperty(required = false) @Description("create/update 时:技能正文(Markdown,完整可执行的指令步骤)") String instructions,
            @JsonProperty(required = false) @Description("create/update 时:分类(可选,默认「自定义」)") String category,
            @JsonProperty(required = false) @Description("update 时:启用(true)/停用(false)该技能") Boolean enabled) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchKnowledge(
            @Description("检索查询(自然语言或关键词;用具体名词与术语,不要用「那个文档」这类指代)") String query,
            @JsonProperty(required = false) @Description("返回块数(默认 8,最大 20)") Integer topK,
            @JsonProperty(required = false) @Description("可选:限定在某个资料库内检索(manage_knowledge action=bases 查 id)") Long baseId,
            @JsonProperty(required = false) @Description("可选:限定在这批文档 id 内检索(manage_knowledge action=list 查 id)") List<Long> docIds,
            @JsonProperty(required = false) @Description("可选的知识来源范围") List<String> sources) { }

    public enum KnowledgeAction { list, bases, index, remove, reindex, disable, enable, stats }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Knowledge(
            @Description("list / bases / index / remove / reindex / disable / enable / stats") KnowledgeAction action,
            @JsonProperty(required = false) @Description("list 时可选:按文档名包含的子串过滤(如「周报」)") String filter,
            @JsonProperty(required = false) @Description("index 时:文件中心文件 id(先 manage_file action=list 拿 id)") String fileId,
            @JsonProperty(required = false) @Description("index 时可选:知识库展示名(默认取文件名)") String name,
            @JsonProperty(required = false) @Description("remove/reindex 时:文档 id(list 结果里的数字 id)") String target) { }

    public enum AutomationAction { list, create, toggle, remove, run, executions }

    public enum AutomationTriggerType { daily, weekly, manual }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Automation(
            @Description("list / create / toggle / remove / run / executions") AutomationAction action,
            @JsonProperty(required = false) @Description("create 时:规则名(简短描述性,如「每日订单巡检」)") String name,
            @JsonProperty(required = false) @Description("create 时:自然语言指令(未来无人值守轮次执行;写清楚要做什么、输出什么,因为执行时你不在场、没有人可以追问)") String prompt,
            @JsonProperty(required = false) @Description("create 时:触发方式(daily=每日一次 / weekly=每周一次 / manual=仅手动)") AutomationTriggerType triggerType,
            @JsonProperty(required = false) @Description("create 且 triggerType=daily/weekly 时必填:执行时刻 HH:mm(24 小时制,如 09:00)") String localTime,
            @JsonProperty(required = false) @Description("create 且 triggerType=weekly 时必填:星期几(1=周一 … 7=周日)") Integer dayOfWeek,
            @JsonProperty(required = false) @Description("create 时可选:IANA 时区(如 Asia/Shanghai / America/New_York);省略默认 Asia/Shanghai") String timezone,
            @JsonProperty(required = false) @Description("toggle/remove/run 时:规则 id(list 结果里的数字 id)") String target,
            @JsonProperty(required = false) @Description("executions 时:返回条数(默认 20,最大 200)") Integer limit) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Question(
            @Description("要问用户的问题原文(具体、可回答,避免开放式的「你想要什么」)") String question,
            @JsonProperty(required = false) @Description("可选项(可选;给 2-5 个候选项让用户点选,同时用户仍可自由输入)") List<String> options,
            @JsonProperty(required = false) @Description("等待上限秒数(可选,默认 300,上限 600);超时后你会收到「未回答」提示,应基于合理假设继续") Integer timeoutSeconds) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Command(
            @Description("要执行的命令(单条,非交互;支持管道/重定向)") String command,
            @JsonProperty(required = false) @Description("工作目录(可选):相对路径相对工作区解析;绝对路径必须符合当前系统;省略=工作区根") String cwd,
            @JsonProperty(required = false) @Description("进程总时限秒数(正整数，省略遵循 Agent 设置，初始默认 1800；不是工具等待时长)") Integer timeout,
            @JsonProperty(required = false) @Description("等待工具返回的毫秒数，默认 3000，范围 0–30000；到期转后台，进程继续") Integer yieldMs,
            @JsonProperty(required = false) @Description("true 立即返回后台任务 ID，默认 false") Boolean background,
            @JsonProperty(required = false) @Description("一句话描述这次调用要做什么,将作为执行时间线的标题展示给用户(5-12 个字,祈使句)。正例:「统计各状态订单数」「重启 Redis 恢复连接」;反例:不要用「执行操作」这类泛化词,不要复述完整参数") String description) { }

    public enum CommandExecutionAction { wait, stop }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CommandExecution(
            CommandExecutionAction action,
            String executionId,
            @JsonProperty(required = false) @Description("等待工具返回的毫秒数，默认 3000，范围 0–30000；到期转后台，进程继续") Integer yieldMs) { }

    public enum EnvVarsAction { list, set, delete }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EnvVars(
            EnvVarsAction action,
            @JsonProperty(required = false) @Description("set/delete 必填：变量名，如 TAVILY_API_KEY；自动转大写") String key,
            @JsonProperty(required = false) @Description("set 必填：完整原值；参数记录隐藏此字段") String value,
            @JsonProperty(required = false) @Description("默认 true，查询和结果隐藏原值；普通非敏感变量可显式 false") Boolean secret,
            @JsonProperty(required = false) @Description("用途备注，可省略；空字符串清除备注，不填密钥原文") String note) { }

    public enum BrowserAction { state, open, snapshot, inspect, click, fill, key, scroll, back, forward, reload, select_tab, close_tab, close, plan, extract, export, verify, learn, rows }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BrowserFieldsItem(
            String name,
            @JsonProperty(required = false) @Description("相对于行的子元素选择器；行本身就是所需元素时省略，不能重复 rowsSelector") String selector,
            @JsonProperty(required = false) @Description("仅用于 href/src/datetime 等 HTML 属性；读取正文必须省略，禁止填 text/innerText/textContent") String attribute) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Browser(
            BrowserAction action,
            @JsonProperty(required = false) @Description("open 可选的 HTTP/HTTPS 网页地址；省略时打开浏览器并保留当前页面") String url,
            @JsonProperty(required = false) Boolean newTab,
            @JsonProperty(required = false) String tabId,
            @JsonProperty(required = false) @Description("最新 snapshot 中的元素 ref，例如 e12") String ref,
            @JsonProperty(required = false) @Description("标准 Playwright selector，避免歧义；inspect 可查看多个匹配") String selector,
            @JsonProperty(required = false) String role,
            @JsonProperty(required = false) @Description("role 的准确可访问名称；learn 的新技能名称") String name,
            @JsonProperty(required = false) @Description("fill 的输入内容") String text,
            @JsonProperty(required = false) @Description("例如 Enter、Control+a、Backspace") String key,
            @JsonProperty(required = false) Double dx,
            @JsonProperty(required = false) Double dy,
            @JsonProperty(required = false) @Description("plan 的具体任务与适用范围") String task,
            @JsonProperty(required = false) @Description("plan 的起始网址；已有表单计划时沿用其 planId，不再另建") String sourceUrl,
            @JsonProperty(required = false) @Description("plan 是否先查看样本。true 时 extract 服务端至多采三条，随后结束本轮等待用户；模型不能确认样本") Boolean previewFirst,
            @JsonProperty(required = false) @Description("plan 是否要求结果无完全重复的资料行") Boolean uniqueRows,
            @JsonProperty(required = false) @Description("plan 冻结的必需字段名称及顺序；不含 source_url/captured_at") List<String> columns,
            @JsonProperty(required = false) Integer minRows,
            @JsonProperty(required = false) String planId,
            @JsonProperty(required = false) @Description("extract 必填的重复行选择器；每个匹配生成一行") String rowsSelector,
            @JsonProperty(required = false) @Description("字段 name 和顺序必须与 plan.columns 一致；读取正文时省略 attribute") List<BrowserFieldsItem> fields,
            @JsonProperty(required = false) @Description("extract 最多采集行数；按用户要求设置，样本阶段由服务端另行限制") Integer limit,
            @JsonProperty(required = false) @Description("export 必填：extract 返回的采集批次 id（UUID），不是 rows 返回的行 id；最多20批。选择行另用 rowIds") List<String> captureIds,
            @JsonProperty(required = false) @Description("export 可保留 rows 返回的真实行 ID 子集；不能填自造数据。用户已排除的内容仍会过滤") List<String> rowIds,
            @JsonProperty(required = false) @Description("rows 的分页起点") Integer offset,
            @JsonProperty(required = false) @Description("普通 .csv 文件名，固定保存工作区，不传路径") String filename,
            @JsonProperty(required = false) String receiptId,
            @JsonProperty(required = false) String skillDescription,
            @JsonProperty(required = false) @Description("learn 的具体可复用方法：输入参数、定位、失败处理与重新验收") String instructions) { }

    public enum McpAction { list, get, refresh, enable, disable, register, update, remove, tools, call, setPolicy }

    public enum McpToolPolicy { eager, lazy }

    public enum McpTransport { STREAMABLE, SSE, STDIO }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Mcp(
            @Description("list / get / refresh / enable / disable / register / update / remove / tools / call / setPolicy") McpAction action,
            @JsonProperty(required = false) @Description("call 时:要调用的工具名(以 action=tools 清单为准,不要猜测)") String tool,
            @JsonProperty(required = false) @Description("call 时:工具参数对象(如 {\"query\": \"x\"};无参数省略)") Map<String, Object> arguments,
            @JsonProperty(required = false) @Description("setPolicy 时:eager=工具直接挂载(默认)/ lazy=按需(不占每轮上下文,tools/call 使用)") McpToolPolicy toolPolicy,
            @JsonProperty(required = false) @Description("register 时:服务器名(只含字母/数字/下划线/连字符,不能含连续下划线;会成为挂载工具名前缀)") String name,
            @JsonProperty(required = false) @Description("register/update 远程服务器时:MCP 地址(http(s):// 开头)，支持 ${VAR} 引用；STDIO 时省略") String url,
            @JsonProperty(required = false) @Description("register 时:STREAMABLE(远程默认)/ SSE(远程)/ STDIO(本地进程)") McpTransport transport,
            @JsonProperty(required = false) @Description("register STDIO 时:可执行命令(npx / node / docker / uvx ...;需本机已安装)") String command,
            @JsonProperty(required = false) @Description("register STDIO 时:命令参数数组,如 [\"-y\", \"@scope/server\"]") List<String> args,
            @JsonProperty(required = false) @Description("register/update 时:鉴权头，优先引用变量，如 {\"Authorization\": \"Bearer ${MCP_TOKEN}\"}；update 省略保留，{} 清除") Map<String, String> headers,
            @JsonProperty(required = false) @Description("register STDIO 时:追加环境变量(如 {\"API_KEY\": \"xxx\"}),可省略;值不会出现在对话记录中") Map<String, String> env,
            @JsonProperty(required = false) @Description("get/update/refresh/enable/disable/remove/tools/call/setPolicy 时:目标服务器名称或 id(以 list 结果为准)") String target) { }
}
