package com.nora.agent.service;

import static com.nora.agent.service.ChatToolExecutor.bounded;

import java.time.Duration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.agent.dto.ChatStepDto;
import com.nora.agent.service.ChatToolExecutor.LiveOutput;
import com.nora.agent.service.ChatToolExecutor.ToolOutcome;

/** 文件中心、工作区与查看器的工具执行。 */
class FileWorkspaceTools {
    private static final Logger log = LoggerFactory.getLogger(FileWorkspaceTools.class);
    private final ObjectMapper objectMapper;
    private final FileToolClient fileToolClient;
    private final AgentWorkspaceService agentWorkspaceService;
    private final RelayMediaRouter relayMediaRouter;
    private ViewerService viewerService;

    FileWorkspaceTools(ObjectMapper objectMapper, FileToolClient fileToolClient,
            AgentWorkspaceService agentWorkspaceService, RelayMediaRouter relayMediaRouter) {
        this.objectMapper = objectMapper;
        this.fileToolClient = fileToolClient;
        this.agentWorkspaceService = agentWorkspaceService;
        this.relayMediaRouter = relayMediaRouter;
    }

    void setViewerService(ViewerService viewerService) { this.viewerService = viewerService; }

    /** read_file handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execReadFile(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
        String action = parsed.datasourceAction() == null ? "list" : parsed.datasourceAction().trim().toLowerCase();
        // action 白名单(2026-09-18):非空且未知的 action 报错而不是静默 fallback 到
        // read/list——静默 fallback 会把「模型写错 action」变成「奇怪的成功」,
        // 掩盖问题且误导后续轮次。省略 action 时保留兼容:有 id 即 read,无 id 即 list。
        if (parsed.datasourceAction() != null && !parsed.datasourceAction().isBlank()
                && !java.util.Set.of("list", "read", "import", "rename", "move", "delete", "folders", "mkdir")
                .contains(action)) {
            return new ToolOutcome("ERROR: 拒绝执行「" + action + "」：action 只允许 "
                    + "list / read / import / rename / move / delete / folders / mkdir"
                    + "(文件中心;工作区/整机文件用 manage_workspace)", null, null, false);
        }
        // import: 把远程 URL 下载并存成工作台文件(用户可见可管理)
        if ("import".equals(action)) {
            String r = importToWorkbenchFile(args);
            return r.startsWith("ERROR:") ? new ToolOutcome(r, null, null, false)
                    : new ToolOutcome(r, r, null, false);
        }
        // 文件中心管理面(2026-09-18 复查补齐):rename/move/delete/folders/mkdir——
        // 用户说「把上传的合同改名/移到文件夹/删掉/建个文件夹」时用这些,
        // 不必让用户去 UI 操作
        if ("rename".equals(action) || "move".equals(action) || "delete".equals(action)
                || "folders".equals(action) || "mkdir".equals(action)) {
            return execFileManage(action, args);
        }
        if ("list".equals(action) || parsed.input().target() == null) {
            // 无 id = 列出文件让模型挑;显式 action=list 同理
            return bounded(fileToolClient.list(), null);
        }
        String target = parsed.input().target();
        if (!target.matches("\\d+")) {
            // 路径路由(2026-09-18 文件工具设计分析):模型常把「读文件」的心智
            // 模型合并——对文件中心写工作区路径(id="MEMORY.md")或对工作区文件
            // 用 read_file。这里把「非数字 target」路由到正确的通道,而不是报错:
            //   @center/名 或纯文件名 → 先按名查文件中心,命中即读文件中心
            //   其余(含 / 的路径、工作区文件) → 转 manage_workspace.readAny
            return routeReadByPath(target);
        }
        return readCenterFile(Long.parseLong(target));
    }

    private ToolOutcome readCenterFile(long fileId) {
        FileToolClient.PreviewInfo info = fileToolClient.previewInfo(fileId);
        if (info.failed()) {
            return bounded("ERROR: " + info.error(), null);
        }
        if (info.hasText()) {
            return bounded(fileToolClient.renderPreview(info), null);
        }
        // 无文本=二进制/图片:图片走图像通道(视觉模型直接看图;
        // 非视觉模型由 backfillToolMessage 明确告知看不到,不静默丢弃)
        JsonNode meta = fileToolClient.meta(fileId);
        String mime = meta == null ? null : meta.path("mimeType").asText(null);
        if (mime != null && mime.startsWith("image/")) {
            FileToolClient.RawFile raw = fileToolClient.raw(fileId);
            if (raw != null && raw.bytes().length > 0) {
                String b64 = java.util.Base64.getEncoder().encodeToString(raw.bytes());
                String desc = "图片文件 " + info.name() + "(" + FileToolClient.formatSize(raw.bytes().length)
                        + ", " + mime + "),原始字节已作为图像附件返回;直接描述你看到的内容";
                return new ToolOutcome(desc, "图片", null, false,
                        List.of(new McpServerService.McpToolResult.ImageBlock(mime, b64)));
            }
        }
        return bounded("文件 " + info.name() + " 没有可提取的文本内容(可能是二进制/图片)", null);
    }

    /**
     * read_file 非数字 target 的路径路由(2026-09-18 文件工具设计分析):
     * 模型对「读文件」有单一心智模型,但 Nora 有两个寻址域(文件中心=数字 id,
     * 工作区/整机=路径)。与其报错教学,不如按形态路由到正确通道:
     *
     * <ul>
     *   <li>{@code @center/名} → 明确走文件中心按名查找;</li>
     *   <li>纯文件名(无路径分隔符)→ 先试文件中心按名查(命中即读);未命中
     *       转工作区(模型多半想读 MEMORY.md 这类工作区文件);</li>
     *   <li>含 {@code /} 或绝对路径 → 直接转 manage_workspace.readAny。</li>
     * </ul>
     *
     * 图片文件同样走图像通道(复用 execManageWorkspace 的 read 分支能力)。
     */
    private ToolOutcome routeReadByPath(String target) {
        String t = target.trim();
        if (t.startsWith("workspace:") || t.startsWith("file:") || t.startsWith("media:")) {
            try {
                if (viewerService == null) throw new IllegalArgumentException("文件查看服务不可用");
                var file = viewerService.resolveOne(t);
                if (file.target().startsWith("file:")) return readCenterFile(Long.parseLong(file.target().substring(5)));
                if (file.previewKind().equals("image")) {
                    var raw = viewerService.image(file.target());
                    return new ToolOutcome("图片 " + file.name() + " 已作为图像附件返回", "图片", null, false,
                            List.of(new McpServerService.McpToolResult.ImageBlock(file.mimeType(), java.util.Base64.getEncoder().encodeToString(raw.bytes()))));
                }
                if (file.capabilities().source()) return bounded(viewerService.text(file.target()).content(), null);
                return bounded("文件 " + file.name() + " 不支持内容提取，可使用 open_file 展示给用户查看", null);
            } catch (java.io.IOException | IllegalArgumentException | org.springframework.web.client.RestClientException e) {
                return new ToolOutcome("ERROR: 文件暂时无法读取或格式不支持，请核对引用", null, null, false);
            }
        }
        boolean explicitCenter = t.startsWith("@center/");
        boolean hasSeparator = t.contains("/") || t.contains("\\");
        // 1) 显式 @center/ 或纯文件名:先试文件中心
        if (explicitCenter || !hasSeparator) {
            Long fileId = fileToolClient.findIdByName(t);
            if (fileId != null) {
                FileToolClient.PreviewInfo info = fileToolClient.previewInfo(fileId);
                if (!info.failed()) {
                    if (info.hasText()) {
                        return bounded("(文件中心 id=" + fileId + ")\n" + fileToolClient.renderPreview(info), null);
                    }
                    // 无文本:复用图像通道逻辑(与数字 id 路径一致)
                    JsonNode meta = fileToolClient.meta(fileId);
                    String mime = meta == null ? null : meta.path("mimeType").asText(null);
                    if (mime != null && mime.startsWith("image/")) {
                        FileToolClient.RawFile raw = fileToolClient.raw(fileId);
                        if (raw != null && raw.bytes().length > 0) {
                            String b64 = java.util.Base64.getEncoder().encodeToString(raw.bytes());
                            String desc = "图片文件 " + info.name() + "(" + FileToolClient.formatSize(raw.bytes().length)
                                    + ", " + mime + "),原始字节已作为图像附件返回;直接描述你看到的内容";
                            return new ToolOutcome(desc, "图片", null, false,
                                    List.of(new McpServerService.McpToolResult.ImageBlock(mime, b64)));
                        }
                    }
                    return bounded("文件 " + info.name() + " 没有可提取的文本内容(可能是二进制/图片)", null);
                }
            }
            if (explicitCenter) {
                return new ToolOutcome("ERROR: 文件中心没有名为「" + t + "」的文件(用 action=list 查看全部文件)",
                        null, null, false);
            }
        }
        // 2) 其余(含路径分隔符、或文件中心未命中的纯文件名)→ 工作区/整机读
        if (agentWorkspaceService == null) {
            return new ToolOutcome("ERROR: 文件中心没有「" + t + "」,且工作区能力未启用(服务未配置)",
                    null, null, false);
        }
        try {
            // 复用工作区 read 的完整能力(图片图像通道/文本读取/截断)
            return execManageWorkspaceRead(t);
        } catch (Exception e) {
            return new ToolOutcome("ERROR: 读取失败: " + Texts.abbreviate(e.getMessage(), 200), null, null, false);
        }
    }

