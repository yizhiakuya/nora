package com.nora.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * tools spec 装配(从 ChatOrchestrationService 拆出,2026-09-17 复杂度审计 Step 1):
 * 11 个内置工具的 JSON Schema + MCP 动态挂载工具。
 * 服务为 null 时对应工具不挂载(测试构造器兼容)。
 */
class ChatToolsSpec {

    private final ObjectMapper objectMapper;
    private final AgentWorkspaceService agentWorkspaceService;
    private final AgentSkillService agentSkillService;
    private final McpServerService mcpServerService;
    private final TerminalService terminalService;

    ChatToolsSpec(ObjectMapper objectMapper,
                  AgentWorkspaceService agentWorkspaceService,
                  AgentSkillService agentSkillService,
                  McpServerService mcpServerService,
                  TerminalService terminalService) {
        this.objectMapper = objectMapper;
        this.agentWorkspaceService = agentWorkspaceService;
        this.agentSkillService = agentSkillService;
        this.mcpServerService = mcpServerService;
        this.terminalService = terminalService;
    }

    /** OpenAI tools array: guarded SQL + service log reading. */
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
        ObjectNode dsNameProp = dsProps.putObject("name");
        dsNameProp.put("type", "string");
        dsNameProp.put("description", "create 时:连接显示名;其他 action 时省略(用 target 定位)");
        ObjectNode dsTargetProp = dsProps.putObject("target");
        dsTargetProp.put("type", "string");
        dsTargetProp.put("description", "test/schema/remove 时:目标数据源的名称或 id");
        ObjectNode dsEngineProp = dsProps.putObject("engine");
        dsEngineProp.put("type", "string");
        dsEngineProp.put("description", "create 时:postgresql / mysql / redis(redis 的 database 填逻辑库编号 0-15)");
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
        ObjectNode svcKindProp = svcProps.putObject("kind");
        svcKindProp.put("type", "string");
        svcKindProp.put("description", "register 时:FILE / DOCKER / PROC");
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

        // 文件读取:纯只读(LOW),无审批;文件名猜不准,先 list 再按 id 读
        ObjectNode fileTool = objectMapper.createObjectNode();
        fileTool.put("type", "function");
        ObjectNode fileFn = fileTool.putObject("function");
        fileFn.put("name", "read_file");
        fileFn.put("description", "工作台文件管理。"
                + "list 列出全部文件;带 id 读取某个文件的提取文本(支持文档/PDF/代码等);"
                + "**import 把远程 URL 下载并存成工作台文件**"
                + "(适合把 MCP 工具返回的图片链接存起来:用户可在「文件」页直接看到)。"
                + "用户问\"我的文件里/上传的文档里\"这类问题时使用——"
                + "注意 RAG 检索只能召回片段,通读全文用此工具。文件名不能猜,必须先 list 拿到 id。"
                + "示例:{\"action\": \"list\"}、{\"id\": \"3\"}、"
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
        fileActionProp.put("description", "list(列文件)/ read(读内容)/ import(下载 URL 存成文件);省略时:有 id 即 read,无 id 即 list");
        ObjectNode fileIdProp = fileProps.putObject("id");
        fileIdProp.put("type", "string");
        fileIdProp.put("description", "read 时:文件 id(list 结果里的数字 id,非文件名)");
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
        wsFn.put("description", "文件系统读写(工作区是你的家目录,也是你的长期记忆)。"
                + "list 列目录;read 读文件;write 覆盖写入;append 追加;delete 删除;"
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
        wsActionProp.put("description", "list / read / write / append / delete / import(下载 URL 存成文件;"
                + "存远程图片等二进制必须用 import,write 只写文本)");
        ObjectNode wsPathProp = wsProps.putObject("path");
        wsPathProp.put("type", "string");
        wsPathProp.put("description", "read/write/append/delete/import 时:相对路径=工作区内(如 USER.md);"
                + "绝对路径=整机(如 D:/projects/x/README.md;写/删前会被要求确认)。"
                + "import 不给 path 时默认存到工作区 imports/ 目录");
        ObjectNode wsDirProp = wsProps.putObject("dir");
        wsDirProp.put("type", "string");
        wsDirProp.put("description", "list 时:目录(相对=工作区内;绝对=整机;省略=工作区根目录)");
        ObjectNode wsContentProp = wsProps.putObject("content");
        wsContentProp.put("type", "string");
        wsContentProp.put("description", "write/append 时:文件内容(write 会覆盖整文件,先 read 再写)");
        ObjectNode wsDescProp = wsProps.putObject("description");
        wsDescProp.put("type", "string");
        wsDescProp.put("description", "一句话描述这次操作的目的(5-12 个字,祈使句)");
        ArrayNode wsRequired = wsParams.putArray("required");
        wsRequired.add("action");
        tools.add(wsTool);
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
                + "enable/disable=启用或停用;register=注册新服务器;remove=删除注册。"
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
        mcpActionProp.put("description", "list / refresh / enable / disable / register / remove");
        ObjectNode mcpNameProp = mcpProps.putObject("name");
        mcpNameProp.put("type", "string");
        mcpNameProp.put("description", "register 时:服务器名(只含字母/数字/下划线/连字符,不能含连续下划线;会成为挂载工具名前缀)");
        ObjectNode mcpUrlProp = mcpProps.putObject("url");
        mcpUrlProp.put("type", "string");
        mcpUrlProp.put("description", "register 远程服务器时:MCP 服务器地址(http(s):// 开头);本地 STDIO 时省略");
        ObjectNode mcpTransportProp = mcpProps.putObject("transport");
        mcpTransportProp.put("type", "string");
        mcpTransportProp.put("description", "register 时:STREAMABLE(远程默认)/ SSE(远程)/ STDIO(本地进程)");
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
