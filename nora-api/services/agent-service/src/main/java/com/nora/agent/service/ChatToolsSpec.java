package com.nora.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * tools spec 装配(从 ChatOrchestrationService 拆出,2026-09-17 复杂度审计 Step 1):
 * 12 个内置工具的 JSON Schema + MCP 动态挂载工具。
 * 服务为 null 时对应工具不挂载(测试构造器兼容)。
 *
 * <p>2026-09-18 schema 纪律:所有有限取值字段补 {@code enum}(action/kind/shell 等),
 * 让无效值在 schema 层就不可表示——比描述里写警告有效(实测模型仍会传
 * album="全部" 这类字面值,描述警告不敌 enum 约束)。
 */
class ChatToolsSpec {

    private final ObjectMapper objectMapper;
    private final AgentWorkspaceService agentWorkspaceService;
    private final AgentSkillService agentSkillService;
    private final McpServerService mcpServerService;
    private final TerminalService terminalService;
    private final MediaFetchService mediaFetchService;
    private final KnowledgeManageClient knowledgeManageClient;
    private final AutomationManageClient automationManageClient;
    private final EnvironmentStatusClient environmentStatusClient;

    ChatToolsSpec(ObjectMapper objectMapper,
                  AgentWorkspaceService agentWorkspaceService,
                  AgentSkillService agentSkillService,
                  McpServerService mcpServerService,
                  TerminalService terminalService) {
        this(objectMapper, agentWorkspaceService, agentSkillService, mcpServerService, terminalService,
                null, null, null, null);
    }

    ChatToolsSpec(ObjectMapper objectMapper,
                  AgentWorkspaceService agentWorkspaceService,
                  AgentSkillService agentSkillService,
                  McpServerService mcpServerService,
                  TerminalService terminalService,
                  MediaFetchService mediaFetchService) {
        this(objectMapper, agentWorkspaceService, agentSkillService, mcpServerService, terminalService,
                mediaFetchService, null, null, null);
    }

    ChatToolsSpec(ObjectMapper objectMapper,
                  AgentWorkspaceService agentWorkspaceService,
                  AgentSkillService agentSkillService,
                  McpServerService mcpServerService,
                  TerminalService terminalService,
                  MediaFetchService mediaFetchService,
                  KnowledgeManageClient knowledgeManageClient,
                  AutomationManageClient automationManageClient,
                  EnvironmentStatusClient environmentStatusClient) {
        this.objectMapper = objectMapper;
        this.agentWorkspaceService = agentWorkspaceService;
        this.agentSkillService = agentSkillService;
        this.mcpServerService = mcpServerService;
        this.terminalService = terminalService;
        this.mediaFetchService = mediaFetchService;
        this.knowledgeManageClient = knowledgeManageClient;
        this.automationManageClient = automationManageClient;
        this.environmentStatusClient = environmentStatusClient;
    }

    /** 给字符串字段加 enum 约束(有限取值集):无效值在 schema 层不可表示。 */
    private static void setEnum(ObjectNode prop, String... values) {
        ArrayNode en = prop.putArray("enum");
        for (String v : values) {
            en.add(v);
        }
    }

    /**
     * 工具面版本号(MCP 挂载集变化时递增;其余工具运行期不变)。
     * 供 {@link ChatContextAssembler#toolsOverheadTokens()} 的估算缓存失效判断。
     */
    long toolsRevision() {
        return mcpServerService == null ? 0 : mcpServerService.toolsRevision();
    }