    /** 工作区 read 的轻量封装(供路径路由复用;不含 manage_workspace 的 action 校验开销)。 */
    private ToolOutcome execManageWorkspaceRead(String path) {
        String imgMime = AgentWorkspaceService.imageMime(path);
        if (imgMime != null) {
            try {
                byte[] bytes = agentWorkspaceService.readBytesAny(path);
                String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
                return new ToolOutcome(
                        "图片文件 " + path + "(" + FileToolClient.formatSize(bytes.length)
                                + ", " + imgMime + "),原始字节已作为图像附件返回;直接描述你看到的内容",
                        "图片", null, false,
                        List.of(new McpServerService.McpToolResult.ImageBlock(imgMime, b64)));
            } catch (IllegalArgumentException e) {
                return new ToolOutcome("ERROR: " + e.getMessage(), null, null, false);
            }
        }
        try {
            return bounded("(工作区文件 " + path + ")\n" + agentWorkspaceService.readAny(path), null);
        } catch (IllegalArgumentException e) {
            return new ToolOutcome("ERROR: " + e.getMessage(), null, null, false);
        }
    }

    /**
     * read_file 管理动作(2026-09-18 复查补齐):rename / move / delete / folders / mkdir。
     * 参数:id(文件 id,可逗号分隔多个) / name(新文件名或文件夹名) / folderId(目标文件夹,null=根)。
     */
    private ToolOutcome execFileManage(String action, String args) {
        JsonNode a;
        try {
            a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
        } catch (Exception e) {
            return new ToolOutcome("ERROR: 参数不是合法 JSON: " + e.getMessage(), null, null, false);
        }
        switch (action) {
            case "folders" -> {
                return bounded(fileToolClient.folders(), null);
            }
            case "mkdir" -> {
                String folderName = a.path("name").asText(null);
                if (folderName == null || folderName.isBlank()) {
                    return new ToolOutcome("ERROR: mkdir 需要 name 参数(新文件夹名)。"
                            + "示例:{\"action\": \"mkdir\", \"name\": \"合同\"}", null, null, false);
                }
                return bounded(fileToolClient.mkdir(folderName), null);
            }
            default -> {
                // rename / move / delete 都需要文件 id(rename 单个;move/delete 支持逗号分隔多个)
                List<Long> ids = parseFileIds(a);
                if (ids.isEmpty()) {
                    return new ToolOutcome("ERROR: " + action + " 需要 id 参数(文件 id,先 action=list 查看;"
                            + "move/delete 支持逗号分隔多个 id)。示例:{\"action\": \"" + action + "\", \"id\": \"12\"}",
                            null, null, false);
                }
                if ("rename".equals(action)) {
                    String newName = a.path("name").asText(null);
                    if (newName == null || newName.isBlank()) {
                        return new ToolOutcome("ERROR: rename 需要 name 参数(新文件名,含扩展名)。"
                                + "示例:{\"action\": \"rename\", \"id\": \"12\", \"name\": \"2026合同.pdf\"}", null, null, false);
                    }
                    if (ids.size() > 1) {
                        return new ToolOutcome("ERROR: rename 一次只能改一个文件(逐个改,或告诉我批量命名规则)",
                                null, null, false);
                    }
                    return bounded(fileToolClient.rename(ids.get(0), newName), null);
                }
                if ("move".equals(action)) {
                    Long folderId = a.path("folderId").isNumber() ? a.path("folderId").asLong() : null;
                    return bounded(fileToolClient.move(ids, folderId), null);
                }
                // delete(软删,进回收站)
                return bounded(fileToolClient.delete(ids), null);
            }
        }
    }

    /** 解析文件 id 参数:id / target 可以是数字/数字字符串/逗号分隔字符串(模型方言兼容)。 */
    private static List<Long> parseFileIds(JsonNode a) {
        List<Long> out = new java.util.ArrayList<>();
        JsonNode idNode = a.path("id");
        if (idNode.isMissingNode() || idNode.isNull()) {
            idNode = a.path("target"); // 模型常把 id 写进 target(与其他 manage_* 工具一致)
        }
        if (idNode.isNumber()) {
            out.add(idNode.asLong());
        } else if (idNode.isTextual()) {
            for (String part : idNode.asText("").split("[,\\s]+")) {
                if (part.matches("\\d+")) {
                    out.add(Long.parseLong(part));
                }
            }
        } else if (idNode.isArray()) {
            for (JsonNode n : idNode) {
                if (n.isNumber()) {
                    out.add(n.asLong());
                } else if (n.isTextual() && n.asText("").matches("\\d+")) {
                    out.add(Long.parseLong(n.asText("")));
                }
            }
        }
        return out;
    }

    /** manage_workspace handler(2026-09-17 从 executeTool 拆出,原分支逐行平移)。 */
    ToolOutcome execManageWorkspace(String name, String args, ToolStepEmitter.ParsedArgs parsed,
                            LiveOutput liveOutput) {
        if (agentWorkspaceService == null) {
            return new ToolOutcome("ERROR: 工作区能力未启用(服务未配置)", null, null, false);
        }
        String action = RiskClassifier.normalizeWorkspaceAction(parsed.datasourceAction());
        if (!java.util.Set.of("list", "read", "write", "append", "delete", "import", "move", "copy", "mkdir", "edit")
                .contains(action)) {
            return new ToolOutcome("ERROR: 拒绝执行「" + action + "」：action 只允许 "
                    + "list / read / write / append / delete / import / move / copy / mkdir / edit", null, null, false);
        }
        try {
            JsonNode a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
            // 参数方言兼容(2026-09-18 复盘数据驱动):模型对「读文件」的第一直觉
            // 参数名是 filename/file(实测 12 次失败全是 filename)——harness 层吸收
            // 模型方言,别让用户为参数名教学买单。与 RiskClassifier.workspacePathOf
            // 同一别名序(2026-09-20 统一):权限判定与实际执行必须看到同一路径。
            String path = RiskClassifier.workspacePathOf(a);
            // read 图片:文本解码必然失败("Input length = 1"),改走图像通道——
            // 原始字节作为图像附件喂给视觉模型;非视觉模型由 backfillToolMessage
            // 明确告知「看不到」,不静默丢弃、不报解码错误。
            if ("read".equals(action) && path != null && !path.isBlank()) {
                String imgMime = AgentWorkspaceService.imageMime(path);
                if (imgMime != null) {
                    try {
                        byte[] bytes = agentWorkspaceService.readBytesAny(path);
                        String b64 = java.util.Base64.getEncoder().encodeToString(bytes);
                        return new ToolOutcome(
                                "图片文件 " + path + "(" + FileToolClient.formatSize(bytes.length)
                                        + ", " + imgMime + "),原始字节已作为图像附件返回;直接描述你看到的内容",
                                "图片", null, false,
                                List.of(new McpServerService.McpToolResult.ImageBlock(imgMime, b64)));
                    } catch (IllegalArgumentException e) {
                        return new ToolOutcome("ERROR: " + e.getMessage(), null, null, false);
                    }
                }
            }
            // 文本写操作的变更记录(对话「已编辑 N 个文件」卡片):switch 内写分支
            // 逐项追加,switch 后统一挂到 ToolOutcome;非写操作保持空列表。
            List<ChatStepDto.FileChange> fileChanges = new java.util.ArrayList<>();
            String result = switch (action) {
                case "list" -> {
                    // dir 优先,其次 path(agent 可能把路径塞进 path)
                    String dirArg = a.path("dir").asText(null);
                    if (dirArg == null) {
                        dirArg = a.path("path").asText(null);
                    }
                    List<AgentWorkspaceService.FileEntry> entries =
                            agentWorkspaceService.listAny(dirArg);
                    if (entries.isEmpty()) {
                        yield "(空目录)";
                    }
                    StringBuilder sb = new StringBuilder("工作区文件(" + path + " 相对根目录):\n");
                    for (AgentWorkspaceService.FileEntry f : entries) {
                        sb.append(f.directory() ? "[目录] " : "").append(f.path());
                        if (f.directory()) {
                            // 目录带摘要(文件数+总大小):模型判断"素材在不在"不必再跑 PowerShell 数
                            sb.append(" (").append(f.fileCount()).append(" 个文件, ")
                                    .append(FileToolClient.formatSize(f.size())).append(")");
                        } else {
                            sb.append(" (").append(f.size()).append("B, ").append(f.modifiedAt()).append(")");
                        }
                        sb.append('\n');
                    }
                    yield sb.toString();
                }
                case "read" -> {
                    if (path == null || path.isBlank()) {
                        yield "ERROR: 缺少 path 参数。相对路径=工作区内(如 USER.md);绝对路径可读整机(如 D:/projects/x/README.md)"
                                + ";路径本身就是相对工作区解析的,不要再拼工作区目录名(避免 agent-workspace/agent-workspace 这类重复)";
                    }
                    // 分段读(设计 §5.3 大结果回读):offset/limit 给截断后的续读出路
                    Integer readOffset = a.path("offset").isNumber() ? a.path("offset").asInt() : null;
                    if (readOffset != null) {
                        Integer readLimit = a.path("limit").isNumber() ? a.path("limit").asInt() : 500;
                        yield agentWorkspaceService.readRangeAny(path, readOffset, readLimit);
                    }
                    yield agentWorkspaceService.readAny(path);
                }
                case "write" -> {
                    if (path == null || path.isBlank()) {
                        yield "ERROR: 缺少 path 参数(相对=工作区内,如 USER.md;绝对=整机)。"
                                + "示例:{\"action\": \"write\", \"path\": \"USER.md\", \"content\": \"…\"}";
                    }
                    String content = a.path("content").asText(null);
                    if (content == null) {
                        yield "ERROR: 缺少 content 参数(要写入的完整内容;如需保留原内容请先 read)";
                    }
                    boolean existed = agentWorkspaceService.existsAny(path);
                    String before = existed ? agentWorkspaceService.readTextOrNull(path) : null;
                    int written = agentWorkspaceService.writeAny(path, content);
                    addFileChange(fileChanges, path, "write", before, content, existed);
                    yield "已写入 " + path + "(" + written + " 字符)";
                }
                case "append" -> {
                    if (path == null || path.isBlank()) {
                        yield "ERROR: 缺少 path 参数(相对=工作区内,如 memory/2026-09-18.md)。"
                                + "示例:{\"action\": \"append\", \"path\": \"memory/2026-09-18.md\", \"content\": \"…\"}";
                    }
                    String content = a.path("content").asText(null);
                    if (content == null || content.isBlank()) {
                        yield "ERROR: 缺少 content 参数(要追加的内容)";
                    }
                    boolean existed = agentWorkspaceService.existsAny(path);
                    String before = existed ? agentWorkspaceService.readTextOrNull(path) : null;
                    int written = agentWorkspaceService.appendAny(path, content);
                    addFileChange(fileChanges, path, "append", before, (before == null ? "" : before) + content, existed);
                    yield "已追加 " + written + " 字符到 " + path;
                }
                case "edit" -> {
                    if (path == null || path.isBlank()) {
                        yield "ERROR: edit 需要 path(要编辑的文件)。"
                                + "示例:{\"action\": \"edit\", \"path\": \"MEMORY.md\", \"old_string\": \"…\", \"new_string\": \"…\"}";
                    }
                    String oldString = a.path("old_string").asText(null);
                    String newString = a.path("new_string").asText(null);
                    if (oldString == null || oldString.isEmpty()) {
                        yield "ERROR: edit 需要 old_string 参数(要被替换的原文,必须与文件内容精确一致,含缩进;"
                                + "在文件中必须唯一出现)。若要整文件重写请改用 write";
                    }
                    if (newString == null) {
                        yield "ERROR: edit 需要 new_string 参数(替换后的新文本;留空字符串=删除该段)";
                    }
                    String before = agentWorkspaceService.readTextOrNull(path);
                    String editResult = agentWorkspaceService.editAny(path, oldString, newString);
                    // editAny 成功后 oldString 必然唯一,重放替换即为写后内容
                    if (before != null) {
                        int idx = before.indexOf(oldString);
                        if (idx >= 0) {
                            String after = before.substring(0, idx) + newString + before.substring(idx + oldString.length());
                            addFileChange(fileChanges, path, "edit", before, after, true);
                        }
                    }
                    yield editResult;
                }
                case "import" -> importFromUrl(a, path);
                case "move" -> {
                    // 源/目标:path 是源,to 是目标(与 write 的 path 语义一致)
                    String to = a.path("to").asText(null);
                    if (path == null || path.isBlank()) {
                        yield "ERROR: move 需要 path(源路径)与 to(目标路径);移动不覆盖已存在的目标";
                    }
                    if (to == null || to.isBlank()) {
                        yield "ERROR: move 需要 to 参数(目标路径;目标为已存在目录时移入该目录)";
                    }
                    yield agentWorkspaceService.moveAny(path, to);
                }
                case "mkdir" -> {
                    // 建目录(write/move 会自动建父目录,但空目录需要显式创建)
                    if (path == null || path.isBlank()) {
                        yield "ERROR: mkdir 需要 path 参数(要创建的目录路径)。"
                                + "示例:{\"action\": \"mkdir\", \"path\": \"photos/2026-09-18\"}";
                    }
                    yield agentWorkspaceService.mkdirAny(path);
                }
                case "copy" -> {
                    String to = a.path("to").asText(null);
                    if (path == null || path.isBlank()) {
                        yield "ERROR: copy 需要 path(源路径)与 to(目标路径);复制不覆盖已存在的目标";
                    }
                    if (to == null || to.isBlank()) {
                        yield "ERROR: copy 需要 to 参数(目标路径;目标为已存在目录时复制进该目录)";
                    }
                    yield agentWorkspaceService.copyAny(path, to);
                }
                default -> {
                    if (path == null || path.isBlank()) {
                        yield "ERROR: 缺少 path 参数;删除不可恢复,请先向用户确认";
                    }
                    agentWorkspaceService.deleteAny(path);
                    yield "已删除 " + path;
                }
            };
            return new ToolOutcome(result, null, null, false, java.util.List.of(), false, false,
                    null, null, null, fileChanges.isEmpty() ? null : fileChanges);
        } catch (IllegalArgumentException e) {
            return new ToolOutcome("ERROR: " + e.getMessage(), null, null, false);
        } catch (Exception e) {
            return new ToolOutcome("ERROR: 工作区操作失败: " + Texts.abbreviate(e.getMessage(), 200), null, null, false);
        }
    }