    /** OpenAI tools 数组:受控 SQL + 服务日志读取。 */
    ArrayNode build() {
        ArrayNode tools = objectMapper.createArrayNode();

        ObjectNode sqlTool = objectMapper.createObjectNode();
        sqlTool.put("type", "function");
        ObjectNode sqlFn = sqlTool.putObject("function");
        sqlFn.put("name", "execute_sql");
        sqlFn.put("description", "在已连接的数据库上执行只读 SQL 查询并返回真实结果。需要数据库统计、表数据时必须使用此工具。"
                + "限制：仅允许单条 SELECT/SHOW/EXPLAIN 语句；一次调用一条语句；不允许 INSERT/UPDATE/DELETE/DDL；"
                + "结果最多返回 50 行,超长会被截断——请先加 LIMIT 探查再逐步细化。"
                + "Redis 连接(engine=redis)时此处填只读 Redis 命令(如 GET k / HGETALL k / KEYS pattern / SCAN 0 MATCH p / TYPE k / TTL k),写命令会被拒绝。"
                + "示例:{\"sql\": \"SELECT status, COUNT(*) FROM orders GROUP BY status\"}");
        ObjectNode sqlParams = sqlFn.putObject("parameters");
        sqlParams.put("type", "object");
        sqlParams.put("additionalProperties", false);
        ObjectNode sqlProps = sqlParams.putObject("properties");
        ObjectNode sqlProp = sqlProps.putObject("sql");
        sqlProp.put("type", "string");
        sqlProp.put("description", "要执行的只读 SQL 语句,必须是单条完整的 SELECT/SHOW/EXPLAIN,不要带分号以外的多条语句");
        ObjectNode sqlDsProp = sqlProps.putObject("datasource");
        sqlDsProp.put("type", "string");
        sqlDsProp.put("description", "可选:目标数据源的连接名或 id(以 manage_datasource action=list 返回的 name/id 为准,"
                + "不要猜测数据库名);单连接时省略此参数");
        ObjectNode sqlDescProp = sqlProps.putObject("description");
        sqlDescProp.put("type", "string");
        sqlDescProp.put("description", "一句话描述这次调用要做什么,将作为执行时间线的标题展示给用户(5-12 个字,祈使句)。"
                + "正例:「统计各状态订单数」「查最近 50 行日志」;反例:不要出现「复杂」「风险」等主观词,不要复述完整 SQL");
        ArrayNode sqlRequired = sqlParams.putArray("required");
        sqlRequired.add("sql");
        tools.add(sqlTool);

        ObjectNode logTool = objectMapper.createObjectNode();
        logTool.put("type", "function");
        ObjectNode logFn = logTool.putObject("function");
        logFn.put("name", "read_service_logs");
        logFn.put("description", "读取本地 Docker 容器服务的最近日志,用于诊断服务异常/报错。"
                + "限制:service 必须是已注册容器名;最多返回最近 100 行;DEBUG 级别日志会被过滤。"
                + "不确定有哪些服务时,不要凭空猜测容器名。示例:{\"service\": \"nora-redis\", \"limit\": 50}");
        ObjectNode logParams = logFn.putObject("parameters");
        logParams.put("type", "object");
        logParams.put("additionalProperties", false);
        ObjectNode logProps = logParams.putObject("properties");
        ObjectNode serviceProp = logProps.putObject("service");
        serviceProp.put("type", "string");
        serviceProp.put("description", "容器名,如 nora-postgres / nora-redis / nora-nacos");
        ObjectNode limitProp = logProps.putObject("limit");
        limitProp.put("type", "integer");
        limitProp.put("description", "返回的最近日志行数,默认 50,最大 100");
        ObjectNode logDescProp = logProps.putObject("description");
        logDescProp.put("type", "string");
        logDescProp.put("description", "一句话描述这次调用要做什么,将作为执行时间线的标题展示给用户(5-12 个字,祈使句)。"
                + "正例:「诊断 Redis 启动日志」「排查 Postgres 报错」;反例:不要用「查看日志」这类与工具名重复的泛化描述");
        ArrayNode logRequired = logParams.putArray("required");
        logRequired.add("service");
        tools.add(logTool);

        // 写 SQL:仅在用户批准后执行(ASK/ASSIST 档)。描述必须让模型明白这会真的改数据
        ObjectNode writeTool = objectMapper.createObjectNode();
        writeTool.put("type", "function");
        ObjectNode writeFn = writeTool.putObject("function");
        writeFn.put("name", "execute_write_sql");
        writeFn.put("description", "在已连接的数据库上执行一条写语句(INSERT/UPDATE/DELETE/DDL),会真实修改数据。"
                + "仅当用户明确要求修改/删除/新增数据时使用;只读查询必须用 execute_sql。"
                + "限制:一次只允许一条写语句;执行前用户会收到审批请求,未批准则不会执行。"
                + "示例:{\"sql\": \"UPDATE orders SET status='paid' WHERE id=42\"}");
        ObjectNode writeParams = writeFn.putObject("parameters");
        writeParams.put("type", "object");
        writeParams.put("additionalProperties", false);
        ObjectNode writeProps = writeParams.putObject("properties");
        ObjectNode writeSqlProp = writeProps.putObject("sql");
        writeSqlProp.put("type", "string");
        writeSqlProp.put("description", "要执行的单条写 SQL(INSERT/UPDATE/DELETE/DDL),不要带 WHERE 以外的子查询副作用");
        ObjectNode writeDsProp = writeProps.putObject("datasource");
        writeDsProp.put("type", "string");
        writeDsProp.put("description", "可选:目标数据源的名称或 id(不填=默认连接)");
        ObjectNode writeDescProp = writeProps.putObject("description");
        writeDescProp.put("type", "string");
        writeDescProp.put("description", "一句话描述这次写操作的目的,将作为审批卡片和时间线标题展示(5-12 个字,祈使句)。"
                + "正例:「更新订单 42 状态」;反例:不要出现「危险」「风险」等主观词");
        ArrayNode writeRequired = writeParams.putArray("required");
        writeRequired.add("sql");
        tools.add(writeTool);

        // 容器控制:仅在用户批准后执行(ASK/ASSIST 档)
        ObjectNode containerTool = objectMapper.createObjectNode();
        containerTool.put("type", "function");
        ObjectNode containerFn = containerTool.putObject("function");
        containerFn.put("name", "manage_container");
        containerFn.put("description", "启动/停止/重启本地 Docker 容器服务。仅在诊断确认服务异常且需要重启才能恢复时使用;"
                + "只看日志用 read_service_logs。限制:service 必须是已注册容器名;action 只允许 start/stop/restart;"
                + "执行前用户会收到审批请求,未批准则不会执行。示例:{\"service\": \"nora-redis\", \"action\": \"restart\"}");
        ObjectNode containerParams = containerFn.putObject("parameters");
        containerParams.put("type", "object");
        containerParams.put("additionalProperties", false);
        ObjectNode containerProps = containerParams.putObject("properties");
        ObjectNode containerServiceProp = containerProps.putObject("service");
        containerServiceProp.put("type", "string");
        containerServiceProp.put("description", "容器名,如 nora-postgres / nora-redis / nora-nacos");
        ObjectNode containerActionProp = containerProps.putObject("action");
        containerActionProp.put("type", "string");
        containerActionProp.put("description", "要执行的动作:start / stop / restart");
        setEnum(containerActionProp, "start", "stop", "restart");
        ObjectNode containerDescProp = containerProps.putObject("description");
        containerDescProp.put("type", "string");
        containerDescProp.put("description", "一句话描述这次容器操作的目的,将作为审批卡片和时间线标题展示(5-12 个字,祈使句)。"
                + "正例:「重启 Redis 恢复连接」;反例:不要用「操作容器」这类泛化描述");
        ArrayNode containerRequired = containerParams.putArray("required");
        containerRequired.add("service");
        containerRequired.add("action");
        tools.add(containerTool);

        // 数据源管理:list/schema 只读自动;create/test ASSIST+FULL 需批准(见 RiskClassifier);
        // remove CRITICAL——任何档位都要用户确认(级联删历史,不可逆)
        ObjectNode dsTool = objectMapper.createObjectNode();
        dsTool.put("type", "function");
        ObjectNode dsFn = dsTool.putObject("function");
        dsFn.put("name", "manage_datasource");
        dsFn.put("description", "管理数据库连接:list=列出全部连接;create=新增连接(用户提供 host/port/账号密码,"
                + "创建后自动测试连通性);test=测试某连接;schema=查看某连接的表结构(免掉探索性 SQL);"
                + "remove=删除连接(其查询历史一并删除)。用户明确要求添加/删除数据库时使用;"
                + "create 和 remove 在执行前会收到审批请求。示例:{\"action\": \"list\"}");
        ObjectNode dsParams = dsFn.putObject("parameters");
        dsParams.put("type", "object");
        dsParams.put("additionalProperties", false);
        ObjectNode dsProps = dsParams.putObject("properties");
        ObjectNode dsActionProp = dsProps.putObject("action");
        dsActionProp.put("type", "string");
        dsActionProp.put("description", "list / create / test / schema / remove");
        setEnum(dsActionProp, "list", "create", "test", "schema", "remove");
        ObjectNode dsNameProp = dsProps.putObject("name");
        dsNameProp.put("type", "string");
        dsNameProp.put("description", "create 时:连接显示名;其他 action 时省略(用 target 定位)");
        ObjectNode dsTargetProp = dsProps.putObject("target");
        dsTargetProp.put("type", "string");
        dsTargetProp.put("description", "test/schema/remove 时:目标数据源的名称或 id");
        ObjectNode dsEngineProp = dsProps.putObject("engine");
        dsEngineProp.put("type", "string");
        dsEngineProp.put("description", "create 时:postgresql / mysql / redis(redis 的 database 填逻辑库编号 0-15)");
        setEnum(dsEngineProp, "postgresql", "mysql", "redis");
        ObjectNode dsHostProp = dsProps.putObject("host");
        dsHostProp.put("type", "string");
        dsHostProp.put("description", "create 时:数据库主机名或 IP");
        ObjectNode dsPortProp = dsProps.putObject("port");
        dsPortProp.put("type", "integer");
        dsPortProp.put("description", "create 时:端口(如 postgresql 5432 / mysql 3306)");
        ObjectNode dsDbProp = dsProps.putObject("database");
        dsDbProp.put("type", "string");
        dsDbProp.put("description", "create 时:数据库名");
        ObjectNode dsUserProp = dsProps.putObject("username");
        dsUserProp.put("type", "string");
        dsUserProp.put("description", "create 时:用户名(可选)");
        ObjectNode dsPwdProp = dsProps.putObject("password");
        dsPwdProp.put("type", "string");
        dsPwdProp.put("description", "create 时:密码(可选;仅存入数据源服务,不会出现在对话与日志)");
        ObjectNode dsDescProp = dsProps.putObject("description");
        dsDescProp.put("type", "string");
        dsDescProp.put("description", "一句话描述这次操作的目的,将作为审批卡片和时间线标题展示(5-12 个字,祈使句)");
        ArrayNode dsRequired = dsParams.putArray("required");
        dsRequired.add("action");
        tools.add(dsTool);

        // 纳管源管理:list/enable/disable ASSIST 需批准;register/remove CRITICAL——
        // 任何档位都要用户确认(PROC 源的 command 是宿主机命令,注册等于纳入监控+守护)
        ObjectNode svcTool = objectMapper.createObjectNode();
        svcTool.put("type", "function");
        ObjectNode svcFn = svcTool.putObject("function");
        svcFn.put("name", "manage_service");
        svcFn.put("description", "管理环境纳管源(日志/监控注册表):list=列出全部;register=注册新源"
                + "(FILE=日志文件,DOCKER=容器名,PROC=宿主机进程及启动命令);enable/disable=暂停或恢复监控;"
                + "remove=删除注册(不动容器/文件)。注册 PROC 源意味着系统将跟踪其命令并采集日志,"
                + "register 和 remove 执行前会收到审批请求。示例:{\"action\": \"register\", \"kind\": \"PROC\","
                + " \"name\": \"backup-job\", \"command\": \"/opt/scripts/backup.sh\", \"workDir\": \"/opt/scripts\"}");
        ObjectNode svcParams = svcFn.putObject("parameters");
        svcParams.put("type", "object");
        svcParams.put("additionalProperties", false);
        ObjectNode svcProps = svcParams.putObject("properties");
        ObjectNode svcActionProp = svcProps.putObject("action");
        svcActionProp.put("type", "string");
        svcActionProp.put("description", "list / register / enable / disable / remove");
        setEnum(svcActionProp, "list", "register", "enable", "disable", "remove");
        ObjectNode svcKindProp = svcProps.putObject("kind");
        svcKindProp.put("type", "string");
        svcKindProp.put("description", "register 时:FILE / DOCKER / PROC");
        setEnum(svcKindProp, "FILE", "DOCKER", "PROC");
        ObjectNode svcNameProp = svcProps.putObject("name");
        svcNameProp.put("type", "string");
        svcNameProp.put("description", "register 时:纳管源唯一显示名;其他 action 时省略(用 target 定位)");
        ObjectNode svcFileProp = svcProps.putObject("fileLogPath");
        svcFileProp.put("type", "string");
        svcFileProp.put("description", "kind=FILE 时:日志文件绝对路径");
        ObjectNode svcContainerProp = svcProps.putObject("containerName");
        svcContainerProp.put("type", "string");
        svcContainerProp.put("description", "kind=DOCKER 时:容器名");
        ObjectNode svcCmdProp = svcProps.putObject("command");
        svcCmdProp.put("type", "string");
        svcCmdProp.put("description", "kind=PROC 时:启动命令(绝对路径或可执行文件)");
        ObjectNode svcWorkDirProp = svcProps.putObject("workDir");
        svcWorkDirProp.put("type", "string");
        svcWorkDirProp.put("description", "kind=PROC 时:工作目录(可选)");
        ObjectNode svcTargetProp = svcProps.putObject("target");
        svcTargetProp.put("type", "string");
        svcTargetProp.put("description", "enable/disable/remove 时:目标纳管源的名称或 id");
        ObjectNode svcDescProp = svcProps.putObject("description");
        svcDescProp.put("type", "string");
        svcDescProp.put("description", "一句话描述这次操作的目的,将作为审批卡片和时间线标题展示(5-12 个字,祈使句)");
        ArrayNode svcRequired = svcParams.putArray("required");
        svcRequired.add("action");
        tools.add(svcTool);

        // 文件中心管理:读取(list/read)+ 管理(import/rename/move/delete/folders/mkdir)。
        // 2026-09-20 更名(架构设计 §4.2):原名 read_file 与真实能力不符(它能改名/移动/
        // 删除)——统一为 manage_file;read_file 保留为兼容别名(旧历史/旧调用仍路由),
        // 但不再作为工具名暴露给模型。
        ObjectNode fileTool = objectMapper.createObjectNode();
        fileTool.put("type", "function");
        ObjectNode fileFn = fileTool.putObject("function");
        fileFn.put("name", "manage_file");
        fileFn.put("description", "文件中心(用户上传到文件页的文件,**不是工作区/整机文件系统——那是 manage_workspace**)。"
                + "list 列出全部文件(按文件夹分组展示,可看到用户整理的结构);"
                + "带 id 读取某个文件的提取文本(支持文档/PDF/代码等);"
                + "**import 把远程 URL 下载并存成文件中心文件**"
                + "(适合把 MCP 工具返回的图片链接存起来:用户可在「文件」页直接看到);"
                + "**rename 改名 / move 移到文件夹 / delete 移入回收站 / folders 列文件夹 / mkdir 建文件夹**"
                + "(用户说「把上传的 XX 改名/移到 YY/删了/建个文件夹」时用)。"
                + "用户问\"我的文件里/上传的文档里\"这类问题时使用——"
                + "注意 RAG 检索只能召回片段,通读全文用此工具。文件名不能猜,必须先 list 拿到 id。"
                + "示例:{\"action\": \"list\"}、{\"id\": \"3\"}、"
                + "{\"action\": \"rename\", \"id\": \"3\", \"name\": \"2026合同.pdf\"}、"
                + "{\"action\": \"import\", \"url\": \"https://.../photo/123/content?t=xxx\", \"filename\": \"photo-123.jpg\"}");
        ObjectNode fileParams = fileFn.putObject("parameters");
        fileParams.put("type", "object");
        fileParams.put("additionalProperties", false);
        ObjectNode fileProps = fileParams.putObject("properties");
        ObjectNode fileUrlProp = fileProps.putObject("url");
        fileUrlProp.put("type", "string");
        fileUrlProp.put("description", "import 时:要下载并存成文件的 http/https 地址");
        ObjectNode fileFilenameProp = fileProps.putObject("filename");
        fileFilenameProp.put("type", "string");
        fileFilenameProp.put("description", "import 时可选:保存的文件名(不给则从 URL 推断)");
        ObjectNode fileActionProp = fileProps.putObject("action");
        fileActionProp.put("type", "string");
        fileActionProp.put("description", "list(列文件)/ read(读内容)/ import(下载 URL 存成文件)/ "
                + "rename(改名)/ move(移到文件夹)/ delete(移入回收站)/ folders(列文件夹)/ mkdir(建文件夹);"
                + "省略时:有 id 即 read,无 id 即 list");
        setEnum(fileActionProp, "list", "read", "import", "rename", "move", "delete", "folders", "mkdir");
        ObjectNode fileIdProp = fileProps.putObject("id");
        fileIdProp.put("type", "string");
        fileIdProp.put("description", "read/rename/move/delete 时:文件 id(list 结果里的数字 id;"
                + "**read 也接受路径/文件名**(如 MEMORY.md 或 photos/x.jpg 或 @center/报告.pdf——"
                + "会自动路由到文件中心或工作区,无需先查 id);"
                + "move/delete 支持逗号分隔多个,如 \"3,4,5\")");
        ObjectNode fileNameProp = fileProps.putObject("name");
        fileNameProp.put("type", "string");
        fileNameProp.put("description", "rename 时:新文件名(含扩展名);mkdir 时:新文件夹名");
        ObjectNode fileFolderProp = fileProps.putObject("folderId");
        fileFolderProp.put("type", "integer");
        fileFolderProp.put("description", "move 时:目标文件夹 id(folders 动作查看;省略/null=移回根目录)");
        ObjectNode fileDescProp = fileProps.putObject("description");
        fileDescProp.put("type", "string");
        fileDescProp.put("description", "一句话描述这次调用要做什么(5-12 个字,祈使句)");
        ArrayNode fileRequired = fileParams.putArray("required");
        tools.add(fileTool);

        // 工作区文件操作(设计对齐 OpenClaw workspace):agent 的记忆载体是文件,
        // 工作区就是它的家——USER.md/MEMORY.md/memory 日记由它自己维护。
        // 全部动作锁定在工作区内(路径校验),LOW 风险,任何档位自动执行。
        if (agentWorkspaceService != null) {
        ObjectNode wsTool = objectMapper.createObjectNode();
        wsTool.put("type", "function");
        ObjectNode wsFn = wsTool.putObject("function");
        wsFn.put("name", "manage_workspace");
        wsFn.put("description", "文件系统读写(**工作区与整机的文件**,不是文件中心——用户上传的文件用 manage_file)。"
                + "工作区是你的家目录,也是你的长期记忆。"
                + "list 列目录;read 读文件;write 覆盖写入;append 追加;delete 删除;"
                + "mkdir 建目录;"
                + "**edit 对已存在文件做精确文本替换**(改一小段用它,不必 read 全文再 write 全文——"
                + "{\"action\": \"edit\", \"path\": \"MEMORY.md\", \"old_string\": \"原文\", \"new_string\": \"新文\"};"
                + "old_string 必须与文件内容精确一致且唯一出现);"
                + "**move/copy 移动或复制文件与目录**(整理工作区用它,不要借 run_command 的 PowerShell——"
                + "{\"action\": \"move\", \"path\": \"导出目录\", \"to\": \"photos/新目录\"};目标为已存在目录时移入其中);"
                + "**import 把远程 URL 的内容下载并保存到文件系统**"
                + "(如把 MCP 工具返回的图片链接存到工作区:"
                + "{\"action\": \"import\", \"url\": \"https://.../photo/123/content?t=xxx\", \"path\": \"album/photo-123.jpg\"})。"
                + "**相对路径=工作区内**(如 USER.md、memory/2026-09-10.md);"
                + "**绝对路径=整机任意位置**(如 D:/projects/app/src/main.ts、C:/Users/xxx/notes.md),"
                + "可帮用户查看/整理项目文件——写/删工作区外的文件前用户会被要求确认。"
                + "记忆维护约定:稳定偏好→USER.md,耐久事实/决定→MEMORY.md(保持精简,每轮自动注入),"
                + "日常观察/进度→memory/YYYY-MM-DD.md(按需读取)。用户说「记住…」时必须落盘。"
                + "示例:{\"action\": \"read\", \"path\": \"D:/projects/myapp/package.json\"}");
        ObjectNode wsParams = wsFn.putObject("parameters");
        wsParams.put("type", "object");
        wsParams.put("additionalProperties", false);
        ObjectNode wsProps = wsParams.putObject("properties");
        ObjectNode wsUrlProp = wsProps.putObject("url");
        wsUrlProp.put("type", "string");
        wsUrlProp.put("description", "import 时:要下载的 http/https 地址");
        ObjectNode wsFilenameProp = wsProps.putObject("filename");
        wsFilenameProp.put("type", "string");
        wsFilenameProp.put("description", "import 时可选:保存的文件名(不给则从 URL 推断)");
        ObjectNode wsActionProp = wsProps.putObject("action");
        wsActionProp.put("type", "string");
        wsActionProp.put("description", "list / read / write / append / delete / import / move / copy / mkdir / edit"
                + "(import=下载 URL 存成文件,存远程图片等二进制必须用它,write 只写文本;"
                + "move/copy 用于整理文件与目录;edit=对已存在文件做精确文本替换,见 old_string/new_string 参数)");
        setEnum(wsActionProp, "list", "read", "write", "append", "delete", "import", "move", "copy", "mkdir", "edit");
        ObjectNode wsPathProp = wsProps.putObject("path");
        wsPathProp.put("type", "string");
        wsPathProp.put("description", "read/write/append/delete/import 时:相对路径=工作区内(如 USER.md);"
                + "绝对路径=整机(如 D:/projects/x/README.md;写/删前会被要求确认)。"
                + "import 不给 path 时默认存到工作区 imports/ 目录");
        ObjectNode wsOffsetProp = wsProps.putObject("offset");
        wsOffsetProp.put("type", "integer");
        wsOffsetProp.put("description", "read 时可选:起始行号(1-based)。读大文件被截断后,"
                + "用截断提示里的 offset 继续读后续内容,如 {\"action\": \"read\", \"path\": \"big.log\", \"offset\": 501}");
        ObjectNode wsLimitProp = wsProps.putObject("limit");
        wsLimitProp.put("type", "integer");
        wsLimitProp.put("description", "read 时可选:读取行数(默认 500,上限 2000);配合 offset 分段读大文件");
        ObjectNode wsToProp = wsProps.putObject("to");
        wsToProp.put("type", "string");
        wsToProp.put("description", "move/copy 时:目标路径(相对=工作区内)。目标为已存在目录时,源会移入/复制进该目录;"
                + "目标已存在(非目录)时拒绝,不覆盖。**支持通配符**:path 可写 "
                + "\"photos/week-videos/VID_20260916_*.mp4\"(在父目录内匹配,批量移动/复制到 to 目录,一次调用完成)");
        ObjectNode wsDirProp = wsProps.putObject("dir");
        wsDirProp.put("type", "string");
        wsDirProp.put("description", "list 时:目录(相对=工作区内;绝对=整机;省略=工作区根目录)");
        ObjectNode wsContentProp = wsProps.putObject("content");
        wsContentProp.put("type", "string");
        wsContentProp.put("description", "write/append 时:文件内容(write 会覆盖整文件,先 read 再写)");
        ObjectNode wsOldProp = wsProps.putObject("old_string");
        wsOldProp.put("type", "string");
        wsOldProp.put("description", "edit 时:要被替换的原文(必须与文件内容精确一致,含缩进;"
                + "且在文件中唯一出现——多次出现会拒绝,请带足上下文使其唯一)");
        ObjectNode wsNewProp = wsProps.putObject("new_string");
        wsNewProp.put("type", "string");
        wsNewProp.put("description", "edit 时:替换后的新文本(留空字符串=删除该段)。"
                + "写不存在的文件用 write;整文件重写用 write;只改一小段用 edit(不必 read 全文再 write 全文)");
        ObjectNode wsDescProp = wsProps.putObject("description");
        wsDescProp.put("type", "string");
        wsDescProp.put("description", "一句话描述这次操作的目的(5-12 个字,祈使句)");
        ArrayNode wsRequired = wsParams.putArray("required");
        wsRequired.add("action");
        tools.add(wsTool);
        }

        // 批量媒体拉取:把手机相册(或任意 MCP 媒体的)一批文件一次调用下载到
        // 工作区文件夹。**「把相册整理出来/备份到本地」类任务必须用它**——
        // 不要对成百上千个 URL 逐个跑 run_command 下载(实测:407 个文件要 400+
        // 轮调用,且极易触发远端防护)。风险:区内落盘=LOW(与 import 同级)。
        if (mediaFetchService != null && mcpServerService != null) {
        ObjectNode fmTool = objectMapper.createObjectNode();
        fmTool.put("type", "function");
        ObjectNode fmFn = fmTool.putObject("function");
        fmFn.put("name", "fetch_media");
        fmFn.put("description", "批量拉取媒体文件到工作区(从已注册的媒体 MCP 服务器,如手机相册):"
                + "自动调手机 photos_search(urls=original) 拿清单,并发下载到指定文件夹,链路自动选优(在家走局域网)。"
                + "**「把最近一个月的相册整理出来」「把这批照片存到工作区」这类批量任务必须用本工具一次完成**——"
                + "不要用 run_command 逐个 URL 下载(几百个文件要几百轮,且远端防护可能封 IP)。"
                + "文件落在工作区指定 folder 下(用户可在「文件」页的「Agent 工作区」里浏览);已存在的文件自动跳过,可安全重跑续传。"
                + "示例:{\"server\": \"phone\", \"from\": \"2026-08-17\", \"to\": \"2026-09-17\", \"folder\": \"photos/2026-08\", \"quality\": \"high\"}");
        ObjectNode fmParams = fmFn.putObject("parameters");
        fmParams.put("type", "object");
        fmParams.put("additionalProperties", false);
        ObjectNode fmProps = fmParams.putObject("properties");
        ObjectNode fmServerProp = fmProps.putObject("server");
        fmServerProp.put("type", "string");
        fmServerProp.put("description", "媒体 MCP 服务器名(默认 phone;以 manage_mcp list 的名称或 mcp__<名>__ 前缀为准)");
        ObjectNode fmFromProp = fmProps.putObject("from");
        fmFromProp.put("type", "string");
        fmFromProp.put("description", "起始时间(ISO 8601,如 2026-08-17),含;不传=不限");
        ObjectNode fmToProp = fmProps.putObject("to");
        fmToProp.put("type", "string");
        fmToProp.put("description", "结束时间(ISO 8601),含。**传纯日期(如 2026-09-16)= 含当天一整天**(不是当天 0 点);不传=不限");
        ObjectNode fmAlbumProp = fmProps.putObject("album");
        fmAlbumProp.put("type", "string");
        fmAlbumProp.put("description", "相册名(以 albums_list 返回的真实名称为准,如 Camera/Screenshots);不传=全部相册。"
                + "**不要传「全部」「all」这类字面值**——会被当成不存在的相册名导致 0 条");
        ObjectNode fmTypeProp = fmProps.putObject("type");
        fmTypeProp.put("type", "string");
        fmTypeProp.put("description", "photo / video / all(默认 all)");
        setEnum(fmTypeProp, "photo", "video", "all");
        ObjectNode fmFolderProp = fmProps.putObject("folder");
        fmFolderProp.put("type", "string");
        fmFolderProp.put("description", "工作区目标文件夹(相对路径,相对工作区根,如 photos/2026-09-16-show);"
                + "媒体归档**建议放 photos/ 下**(先 list photos 看已有目录避免重复);不传=imports/");
        ObjectNode fmQualityProp = fmProps.putObject("quality");
        fmQualityProp.put("type", "string");
        fmQualityProp.put("description", "high=图片取原片(归档推荐);不传=手机按网络自动出图。视频始终原片");
        setEnum(fmQualityProp, "high");
        ObjectNode fmDescProp = fmProps.putObject("description");
        fmDescProp.put("type", "string");
        fmDescProp.put("description", "一句话描述这次操作的目的(5-12 个字,祈使句)");
        tools.add(fmTool);
        }

        // 技能管理:目录已在系统提示注入,read 拉全文遵循;create/update 让 agent
        // 能把用户教的方法沉淀为可复用技能(闭环自管)。
        if (agentSkillService != null) {
        ObjectNode skillTool = objectMapper.createObjectNode();
        skillTool.put("type", "function");
        ObjectNode skillFn = skillTool.putObject("function");
        skillFn.put("name", "manage_skill");
        skillFn.put("description", "管理用户的技能库(可复用的任务指令)。系统提示里列出了启用技能目录;"
                + "任务与某技能相关时用 action=read 读它的完整指令并严格遵循;"
                + "用户教你一套方法并希望以后沿用(\"把刚才的流程存成技能\")时用 action=create 沉淀;"
                + "list 查看全部技能;update 修改;remove 删除。"
                + "示例:{\"action\": \"read\", \"target\": \"周报生成\"}");
        ObjectNode skillParams = skillFn.putObject("parameters");
        skillParams.put("type", "object");
        skillParams.put("additionalProperties", false);
        ObjectNode skillProps = skillParams.putObject("properties");
        ObjectNode skillActionProp = skillProps.putObject("action");
        skillActionProp.put("type", "string");
        skillActionProp.put("description", "list / read / create / update / remove");
        setEnum(skillActionProp, "list", "read", "create", "update", "remove");
        ObjectNode skillTargetProp = skillProps.putObject("target");
        skillTargetProp.put("type", "string");
        skillTargetProp.put("description", "read/update/remove 时:技能名称或数字 id");
        ObjectNode skillNameProp = skillProps.putObject("name");
        skillNameProp.put("type", "string");
        skillNameProp.put("description", "create/update 时:技能名称");
        ObjectNode skillDescProp = skillProps.putObject("description");
        skillDescProp.put("type", "string");
        skillDescProp.put("description", "create/update 时:技能的一句话说明(何时该用这个技能)");
        ObjectNode skillInstrProp = skillProps.putObject("instructions");
        skillInstrProp.put("type", "string");
        skillInstrProp.put("description", "create/update 时:技能正文(Markdown,完整可执行的指令步骤)");
        ObjectNode skillCategoryProp = skillProps.putObject("category");
        skillCategoryProp.put("type", "string");
        skillCategoryProp.put("description", "create/update 时:分类(可选,默认「自定义」)");
        ObjectNode skillEnabledProp = skillProps.putObject("enabled");
        skillEnabledProp.put("type", "boolean");
        skillEnabledProp.put("description", "update 时:启用(true)/停用(false)该技能");
        ArrayNode skillRequired = skillParams.putArray("required");
        skillRequired.add("action");
        tools.add(skillTool);
        }

        // 知识库主动检索:自动注入召回不佳时的二次检索通道(换关键词/调 topK 重查)
        if (knowledgeManageClient != null) {
        ObjectNode kbSearchTool = objectMapper.createObjectNode();
        kbSearchTool.put("type", "function");
        ObjectNode kbSearchFn = kbSearchTool.putObject("function");
        kbSearchFn.put("name", "search_knowledge");
        kbSearchFn.put("description", "主动检索知识库(与每轮自动注入同一检索通道):"
                + "当自动注入的片段不够、或需要换关键词/换角度重查时使用——"
                + "如用户追问「再找找有没有提到 X 的」、或你要核实某个事实在知识库中的出处。"
                + "只读,不改变知识库。示例:{\"query\": \"部署流程 端口配置\", \"topK\": 8}");
        ObjectNode kbSearchParams = kbSearchFn.putObject("parameters");
        kbSearchParams.put("type", "object");
        kbSearchParams.put("additionalProperties", false);
        ObjectNode kbSearchProps = kbSearchParams.putObject("properties");
        ObjectNode kbQueryProp = kbSearchProps.putObject("query");
        kbQueryProp.put("type", "string");
        kbQueryProp.put("description", "检索查询(自然语言或关键词;用具体名词与术语,不要用「那个文档」这类指代)");
        ObjectNode kbTopKProp = kbSearchProps.putObject("topK");
        kbTopKProp.put("type", "integer");
        kbTopKProp.put("description", "返回块数(默认 8,最大 20)");
        ObjectNode kbSearchDescProp = kbSearchProps.putObject("description");
        kbSearchDescProp.put("type", "string");
        kbSearchDescProp.put("description", "一句话描述这次检索要做什么(5-12 个字,祈使句)");
        ArrayNode kbSearchRequired = kbSearchParams.putArray("required");
        kbSearchRequired.add("query");
        tools.add(kbSearchTool);

        // 知识库管理:list/index/remove/reindex/stats(索引与删除按风险分级走审批)
        ObjectNode kbTool = objectMapper.createObjectNode();
        kbTool.put("type", "function");
        ObjectNode kbFn = kbTool.putObject("function");
        kbFn.put("name", "manage_knowledge");
        kbFn.put("description", "管理知识库文档:list=列出全部文档(可用 filter 按名过滤);"
                + "index=把文件中心文件索引进知识库(参数 fileId,先 manage_file list 拿 id);"
                + "remove=删除文档及其分块(不动文件中心原文件);reindex=重建文档向量(嵌入模型变更后刷新);"
                + "stats=索引统计(文档/块数/模型)。"
                + "用户说「把这份文档加进知识库/删掉那篇旧文档/知识库多大」时使用。"
                + "示例:{\"action\": \"index\", \"fileId\": \"12\"}");
        ObjectNode kbParams = kbFn.putObject("parameters");
        kbParams.put("type", "object");
        kbParams.put("additionalProperties", false);
        ObjectNode kbProps = kbParams.putObject("properties");
        ObjectNode kbActionProp = kbProps.putObject("action");
        kbActionProp.put("type", "string");
        kbActionProp.put("description", "list / index / remove / reindex / stats");
        setEnum(kbActionProp, "list", "index", "remove", "reindex", "stats");
        ObjectNode kbFilterProp = kbProps.putObject("filter");
        kbFilterProp.put("type", "string");
        kbFilterProp.put("description", "list 时可选:按文档名包含的子串过滤(如「周报」)");
        ObjectNode kbFileIdProp = kbProps.putObject("fileId");
        kbFileIdProp.put("type", "string");
        kbFileIdProp.put("description", "index 时:文件中心文件 id(先 manage_file action=list 拿 id)");
        ObjectNode kbNameProp = kbProps.putObject("name");
        kbNameProp.put("type", "string");
        kbNameProp.put("description", "index 时可选:知识库展示名(默认取文件名)");
        ObjectNode kbTargetProp = kbProps.putObject("target");
        kbTargetProp.put("type", "string");
        kbTargetProp.put("description", "remove/reindex 时:文档 id(list 结果里的数字 id)");
        ObjectNode kbDescProp = kbProps.putObject("description");
        kbDescProp.put("type", "string");
        kbDescProp.put("description", "一句话描述这次操作的目的(5-12 个字,祈使句)");
        ArrayNode kbRequired = kbParams.putArray("required");
        kbRequired.add("action");
        tools.add(kbTool);
        }

        // 自动任务管理:定时任务的完整生命周期(list/create/toggle/remove/run/executions)
        if (automationManageClient != null) {
        ObjectNode autoTool = objectMapper.createObjectNode();
        autoTool.put("type", "function");
        ObjectNode autoFn = autoTool.putObject("function");
        autoFn.put("name", "manage_automation");
        autoFn.put("description", "管理自动任务(定时执行的规则):"
                + "list=列出全部规则(名称/触发方式/启停/上次运行);"
                + "create=新建规则(参数 name + prompt 自然语言指令 + triggerType 触发方式;"
                + "prompt 由无人值守通道执行——到点自动跑,没有审批门,写 prompt 时把动作写清楚);"
                + "toggle=启用/暂停;remove=删除(执行历史保留);run=立即运行一次;executions=查看执行历史。"
                + "用户说「每天帮我查一次 X/定时做 Y/建个自动任务」时用 create。"
                + "示例:{\"action\": \"create\", \"name\": \"每日订单巡检\", \"triggerType\": \"daily\","
                + " \"prompt\": \"查询今天的订单总量与异常状态订单,输出简短摘要\"}");
        ObjectNode autoParams = autoFn.putObject("parameters");
        autoParams.put("type", "object");
        autoParams.put("additionalProperties", false);
        ObjectNode autoProps = autoParams.putObject("properties");
        ObjectNode autoActionProp = autoProps.putObject("action");
        autoActionProp.put("type", "string");
        autoActionProp.put("description", "list / create / toggle / remove / run / executions");
        setEnum(autoActionProp, "list", "create", "toggle", "remove", "run", "executions");
        ObjectNode autoNameProp = autoProps.putObject("name");
        autoNameProp.put("type", "string");
        autoNameProp.put("description", "create 时:规则名(简短描述性,如「每日订单巡检」)");
        ObjectNode autoPromptProp = autoProps.putObject("prompt");
        autoPromptProp.put("type", "string");
        autoPromptProp.put("description", "create 时:自然语言指令(未来无人值守轮次执行;写清楚要做什么、输出什么,"
                + "因为执行时你不在场、没有人可以追问)");
        ObjectNode autoTriggerProp = autoProps.putObject("triggerType");
        autoTriggerProp.put("type", "string");
        autoTriggerProp.put("description", "create 时:触发方式(daily=每日一次 / weekly=每周一次 / manual=仅手动)");
        setEnum(autoTriggerProp, "daily", "weekly", "manual");
        ObjectNode autoTargetProp = autoProps.putObject("target");
        autoTargetProp.put("type", "string");
        autoTargetProp.put("description", "toggle/remove/run 时:规则 id(list 结果里的数字 id)");
        ObjectNode autoLimitProp = autoProps.putObject("limit");
        autoLimitProp.put("type", "integer");
        autoLimitProp.put("description", "executions 时:返回条数(默认 20,最大 200)");
        ObjectNode autoDescProp = autoProps.putObject("description");
        autoDescProp.put("type", "string");
        autoDescProp.put("description", "一句话描述这次操作的目的(5-12 个字,祈使句)");
        ArrayNode autoRequired = autoParams.putArray("required");
        autoRequired.add("action");
        tools.add(autoTool);
        }

        // 环境健康快照:一次拿到全部纳管源状态(诊断入口;对齐「先看面板再翻日志」的工作流)
        if (environmentStatusClient != null) {
        ObjectNode envTool = objectMapper.createObjectNode();
        envTool.put("type", "function");
        ObjectNode envFn = envTool.putObject("function");
        envFn.put("name", "environment_status");
        envFn.put("description", "获取环境健康快照:全部启用纳管源(DOCKER 容器/FILE 日志源/PROC 进程)"
                + "的状态、健康度、CPU/内存、运行时长一次看全。"
                + "用户问「现在系统/服务状态怎么样」或你要做故障诊断时先看这个,再对异常源用 read_service_logs 深入。"
                + "示例:{\"description\": \"查看服务状态\"}");
        ObjectNode envParams = envFn.putObject("parameters");
        envParams.put("type", "object");
        envParams.put("additionalProperties", false);
        ObjectNode envProps = envParams.putObject("properties");
        ObjectNode envDescProp = envProps.putObject("description");
        envDescProp.put("type", "string");
        envDescProp.put("description", "一句话描述这次调用的目的(5-12 个字,祈使句)");
        tools.add(envTool);
        }

        // MCP 服务器管理:agent 可自管远程工具服务器(注册/启停/刷新/删除),
        // 与设置页 /api/mcp/servers 共用服务层。风险跟随全局权限档位
        // (register/remove 等 = HIGH:ASK 全问 / ASSIST 询问 / FULL 自动);
        // headers/env 值不落对话记录。
        if (mcpServerService != null) {
        ObjectNode mcpTool = objectMapper.createObjectNode();
        mcpTool.put("type", "function");
        ObjectNode mcpFn = mcpTool.putObject("function");
        mcpFn.put("name", "manage_mcp");
        mcpFn.put("description", "管理 MCP(Model Context Protocol)工具服务器(远程或本地进程):"
                + "list=列出已注册服务器(名称/状态/工具数);"
                + "refresh=测试连接并拉取工具清单(拉取成功后其工具挂载为 mcp__<服务器名>__<工具名>,你即可调用);"
                + "enable/disable=启用或停用;register=注册新服务器;remove=删除注册;"
                + "tools=查看某服务器的工具清单(读缓存快照,不触发远端;lazy 服务器的工具从这里发现;"
                + "**加 tool=<工具名> 参数则返回该工具的完整参数 schema**——调用前先读它,按 schema 构造 arguments,不要猜);"
                + "call=按名调用工具(参数 target=服务器、tool=工具名、arguments=参数对象/JSON 字符串;"
                + "lazy 服务器用它调用,eager 服务器等价于挂载调用);"
                + "setPolicy=设置工具加载策略(target + toolPolicy=eager/lazy;lazy=不挂载为独立工具、"
                + "经 tools/call 按需使用,省每轮上下文)。"
                + "register 两种形态:①远程——提供 url(可选 transport=STREAMABLE/SSE);"
                + "②本地进程(STDIO)——提供 command 与 args,如 command=npx, args=[\"-y\",\"@modelcontextprotocol/server-filesystem\",\"D:/docs\"],"
                + "或 Docker 方式 command=docker, args=[\"run\",\"-i\",\"--rm\",\"镜像名\"];本地方式需本机已装对应运行时。"
                + "按当前权限档位,高风险动作可能要求用户批准。"
                + "用户说「把 XX MCP 服务器接上/注册一下」时使用。示例:{\"action\": \"register\", "
                + "\"name\": \"weather\", \"url\": \"https://mcp.example.com/mcp\"}");
        ObjectNode mcpParams = mcpFn.putObject("parameters");
        mcpParams.put("type", "object");
        mcpParams.put("additionalProperties", false);
        ObjectNode mcpProps = mcpParams.putObject("properties");
        ObjectNode mcpActionProp = mcpProps.putObject("action");
        mcpActionProp.put("type", "string");
        mcpActionProp.put("description", "list / refresh / enable / disable / register / remove / tools / call / setPolicy");
        setEnum(mcpActionProp, "list", "refresh", "enable", "disable", "register", "remove", "tools", "call", "setPolicy");
        ObjectNode mcpToolNameProp = mcpProps.putObject("tool");
        mcpToolNameProp.put("type", "string");
        mcpToolNameProp.put("description", "call 时:要调用的工具名(以 action=tools 清单为准,不要猜测)");
        ObjectNode mcpArgumentsProp = mcpProps.putObject("arguments");
        mcpArgumentsProp.put("type", "object");
        mcpArgumentsProp.put("description", "call 时:工具参数对象(如 {\"query\": \"x\"};无参数省略)");
        ObjectNode mcpPolicyProp = mcpProps.putObject("toolPolicy");
        mcpPolicyProp.put("type", "string");
        mcpPolicyProp.put("description", "setPolicy 时:eager=工具直接挂载(默认)/ lazy=按需(不占每轮上下文,tools/call 使用)");
        setEnum(mcpPolicyProp, "eager", "lazy");
        ObjectNode mcpNameProp = mcpProps.putObject("name");
        mcpNameProp.put("type", "string");
        mcpNameProp.put("description", "register 时:服务器名(只含字母/数字/下划线/连字符,不能含连续下划线;会成为挂载工具名前缀)");
        ObjectNode mcpUrlProp = mcpProps.putObject("url");
        mcpUrlProp.put("type", "string");
        mcpUrlProp.put("description", "register 远程服务器时:MCP 服务器地址(http(s):// 开头);本地 STDIO 时省略");
        ObjectNode mcpTransportProp = mcpProps.putObject("transport");
        mcpTransportProp.put("type", "string");
        mcpTransportProp.put("description", "register 时:STREAMABLE(远程默认)/ SSE(远程)/ STDIO(本地进程)");
        setEnum(mcpTransportProp, "STREAMABLE", "SSE", "STDIO");
        ObjectNode mcpCommandProp = mcpProps.putObject("command");
        mcpCommandProp.put("type", "string");
        mcpCommandProp.put("description", "register STDIO 时:可执行命令(npx / node / docker / uvx ...;需本机已安装)");
        ObjectNode mcpArgsProp = mcpProps.putObject("args");
        mcpArgsProp.put("type", "array");
        mcpArgsProp.putObject("items").put("type", "string");
        mcpArgsProp.put("description", "register STDIO 时:命令参数数组,如 [\"-y\", \"@scope/server\"]");
        ObjectNode mcpHeadersProp = mcpProps.putObject("headers");
        mcpHeadersProp.put("type", "object");
        mcpHeadersProp.put("description", "register 时:鉴权头(如 {\"Authorization\": \"Bearer xxx\"}),可省略;值不会出现在对话记录中");
        ObjectNode mcpEnvProp = mcpProps.putObject("env");
        mcpEnvProp.put("type", "object");
        mcpEnvProp.put("description", "register STDIO 时:追加环境变量(如 {\"API_KEY\": \"xxx\"}),可省略;值不会出现在对话记录中");
        ObjectNode mcpTargetProp = mcpProps.putObject("target");
        mcpTargetProp.put("type", "string");
        mcpTargetProp.put("description", "refresh/enable/disable/remove 时:目标服务器的名称或 id(以 list 结果为准,不要猜测)");
        ObjectNode mcpDescProp = mcpProps.putObject("description");
        mcpDescProp.put("type", "string");
        mcpDescProp.put("description", "一句话描述这次操作的目的,将作为审批卡片和时间线标题展示(5-12 个字,祈使句)");
        ArrayNode mcpRequired = mcpParams.putArray("required");
        mcpRequired.add("action");
        tools.add(mcpTool);
        }

        // 本机终端:非交互命令执行(构建/测试/git/包管理等);默认 cwd=工作区。
        // 风险 HIGH(跟随全局档位):ASK 全问 / ASSIST 询问 / FULL 自动。
        // terminalService 为 null = 测试便捷构造器,跳过挂载
        if (terminalService != null) {
        ObjectNode cmdTool = objectMapper.createObjectNode();
        cmdTool.put("type", "function");
        ObjectNode cmdFn = cmdTool.putObject("function");
        cmdFn.put("name", "run_command");
        cmdFn.put("description", "在本机终端运行一条非交互命令(构建/测试/git/npm/pip/查进程等)。"
                + "工作目录默认是你的工作区;相对 cwd 相对工作区解析,绝对 cwd 可指向整机任意目录。"
                + "Windows 默认 PowerShell 7(Nora 内置,版本确定),也可显式 shell=bash(git bash)。"
                + "注意:命令无 TTY——不要运行交互式程序(vim/需要输入确认的),会挂起到超时;"
                + "长时间命令(构建/下载)显式传更大的 timeout(秒,上限 300);"
                + "运行前用户会按权限档位收到审批请求。示例:{\"command\": \"npm test\", \"cwd\": \"D:/projects/app\"}");
        ObjectNode cmdParams = cmdFn.putObject("parameters");
        cmdParams.put("type", "object");
        cmdParams.put("additionalProperties", false);
        ObjectNode cmdProps = cmdParams.putObject("properties");
        ObjectNode cmdCommandProp = cmdProps.putObject("command");
        cmdCommandProp.put("type", "string");
        cmdCommandProp.put("description", "要执行的命令(单条,非交互;支持管道/重定向)");
        ObjectNode cmdCwdProp = cmdProps.putObject("cwd");
        cmdCwdProp.put("type", "string");
        cmdCwdProp.put("description", "工作目录(可选):相对=工作区内(如 my-project);绝对=整机(如 D:/projects/app);省略=工作区根");
        ObjectNode cmdTimeoutProp = cmdProps.putObject("timeout");
        cmdTimeoutProp.put("type", "integer");
        cmdTimeoutProp.put("description", "超时秒数(可选,默认 60,上限 300);构建/安装类给 120-300");
        ObjectNode cmdShellProp = cmdProps.putObject("shell");
        cmdShellProp.put("type", "string");
        cmdShellProp.put("description", "powershell(默认,Nora 内置 pwsh 7)或 bash(需要 git bash);省略=平台默认");
        setEnum(cmdShellProp, "powershell", "bash");
        ObjectNode cmdDescProp = cmdProps.putObject("description");
        cmdDescProp.put("type", "string");
        cmdDescProp.put("description", "一句话描述这次命令要做什么,将作为审批卡片和时间线标题展示(5-12 个字,祈使句)。"
                + "正例:「运行单元测试」「查看 git 状态」;反例:不要用「执行命令」这类泛化描述");
        ArrayNode cmdRequired = cmdParams.putArray("required");
        cmdRequired.add("command");
        tools.add(cmdTool);
        }

        // MCP 挂载工具:已启用且完成过 refresh(有 tools_cache)的远程服务器的
        // 工具,命名 mcp__<server>__<tool>;schema/描述来自远端 snapshot。
        // mcpServerService 为 null = 测试便捷构造器,跳过挂载
        if (mcpServerService != null) {
            for (McpServerService.MountedTool mounted : mcpServerService.mountedTools()) {
                ObjectNode mTool = objectMapper.createObjectNode();
                mTool.put("type", "function");
                ObjectNode mFn = mTool.putObject("function");
                mFn.put("name", mounted.mountedName());
                String desc = mounted.description() == null || mounted.description().isBlank()
                        ? "MCP 工具(" + mounted.serverName() + " 提供)" : mounted.description();
                mFn.put("description", desc);
                ObjectNode mParams = mFn.putObject("parameters");
                if (mounted.inputSchema() != null && mounted.inputSchema().isObject()) {
                    mParams.setAll((ObjectNode) mounted.inputSchema());
                } else {
                    mParams.put("type", "object");
                    mParams.putObject("properties");
                }
                tools.add(mTool);
            }
        }

        return tools;
    }
}