    /**
     * 文本写操作的变更记录(对话「已编辑 N 个文件」卡片,2026-09-30):
     * 区外文件与 agent 内部状态文件(人格/记忆/日记)不记录——前者不可通过
     * 查看器打开,后者是 agent 自身记账,弹卡片只会造成每轮噪音。
     * 前后内容任一不可读(二进制/超限)时跳过统计,不阻塞写操作本身。
     */
    private void addFileChange(List<ChatStepDto.FileChange> changes, String path, String kind,
                               String before, String after, boolean existed) {
        String target = agentWorkspaceService.changeTargetOrNull(path);
        if (target == null) {
            return;
        }
        if (existed && before == null) {
            return; // 写前内容不可读:无法给出可靠的行级统计
        }
        AgentWorkspaceService.ChangeStats stats = AgentWorkspaceService.diffStats(before, after);
        String name = target.substring(target.lastIndexOf('/') + 1);
        changes.add(new ChatStepDto.FileChange(target, name, kind,
                stats.additions(), stats.deletions(), !existed));
    }

    /**
     * 把远程 URL 下载并存成工作台「文件」(read_file action=import)。
     *
     * <p>与 manage_workspace 的 import 区别:这里存进用户可见的文件中心
     * (可预览/删除/建索引),适合“把这张图存起来我稍后看”。
     */
    private String importToWorkbenchFile(String args) {
        JsonNode a;
        try {
            a = objectMapper.readTree(args == null || args.isBlank() ? "{}" : args);
        } catch (Exception e) {
            return "ERROR: 参数不是合法 JSON: " + e.getMessage();
        }
        String url = a.path("url").asText(null);
        if (url == null || url.isBlank()) {
            return "ERROR: 缺少 url 参数(要下载的地址)";
        }
        if (!url.matches("(?i)^https?://.*")) {
            return "ERROR: url 必须是 http/https 地址,当前收到: " + Texts.abbreviate(url, 120);
        }
        try {
            byte[] body = downloadBounded(url, AgentWorkspaceService.MAX_BINARY_BYTES);
            if (body == null || body.length == 0) {
                return "ERROR: 下载到空内容";
            }
            String name = a.path("filename").asText(null);
            if (name == null || name.isBlank()) {
                name = inferFilename(url);
            }
            return fileToolClient.upload(name, body);
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            return "ERROR: 下载失败: " + Texts.abbreviate(e.getMessage() == null ? e.toString() : e.getMessage(), 200);
        }
    }

    /**
     * 带大小上限的流式下载:先看 Content-Length 快速拒绝,再边读边计数,
     * 超限立即中断(不再全量进内存后才判断——大视频会把堆打爆)。
     *
     * <p>链路自动选择(2026-09-17 晚):目标属于中继域名时走
     * {@link RelayMediaRouter} 重写——在家自动走内网 8902(快一个量级),
     * 失败立即回退公网。此前 import 单文件下载没接路由器,「在家下载相册」
     * 走的是公网绕行路径。
     *
     * @param url      下载地址
     * @param maxBytes 允许的最大字节数
     * @return 文件字节
     * @throws IllegalArgumentException 超限或下载失败(消息面向用户可读)
     */
    private byte[] downloadBounded(String url, long maxBytes) throws Exception {
        String effective = relayMediaRouter == null ? url : relayMediaRouter.preferLan(url);
        try {
            return downloadBoundedDirect(effective, url, maxBytes);
        } catch (Exception e) {
            if (!effective.equals(url) && relayMediaRouter != null) {
                relayMediaRouter.reportLanFailure();
                log.info("局域网下载失败,回退公网: {} ({})", url, e.getMessage());
                return downloadBoundedDirect(url, url, maxBytes);
            }
            throw e;
        }
    }

    /** 单次下载(不做链路选择);displayUrl 仅用于错误消息。 */
    private byte[] downloadBoundedDirect(String effective, String displayUrl, long maxBytes) throws Exception {
        java.net.http.HttpClient.Builder cb = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1) // 明文链路(局域网 8902)防 h2c 升级探测
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL);
        java.net.InetSocketAddress proxyAddr = com.nora.common.http.ProxySettingsHolder.addressFor(effective);
        if (proxyAddr != null) {
            cb.proxy(java.net.ProxySelector.of(proxyAddr));
        }
        java.net.http.HttpResponse<java.io.InputStream> resp = cb.build().send(
                java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(effective))
                        .timeout(Duration.ofSeconds(120))
                        .header("User-Agent", "Nora-Agent/1.0")
                        .GET().build(),
                java.net.http.HttpResponse.BodyHandlers.ofInputStream());
        if (resp.statusCode() >= 400) {
            throw new IllegalArgumentException("下载失败 HTTP " + resp.statusCode() + "(" + Texts.abbreviate(displayUrl, 100) + ")");
        }
        long declared = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (declared > maxBytes) {
            throw new IllegalArgumentException("文件超过 " + (maxBytes / 1024 / 1024) + "MB 上限(源声明 "
                    + (declared / 1024 / 1024) + "MB)");
        }
        try (java.io.InputStream in = resp.body();
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buf = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) >= 0) {
                total += n;
                if (total > maxBytes) {
                    throw new IllegalArgumentException("文件超过 " + (maxBytes / 1024 / 1024) + "MB 上限,已中断下载");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * 从 URL 下载内容并写入文件系统(manage_workspace action=import)。
     *
     * <p>典型用法:把 MCP 工具(如手机相册)返回的图片链接存到工作区,
     * 供用户在工作台「文件」里查看/整理/归档。下载走全局出站代理配置
     * (外网目标经代理、内网/本机直连,见 ProxySupport)。
     *
     * @param a    工具参数(url / filename 可选)
     * @param path 目标路径(缺省时用下载文件名放到工作区根)
     */
    private String importFromUrl(JsonNode a, String path) {
        String url = a.path("url").asText(null);
        if (url == null || url.isBlank()) {
            return "ERROR: 缺少 url 参数(要下载的地址,如 MCP 工具返回的 contentUrl/thumbUrl)";
        }
        if (!url.matches("(?i)^https?://.*")) {
            return "ERROR: url 必须是 http/https 地址,当前收到: " + Texts.abbreviate(url, 120);
        }
        if (agentWorkspaceService == null) {
            return "ERROR: 工作区能力未启用(服务未配置)";
        }
        // 目标路径:显式给定优先;否则用 filename 或从 URL 推断,放到工作区根
        String target = path;
        if (target == null || target.isBlank()) {
            String name = a.path("filename").asText(null);
            if (name == null || name.isBlank()) {
                name = inferFilename(url);
            }
            target = "imports/" + name;
        } else if (target.endsWith("/") || target.endsWith("\\")
                || (agentWorkspaceService != null && agentWorkspaceService.isDirectoryAny(target))) {
            // 目录语义兼容(2026-09-18 文件工具分析):模型写 path="imports/" 或
            // 已存在的目录(想把文件存进去),自动补文件名——比报「目标是目录」可操作
            String name = a.path("filename").asText(null);
            if (name == null || name.isBlank()) {
                name = inferFilename(url);
            }
            target = target.replaceAll("[/\\\\]+$", "") + "/" + name;
        }
        try {
            byte[] body = downloadBounded(url, AgentWorkspaceService.MAX_BINARY_BYTES);
            if (body == null || body.length == 0) {
                return "ERROR: 下载到空内容(" + Texts.abbreviate(url, 100) + ")";
            }
            boolean existed = agentWorkspaceService.existsAny(target);
            long written = agentWorkspaceService.writeBinaryAny(target, body);
            return "已保存到 " + target + "(" + FileToolClient.formatSize(written)
                    + (existed ? ",已覆盖同名文件" : "")
                    + ", 来源: " + Texts.abbreviate(url, 90) + ")";
        } catch (IllegalArgumentException e) {
            return "ERROR: " + e.getMessage();
        } catch (Exception e) {
            return "ERROR: 下载失败: " + Texts.abbreviate(e.getMessage() == null ? e.toString() : e.getMessage(), 200);
        }
    }

    /** 从 URL 推断合适的文件名(取路径末段并去掉查询参数)。 */
    private static String inferFilename(String url) {
        try {
            String p = java.net.URI.create(url).getPath();
            if (p != null && p.contains("/")) {
                String last = p.substring(p.lastIndexOf('/') + 1);
                if (!last.isBlank() && last.contains(".")) {
                    return last.replaceAll("[\\\\/:*?\"<>|]", "_");
                }
            }
        } catch (Exception ignored) {
            // 推断失败走默认名
        }
        return "import-" + System.currentTimeMillis() + ".bin";
    }

    ToolOutcome execOpenFile(String args, String sessionId, String stepId) {
        if (viewerService == null) return new ToolOutcome("ERROR: 文件查看服务不可用", null, null, false);
        try {
            JsonNode input = objectMapper.readTree(args);
            JsonNode targetsNode = input.path("targets");
            if (!targetsNode.isArray()) throw new IllegalArgumentException("targets 必须是文件引用数组");
            var targets = new java.util.ArrayList<String>();
            for (JsonNode target : targetsNode) {
                if (!target.isTextual()) throw new IllegalArgumentException("文件引用必须是字符串");
                targets.add(target.asText());
            }
            String intent = input.path("intent").asText("inspect");
            if (!java.util.Set.of("inspect", "deliver").contains(intent)) throw new IllegalArgumentException("intent 只允许 inspect / deliver");
            String focus = input.path("focus").asText(null);
            if (focus != null && !targets.contains(focus)) throw new IllegalArgumentException("focus 必须属于 targets");
            var resolved = viewerService.resolve(targets);
            if ("deliver".equals(intent)) resolved = viewerService.deliver(resolved, sessionId, stepId);
            String focusTarget = resolved.files().isEmpty() ? null : resolved.files().get(0).target();
            if (focus != null) {
                try {
                    String canonical = viewerService.resolveOne(focus).target();
                    if (resolved.files().stream().anyMatch(file -> file.target().equals(canonical))) focusTarget = canonical;
                } catch (IllegalArgumentException | java.io.IOException | org.springframework.web.client.RestClientException ignored) { /* 使用首个有效文件。 */ }
            }
            StringBuilder content = new StringBuilder(resolved.files().isEmpty() ? "ERROR: 没有可打开的文件\n" : "已验证文件，可通过文件入口查看：\n");
            for (var file : resolved.files()) {
                content.append(file.target()).append(" | ").append(file.name()).append(" | ").append(file.size()).append(" bytes");
                if (file.delivery() != null) content.append(" | 登记:").append(file.delivery().status());
                content.append('\n');
            }
            for (var error : resolved.errors()) content.append(error.target()).append(" | ").append(error.message()).append('\n');
            boolean partial = !resolved.errors().isEmpty() || resolved.files().stream()
                    .anyMatch(file -> file.delivery() != null && "failed".equals(file.delivery().status()));
            var boundedContent = bounded(content.toString(), null);
            return new ToolOutcome(boundedContent.content(), resolved.files().size() + " 个文件可查看", null, boundedContent.truncated(),
                    List.of(), false, partial && !resolved.files().isEmpty(), resolved.files(), focusTarget, resolved.errors());
        } catch (java.io.IOException | IllegalArgumentException e) {
            return new ToolOutcome("ERROR: " + e.getMessage(), null, null, false);
        }
    }

}
