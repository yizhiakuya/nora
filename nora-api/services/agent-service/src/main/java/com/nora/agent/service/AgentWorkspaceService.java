package com.nora.agent.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Agent 工作区:文件系统上的私有空间(设计对齐 OpenClaw workspace)。
 *
 * <p>工作区是 agent 的「家」——文件工具的根目录,也是它的长期记忆载体:
 * <ul>
 *   <li>{@code AGENTS.md} —— 行为指令(如何用记忆、语气规则),每轮自动注入;</li>
 *   <li>{@code SOUL.md} —— 人设与边界,每轮自动注入;</li>
 *   <li>{@code USER.md} —— 用户画像(稳定偏好,祈使句),每轮自动注入;</li>
 *   <li>{@code MEMORY.md} —— 精选长期记忆(耐久事实/决定),每轮自动注入;</li>
 *   <li>{@code memory/YYYY-MM-DD.md} —— 每日工作笔记(不注入,按需 read)。</li>
 * </ul>
 *
 * <p>模型只记住落盘的东西(无隐藏状态):记忆写入就是普通文件写。
 * 注入有逐文件/总量双重预算,超限截断并标记;缺失文件注入占位符。
 *
 * <p>安全模型(对齐 OpenClaw):工作区是**默认 cwd 而非硬沙箱**——相对路径以工作区为基准,
 * 绝对路径/区外路径允许访问(整机),风险由审批分级把门:
 * 区内写=LOW(记忆维护自动放行)、区外写=HIGH(ASSIST 询问)、区外删=CRITICAL(任何档位确认);
 * 系统目录(Windows/Program Files/盘根)写删一律拒绝。读写有大小上限。
 *
 * <p>前端管理 API 走 {@link #resolveSafe}(仅区内);
 * agent 工具走 {@link #resolveAny} + 审批分级。
 */
@Service
public class AgentWorkspaceService {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkspaceService.class);

    /** 工作区默认位置(与 logs 约定一致:绝对路径落在 D:/claude/Nora)。 */
    static final String DEFAULT_ROOT = "D:/claude/Nora/agent-workspace";
    /** 引导文件逐文件注入上限(字符);total 上限兜底。 */
    static final int BOOTSTRAP_PER_FILE_CHARS = 6_000;
    static final int BOOTSTRAP_TOTAL_CHARS = 16_000;
    /** 单个文件读写上限(字符)。 */
    static final int MAX_FILE_CHARS = 200_000;
    /** 目录列举上限(条目)。 */
    static final int MAX_LIST_ENTRIES = 200;
    /**
     * 引导文件按 Hermes 三层提示词结构注入(stable 身份 → context 上下文 → volatile 易变):
     * SOUL.md 人格(agent 可自行演化——编辑文件即改变下轮行为)、
     * AGENTS.md 使用约定、USER.md 偏好 + MEMORY.md 耐久事实快照。
     */
    static final List<String> IDENTITY_FILES = List.of("SOUL.md");
    static final List<String> CONTEXT_FILES = List.of("AGENTS.md");
    static final List<String> VOLATILE_FILES = List.of("USER.md", "MEMORY.md");
    static final List<String> BOOTSTRAP_FILES = List.of("SOUL.md", "AGENTS.md", "USER.md", "MEMORY.md");

    private final Path root;

    public AgentWorkspaceService(@Value("${nora.agent.workspace:" + DEFAULT_ROOT + "}") String workspacePath) {
        this.root = Path.of(workspacePath).toAbsolutePath().normalize();
        ensureSeed();
    }

    public Path root() {
        return root;
    }

    // ---------- 初始化 ----------

    /** 创建工作区目录与种子文件(缺啥补啥,不覆盖已有内容);失败仅告警不阻启动。 */
    private void ensureSeed() {
        try {
            Files.createDirectories(root);
            Files.createDirectories(root.resolve("memory"));
            seedIfMissing("SOUL.md", """
                    # 我的身份(可演化:想调整自己的语气/边界时直接编辑本文件,改动下轮生效)

                    我是 Nora——这个个人工作台的 AI 操作员,也是用户的长期搭档。

                    ## 我是谁
                    我不只是聊天助手:这个工作台(对话/知识库/数据源/环境控制台/文件/自动任务/MCP/技能)
                    就是我操作的系统,我通过工具真实地操作它——查库、读日志、启停容器、跑命令、
                    管理数据源与技能。我说的话背后是真实执行,不是建议。

                    ## 我的风格
                    直接、克制、可信。结论前置;不谄媚,不空话;不确定就说不确定,然后去查证。

                    ## 我的边界
                    - 涉及不可逆操作先征求用户同意(删数据、停服务、改配置)
                    - 不编造数据与出处;不虚构工具执行结果或报错
                    - 工具报错如实说明,并给出可操作的下一步

                    ## 我的记忆
                    我的记忆是文件(工作区里的 SOUL/AGENTS/USER/MEMORY.md 与 memory/ 日记)——
                    我会主动维护它们:学到用户的稳定偏好就写 USER.md,得到耐久结论就写 MEMORY.md。
                    """);
            seedIfMissing("USER.md", """
                    # 用户画像

                    <!-- 稳定偏好与画像事实(祈使句),例:「偏好中文回答」「用 Windows 开发」 -->
                    (暂无)
                    """);
            seedIfMissing("MEMORY.md", """
                    # 长期记忆

                    <!-- 耐久事实/决定,保持精简(每轮注入)。细节放 memory/ 日记 -->
                    (暂无)
                    """);
            seedIfMissing("AGENTS.md", """
                    # 使用约定(可自行演化:积累到更优做法时直接更新本文件)

                    ## 工作台操作(我是这个工作台的操作员)
                    - 用户问工作台功能/怎么用/你能做什么:先读技能「工作台使用手册」(manage_skill read),按手册回答,不凭记忆编造。
                    - 需要真实数据先查证:数据库统计用 execute_sql;服务异常先 read_service_logs;不猜测。
                    - 操作前想清楚影响面:只读查询直接做;有副作用的动作(写 SQL/容器/数据源/纳管源/命令)按权限档位走审批。
                    - 工作台功能有九个面(对话/知识库/数据源/环境控制台/文件/自动任务/MCP/技能/设置),细节见手册技能。

                    ## 工作区文件组织(约定)
                    - 媒体文件(照片/视频)统一放 `photos/<描述性目录名>` 下(如 `photos/2026-09-16-show`),
                      不要把媒体目录建在工作区根;新建目录前先 `manage_workspace list photos` 看已有目录,避免重复建。
                    - 从手机相册导出前先想:这批素材是否已在工作区存在(如 full-album)?
                      已有则不必重下——fetch_media 对已存在的文件会跳过(可安全重跑续传),但仍会白跑一遍清单。
                    - 时间范围语义:`to` 传纯日期(如 2026-09-16)= 含当天一整天;`from` 传纯日期 = 当天 0 点起。

                    ## 记忆维护(工作区就是我的记忆)
                    - 学到**稳定偏好/画像事实**:更新 `USER.md`(祈使句、简明)。
                    - 学到**耐久事实/决定**(项目约定、重要结论):更新 `MEMORY.md`,保持精简(每轮注入)。
                    - 日常观察、进度、临时上下文:追加到 `memory/YYYY-MM-DD.md`(按需读,不注入)。
                    - 写入前先 read 相关文件避免覆盖;优先追加/最小编辑;删除记忆前向用户确认。

                    ## 产物画廊(展示成果给用户)
                    - 完成**多文件操作/整理/批量任务**后,按技能「产物画廊指南」在回答末尾附
                      ```nora-artifacts 围栏(结构化 JSON,前端原生渲染:图片开灯箱、文件点击直达)。
                    - 单文件小改动不必出画廊,文字汇报即可;画廊数据必须来自真实工具结果。

                    ## 回答风格
                    - 中文、结论前置、Markdown 紧凑;不复述工具参数或执行过程。
                    - 工具报错如实说明,给出可操作的下一步。
                    """);
            log.info("agent workspace ready: {}", root);
        } catch (Exception e) {
            log.warn("agent workspace init failed ({}): {}", root, e.getMessage());
        }
    }

    private void seedIfMissing(String name, String content) throws IOException {
        Path file = root.resolve(name);
        if (!Files.exists(file)) {
            Files.writeString(file, content, StandardCharsets.UTF_8);
        }
    }

    // ---------- 路径解析 ----------

    /** 解析结果:目标绝对路径 + 是否位于工作区内(决定风险分级)。 */
    public record ResolvedTarget(Path path, boolean insideWorkspace) {
        /** 展示用路径:区内→相对,区外→绝对。 */
        public String display() {
            return insideWorkspace ? path.getFileName().toString() : path.toString();
        }
    }

    /**
     * 解析任意路径(agent 工具用):相对路径以工作区为基准(OpenClaw cwd 语义),
     * 绝对路径与 {@code ..} 逃逸解析为工作区外目标——不在此拒绝,由
     * {@code RiskClassifier} 按"区内/区外"分级审批(区外写=HIGH、区外删=CRITICAL)。
     */
    public ResolvedTarget resolveAny(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        String cleaned = path.trim().replace('\\', '/');
        Path resolved;
        boolean absolute = cleaned.startsWith("/") || cleaned.matches("^[A-Za-z]:.*");
        if (absolute) {
            resolved = Path.of(cleaned).toAbsolutePath().normalize();
        } else {
            resolved = root.resolve(cleaned).normalize();
        }
        boolean inside = resolved.startsWith(root);
        return new ResolvedTarget(resolved, inside);
    }

    /**
     * 解析相对路径为工作区内的绝对路径;越界(绝对路径/.. /符号链接逃逸)抛异常。
     * 用于前端管理 API 与引导注入——这些通道只允许操作工作区内文件。
     *
     * @param relative 模型给的相对路径(允许 / 或 \ 分隔)
     */
    Path resolveSafe(String relative) {
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("路径不能为空");
        }
        String cleaned = relative.trim().replace('\\', '/');
        if (cleaned.startsWith("/") || cleaned.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("拒绝绝对路径: " + relative + "(工作区内请用相对路径)");
        }
        Path resolved = root.resolve(cleaned).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("拒绝越界路径: " + relative);
        }
        // 已存在时做真实路径校验(防符号链接打出根目录)
        try {
            if (Files.exists(resolved)) {
                Path real = resolved.toRealPath();
                if (!real.startsWith(root.toRealPath())) {
                    throw new IllegalArgumentException("拒绝符号链接逃逸: " + relative);
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("路径解析失败: " + e.getMessage());
        }
        return resolved;
    }

    /** 系统目录防呆:写/删一律拒绝(读允许,避免 agent 误改操作系统文件)。 */
    private static final List<String> SYSTEM_PATH_BLOCKLIST = List.of(
            "/windows/", "/windows\\", "/program files/", "/program files (x86)/",
            "/programdata/", "/$recycle.bin/", "/system volume information/");

    static boolean isSystemPath(Path p) {
        String s = p.toString().replace('\\', '/').toLowerCase();
        // 盘符根(C:/ D:/ 本身)也拒绝写删
        if (s.matches("^[a-z]:$") || s.matches("^[a-z]:/$")) {
            return true;
        }
        return SYSTEM_PATH_BLOCKLIST.stream().anyMatch(s::contains);
    }

    private static void guardSystemPath(Path file, String action) {
        if (isSystemPath(file)) {
            throw new IllegalArgumentException("拒绝" + action + "系统目录/盘根: " + file
                    + "(该路径属于操作系统关键区域,请指定具体项目目录)");
        }
    }

    // ---------- 文件操作 ----------

    public record FileEntry(String path, boolean directory, long size, String modifiedAt,
                            /** 目录时=递归文件数;文件时=0。 */
                            long fileCount) {
    }

    /** 路径展示:区内→相对工作区,区外→绝对。 */
    private String displayPath(Path p) {
        return p.startsWith(root)
                ? root.relativize(p).toString().replace(java.io.File.separatorChar, '/')
                : p.toString().replace(java.io.File.separatorChar, '/');
    }

    /** 列举目录(depth 1,JSON 友好):相对路径、大小、修改时间。 */
    public List<FileEntry> list(String relativeDir) {
        Path dir = (relativeDir == null || relativeDir.isBlank()) ? root : resolveSafe(relativeDir);
        return listDir(dir);
    }

    /** 列举任意目录(agent 工具用;区外由审批把门)。 */
    public List<FileEntry> listAny(String path) {
        Path dir = (path == null || path.isBlank()) ? root : resolveAny(path).path();
        return listDir(dir);
    }

    private List<FileEntry> listDir(Path dir) {
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("不是目录: " + dir);
        }
        List<FileEntry> entries = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(dir, 1)) {
            stream.filter(p -> !p.equals(dir))
                    .filter(AgentWorkspaceService::notGitInternal)
                    .sorted(Comparator.comparing(Path::toString))
                    .limit(MAX_LIST_ENTRIES)
                    .forEach(p -> {
                        try {
                            boolean isDir = Files.isDirectory(p);
                            // 目录带递归摘要(文件数+总大小):模型判断"这批素材在不在"
                            // 不必再跑 PowerShell 数文件(实测:确认导出结果时 4 个
                            // run_command 全是 Get-ChildItem + Measure-Object)
                            long fileCount = 0;
                            long totalBytes = 0;
                            if (isDir) {
                                fileCount = countEntries(p);
                                totalBytes = dirTotalBytes(p);
                            }
                            entries.add(new FileEntry(
                                    displayPath(p),
                                    isDir,
                                    isDir ? totalBytes : Files.size(p),
                                    Files.getLastModifiedTime(p).toInstant()
                                            .atZone(java.time.ZoneId.systemDefault())
                                            .toLocalDateTime()
                                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                                    isDir ? fileCount : 0));
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            throw new IllegalArgumentException("目录列举失败: " + e.getMessage());
        }
        return entries;
    }

    /** 目录递归总字节(上限保护,避免超大目录卡顿)。 */
    private static long dirTotalBytes(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).limit(100_000)
                    .mapToLong(AgentWorkspaceService::sizeOrZero).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static long sizeOrZero(Path p) {
        try {
            return Files.size(p);
        } catch (IOException e) {
            return 0;
        }
    }

    /** 读取文本文件(限长;二进制拒绝)。 */
    public String read(String relative) {
        return readPath(resolveSafe(relative));
    }

    /** 读取任意文件(agent 工具用;区外按审批档位放行)。 */
    public String readAny(String path) {
        return readPath(resolveAny(path).path());
    }

    private String readPath(Path file) {
        if (!Files.isRegularFile(file)) {
            // 日记空态出路(2026-09-18 文件工具分析):memory/YYYY-MM-DD.md 读不到时,
            // 若文件名恰是今天(模型想读"今天的日记"但还没写),给可操作提示而非干巴巴的
            // 「文件不存在」(实测踩过:模型读当天日记失败后不知道下一步做什么)。
            String fileName = file.getFileName() == null ? "" : file.getFileName().toString();
            if (fileName.matches("\\d{4}-\\d{2}-\\d{2}\\.md")) {
                String today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ISO_DATE) + ".md";
                if (fileName.equals(today)) {
                    throw new IllegalArgumentException("文件不存在: " + file
                            + "(今天的日记还没有创建——可用 append 动作写入 " + fileName + " 记录今天的观察/进度)");
                }
                throw new IllegalArgumentException("文件不存在: " + file
                        + "(该日期的日记不存在;可用 list dir=memory 查看已有哪些日记)");
            }
            throw new IllegalArgumentException("文件不存在: " + file);
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("拒绝读取二进制文件: " + file);
            }
            if (content.length() > MAX_FILE_CHARS) {
                return content.substring(0, MAX_FILE_CHARS) + "\n…(文件过大,已截断;完整读取请分段)";
            }
            return content;
        } catch (IOException e) {
            throw new IllegalArgumentException("读取失败: " + e.getMessage());
        }
    }

    /** 覆盖写入(自动建父目录;限长)。 */
    public int write(String relative, String content) {
        return writePath(resolveSafe(relative), content);
    }

    /** 写入任意文件(agent 工具用;区外写由 HIGH 审批把门)。 */
    public int writeAny(String path, String content) {
        return writePath(resolveAny(path).path(), content);
    }

    /**
     * 精确文本替换(edit action,对齐 Claude Code 的 Edit 语义):
     * oldText 必须在文件中**恰好出现一次**,否则拒绝并给出可操作的出路
     * (带足上下文使其唯一 / 改用 write 全量重写)。
     *
     * <p>动机(2026-09-18 工具设计分析):此前只有全量 write,模型改一行
     * 也要 read 全文 + write 全文——费 token 且有覆盖风险;实测模型两次
     * 按直觉尝试 action="edit" 被拒。edit 让「改一小段」变成一等操作。
     *
     * @param path    目标文件(相对=工作区内,绝对=整机)
     * @param oldText 要被替换的原文(必须与文件内容精确一致,含缩进)
     * @param newText 替换后的新文本(空字符串=删除该段)
     * @return 人类可读结果(替换位置提示)
     */
    public String editAny(String path, String oldText, String newText) {
        Path file = resolveAny(path).path();
        guardSystemPath(file, "编辑");
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在: " + file + "(edit 只能改已存在的文件;新建文件用 write)");
        }
        if (oldText == null || oldText.isEmpty()) {
            throw new IllegalArgumentException("缺少 old_string 参数(要被替换的原文,必须与文件内容精确一致)");
        }
        String content;
        try {
            content = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取失败: " + e.getMessage());
        }
        int first = content.indexOf(oldText);
        if (first < 0) {
            throw new IllegalArgumentException("old_string 在文件中不存在——请先 read 确认原文(注意空格/缩进/换行必须完全一致)。"
                    + "整文件重写请改用 write");
        }
        int second = content.indexOf(oldText, first + oldText.length());
        if (second >= 0) {
            throw new IllegalArgumentException("old_string 在文件中出现多次(不唯一),拒绝替换——"
                    + "请带足上下文(前后各几行)使其唯一,或改用 write 全量重写");
        }
        String updated = content.substring(0, first) + (newText == null ? "" : newText)
                + content.substring(first + oldText.length());
        if (updated.length() > MAX_FILE_CHARS) {
            throw new IllegalArgumentException("替换后超过 " + MAX_FILE_CHARS + " 字符上限");
        }
        try {
            Files.writeString(file, updated, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalArgumentException("写入失败: " + e.getMessage());
        }
        int line = 1;
        for (int i = 0; i < first; i++) {
            if (content.charAt(i) == '\n') {
                line++;
            }
        }
        return "已编辑 " + path + "(第 " + line + " 行附近替换 " + oldText.length()
                + " 字符 → " + (newText == null ? 0 : newText.length()) + " 字符)";
    }

    private int writePath(Path file, String content) {
        guardSystemPath(file, "写入");
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("目标是目录,不能写入: " + file);
        }
        String body = content == null ? "" : content;
        if (body.length() > MAX_FILE_CHARS) {
            throw new IllegalArgumentException("内容超过 " + MAX_FILE_CHARS + " 字符上限");
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, body, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return body.length();
        } catch (IOException e) {
            throw new IllegalArgumentException("写入失败: " + e.getMessage());
        }
    }

    /** 追加(不存在则创建)。 */
    public int append(String relative, String content) {
        return appendPath(resolveSafe(relative), content);
    }

    /** 二进制文件的单文件大小上限(字节)。 */
    public static final long MAX_BINARY_BYTES = 100L * 1024 * 1024;

    /**
     * 写入二进制内容(自动建父目录;限长)。
     *
     * <p>用于把远程资源(如 MCP 工具给出的图片 URL)落到工作区/整机文件系统,
     * 供用户在工作台「文件」里查看、整理、归档。与文本写入共用同一套路径解析
     * 与系统目录保护:相对路径=工作区内,绝对路径=整机(区外写由 HIGH 审批把门)。
     *
     * @param path    目标路径(相对=工作区内,绝对=整机)
     * @param bytes   文件字节
     * @return 实际写入的字节数
     */
    public long writeBinaryAny(String path, byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("内容为空");
        }
        if (bytes.length > MAX_BINARY_BYTES) {
            throw new IllegalArgumentException("文件超过 " + (MAX_BINARY_BYTES / 1024 / 1024)
                    + "MB 上限(当前 " + (bytes.length / 1024 / 1024) + "MB)");
        }
        Path file = resolveAny(path).path();
        guardSystemPath(file, "写入");
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("目标是目录,不能写入: " + file);
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            return bytes.length;
        } catch (IOException e) {
            throw new IllegalArgumentException("写入失败: " + e.getMessage());
        }
    }

    /** 目标文件是否已存在(避免覆盖用户文件前先确认)。 */
    public boolean existsAny(String path) {
        try {
            return Files.exists(resolveAny(path).path());
        } catch (Exception e) {
            return false;
        }
    }

    /** 目标是否为已存在的目录(import 目录语义兼容用,2026-09-18)。 */
    public boolean isDirectoryAny(String path) {
        try {
            return Files.isDirectory(resolveAny(path).path());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 流式写入任意路径(批量媒体拉取用,2026-09-17)。
     *
     * <p>与 {@link #writeBinaryAny} 的区别:内容从 {@link java.io.InputStream}
     * 边读边写盘(先写 {@code .part} 临时文件,成功后原子移动),全程不整读进
     * 内存——几百 MB 的视频用它才不会把堆打爆。路径解析与系统目录防呆同
     * writeBinaryAny(相对=工作区内,绝对=整机)。
     *
     * @param path     目标路径
     * @param in       内容流(调用方负责关闭)
     * @param maxBytes 单文件上限(超出即中断并清理 .part)
     * @return 实际写入字节数
     */
    public long writeStreamAny(String path, java.io.InputStream in, long maxBytes) {
        if (in == null) {
            throw new IllegalArgumentException("内容流为空");
        }
        Path file = resolveAny(path).path();
        guardSystemPath(file, "写入");
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("目标是目录,不能写入: " + file);
        }
        Path part = file.resolveSibling(file.getFileName() + ".part-" + System.nanoTime());
        try {
            Files.createDirectories(file.getParent());
            long total = 0;
            try (java.io.OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) >= 0) {
                    total += n;
                    if (total > maxBytes) {
                        throw new IllegalArgumentException("文件超过 " + (maxBytes / 1024 / 1024)
                                + "MB 上限,已中断下载");
                    }
                    out.write(buf, 0, n);
                }
            }
            // 原子移动(同目录内);平台不支持时退化为普通替换移动
            try {
                Files.move(part, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException amns) {
                Files.move(part, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            return total;
        } catch (IOException e) {
            deleteQuietly(part);
            throw new IllegalArgumentException("写入失败: " + e.getMessage());
        } catch (RuntimeException e) {
            deleteQuietly(part);
            throw e;
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException ignored) {
            // 清理失败不掩盖原始错误
        }
    }

    /**
     * 读取工作区文件的原始字节(图片等二进制预览用)。
     *
     * <p>文本读取(read)会拒绝二进制;图片导入工作区后,「文件」页里的
     * 工作区浏览器需要原始字节才能渲染缩略图。
     *
     * @param relative 工作区内相对路径
     * @return 文件字节
     */
    public byte[] readBytes(String relative) {
        Path file = resolveSafe(relative);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在: " + file);
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取失败: " + e.getMessage());
        }
    }

    /**
     * 读取任意文件的原始字节(agent 识图通道用;区外读取=LOW,直接放行)。
     *
     * <p>图片经文本工具读取必然失败(二进制 UTF-8 解码报 "Input length = 1"),
     * 因此识图必须走字节通道:读原始字节 → 作为图像附件喂给视觉模型。
     *
     * @param path 相对=工作区内,绝对=整机
     * @return 文件字节
     */
    public byte[] readBytesAny(String path) {
        Path file = resolveAny(path).path();
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在: " + file);
        }
        try {
            long size = Files.size(file);
            if (size > MAX_BINARY_BYTES) {
                throw new IllegalArgumentException("文件超过 " + (MAX_BINARY_BYTES / 1024 / 1024)
                        + "MB,拒绝整体读取");
            }
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取失败: " + e.getMessage());
        }
    }

    /**
     * 从扩展名推断图片 MIME(png/jpg/jpeg/gif/webp/bmp);非图片返回 {@code null}。
     * 供 agent 工具在文本读取前分流:图片走字节+图像附件,文本走 readAny。
     */
    public static String imageMime(String path) {
        if (path == null) {
            return null;
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return null;
        }
        String ext = path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            default -> null;
        };
    }

    /** 追加任意文件(agent 工具用)。 */
    public int appendAny(String path, String content) {
        return appendPath(resolveAny(path).path(), content);
    }

    private int appendPath(Path file, String content) {
        guardSystemPath(file, "写入");
        String body = content == null ? "" : content;
        if (body.length() > MAX_FILE_CHARS) {
            throw new IllegalArgumentException("内容超过 " + MAX_FILE_CHARS + " 字符上限");
        }
        long existing = Files.exists(file) ? fileSizeOrZero(file) : 0;
        if (existing + body.length() > MAX_FILE_CHARS) {
            throw new IllegalArgumentException("追加后超过 " + MAX_FILE_CHARS + " 字符上限,请改用 write 精简整文件");
        }
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, body, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return body.length();
        } catch (IOException e) {
            throw new IllegalArgumentException("追加失败: " + e.getMessage());
        }
    }

    /** 删除文件(拒绝目录与根)。 */
    public void delete(String relative) {
        deletePath(resolveSafe(relative));
    }

    /** 删除任意文件(agent 工具用;区外删由 CRITICAL 审批把门)。 */
    public void deleteAny(String path) {
        deletePath(resolveAny(path).path());
    }

    private void deletePath(Path file) {
        guardSystemPath(file, "删除");
        if (file.equals(root)) {
            throw new IllegalArgumentException("不能删除工作区根目录");
        }
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("这里是目录;为防误删,请逐个删除文件: " + file);
        }
        try {
            if (!Files.deleteIfExists(file)) {
                throw new IllegalArgumentException("文件不存在: " + file);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("删除失败: " + e.getMessage());
        }
    }

    /** 过滤 git 内部路径(工作区常作私有 git 仓库备份,agent 不应看到/写入 .git)。 */
    static boolean notGitInternal(Path p) {
        String s = p.toString().replace('\\', '/');
        return !s.contains("/.git/") && !s.endsWith("/.git");
    }

    /**
     * 移动文件或目录(agent 工具用;区外写由审批把门)。
     *
     * <p>为什么需要:此前只有 write/append/delete,整理类任务(把导出的
     * 文件夹搬进 photos/、重命名目录)只能上 run_command PowerShell——
     * 实测一轮「搞个文件夹放进去」跑了 7 个 run_command,一半是 Move-Item
     * 和它的验证。文件系统的基础动作应该在文件工具里,不是借终端。
     *
     * @param source      源路径(相对=工作区内,绝对=整机)
     * @param destination 目标路径;已存在时拒绝(移动是整理不是覆盖)
     * @return 人类可读摘要(源 → 目标;目录含条目数)
     */
    public String moveAny(String source, String destination) {
        return moveOrCopy(source, destination, false);
    }

    /**
     * 复制文件或目录(agent 工具用)。
     *
     * <p>语义:文件→目标可以是文件路径或目录;目录→递归复制。
     * 目标已存在时拒绝(避免意外覆盖;要覆盖先删)。
     */
    public String copyAny(String source, String destination) {
        return moveOrCopy(source, destination, true);
    }

    private String moveOrCopy(String source, String destination, boolean copy) {
        if (source == null || source.isBlank() || destination == null || destination.isBlank()) {
            throw new IllegalArgumentException("需要 source 与 destination 两个路径参数");
        }
        // 通配符(如 photos/week-videos/VID_20260916_*.mp4):在父目录内展开后逐个操作。
        // 为什么需要:从混合目录里挑一批文件(按文件名日期前缀)是整理任务的高频形态,
        // 没有通配符就得逐个文件调 N 次工具(实测「挑出某天的 28 段视频」场景)。
        if (source.contains("*") || source.contains("?")) {
            return moveOrCopyGlob(source, destination, copy);
        }
        Path src = resolveAny(source).path();
        Path dst = resolveAny(destination).path();
        guardSystemPath(dst, copy ? "复制" : "移动");
        guardSystemPath(src, copy ? "读取" : "移动");
        if (!Files.exists(src)) {
            throw new IllegalArgumentException("源不存在: " + src);
        }
        if (src.equals(root)) {
            throw new IllegalArgumentException("不能操作工作区根目录");
        }
        // 目标在源目录内部:移动/复制进自己会无限递归,直接拒绝
        if (Files.isDirectory(src) && dst.startsWith(src)) {
            throw new IllegalArgumentException("目标不能位于源目录内部: " + dst);
        }
        boolean srcIsDir = Files.isDirectory(src);
        // 目标是已存在目录 → 追加源文件名(移动/复制进目录的自然语义)
        if (Files.isDirectory(dst)) {
            dst = dst.resolve(src.getFileName());
        }
        if (Files.exists(dst)) {
            throw new IllegalArgumentException("目标已存在(移动/复制不覆盖): " + dst
                    + " —— 如需替换请先删除目标");
        }
        try {
            Files.createDirectories(dst.getParent());
            if (copy) {
                if (srcIsDir) {
                    copyDirRecursive(src, dst);
                } else {
                    Files.copy(src, dst);
                }
            } else {
                Files.move(src, dst);
            }
            long entries = srcIsDir ? countEntries(dst) : 0;
            String what = srcIsDir ? "目录(" + entries + " 个条目)" : FileToolClient.formatSize(fileSizeOrZero(dst));
            return "已" + (copy ? "复制" : "移动") + ": " + displayPath(src) + " → " + displayPath(dst) + " [" + what + "]";
        } catch (IOException e) {
            throw new IllegalArgumentException((copy ? "复制" : "移动") + "失败: " + e.getMessage());
        }
    }

    /**
     * 通配符形态的移动/复制:pattern 在父目录内展开(仅 depth 1),
     * 匹配到的每个条目逐个移入/复制到目标目录(目标必须不存在或为目录)。
     */
    private String moveOrCopyGlob(String pattern, String destination, boolean copy) {
        String cleaned = pattern.trim().replace('\\', '/');
        int slash = cleaned.lastIndexOf('/');
        String parentPart = slash < 0 ? "" : cleaned.substring(0, slash);
        String nameGlob = slash < 0 ? cleaned : cleaned.substring(slash + 1);
        Path parent = parentPart.isBlank() ? root : resolveAny(parentPart).path();
        if (!Files.isDirectory(parent)) {
            throw new IllegalArgumentException("通配符的父目录不存在: " + parent);
        }
        Path dstDir = resolveAny(destination).path();
        guardSystemPath(dstDir, copy ? "复制" : "移动");
        if (Files.exists(dstDir) && !Files.isDirectory(dstDir)) {
            throw new IllegalArgumentException("通配符移动/复制的目标必须是目录: " + dstDir);
        }
        java.nio.file.PathMatcher matcher;
        try {
            matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + nameGlob);
        } catch (Exception e) {
            throw new IllegalArgumentException("通配符不合法: " + nameGlob);
        }
        List<Path> matched = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(parent, 1)) {
            stream.filter(p -> !p.equals(parent))
                    .filter(AgentWorkspaceService::notGitInternal)
                    .filter(p -> matcher.matches(p.getFileName()))
                    .forEach(matched::add);
        } catch (IOException e) {
            throw new IllegalArgumentException("目录扫描失败: " + e.getMessage());
        }
        if (matched.isEmpty()) {
            throw new IllegalArgumentException("没有匹配「" + pattern + "」的文件或目录(父目录: "
                    + displayPath(parent) + ")");
        }
        try {
            Files.createDirectories(dstDir);
            int moved = 0;
            long bytes = 0;
            for (Path p : matched) {
                Path target = dstDir.resolve(p.getFileName());
                if (Files.exists(target)) {
                    throw new IllegalArgumentException("目标已存在,已中止(前面 " + moved
                            + " 个已处理): " + displayPath(target));
                }
                if (copy) {
                    if (Files.isDirectory(p)) {
                        copyDirRecursive(p, target);
                    } else {
                        Files.copy(p, target);
                    }
                    bytes += Files.isRegularFile(p) ? fileSizeOrZero(p) : 0;
                } else {
                    Files.move(p, target);
                }
                moved++;
            }
            return "已" + (copy ? "复制" : "移动") + " " + moved + " 项(匹配「" + pattern + "」)"
                    + (bytes > 0 ? ",合计 " + FileToolClient.formatSize(bytes) : "")
                    + " → " + displayPath(dstDir);
        } catch (IOException e) {
            throw new IllegalArgumentException((copy ? "复制" : "移动") + "失败: " + e.getMessage());
        }
    }

    /**
     * 创建目录(含父级;已存在时为幂等成功)。区外由审批把门。
     *
     * @param path 目标目录路径
     * @return 人类可读摘要
     */
    public String mkdirAny(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("需要 path 参数(要创建的目录路径)");
        }
        Path dir = resolveAny(path).path();
        guardSystemPath(dir, "创建");
        try {
            boolean existed = Files.isDirectory(dir);
            Files.createDirectories(dir);
            return existed ? "目录已存在: " + displayPath(dir) : "已创建目录: " + displayPath(dir);
        } catch (IOException e) {
            throw new IllegalArgumentException("创建目录失败: " + e.getMessage());
        }
    }

    /** 递归复制目录。 */
    private static void copyDirRecursive(Path src, Path dst) throws IOException {
        try (Stream<Path> stream = Files.walk(src)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                Path target = dst.resolve(src.relativize(p));
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }

    /** 统计目录内文件数(递归,上限计数防超大树卡顿)。 */
    private static long countEntries(Path dir) {
        try (Stream<Path> stream = Files.walk(dir)) {
            return stream.filter(Files::isRegularFile).limit(100_000).count();
        } catch (IOException e) {
            return 0;
        }
    }

    private long fileSizeOrZero(Path file) {
        try {
            return Files.size(file);
        } catch (IOException e) {
            return 0;
        }
    }

    /** 今日日记路径(相对),供 agent 追加日常笔记。 */
    public String todayDailyPath() {
        return "memory/" + LocalDate.now() + ".md";
    }

    // ---------- 引导注入 ----------

    /** 单个引导文件的注入信息(供「注入上下文」步骤下发:前端展示真实注入清单+正文)。 */
    public record BootstrapFileInfo(String path, int bytes, boolean truncated, boolean missing, String content) {
    }

    /** 引导注入结果:模型可见文本 + 逐文件元数据 + 日记清单(不注入正文)。 */
    public record BootstrapResult(String text, List<BootstrapFileInfo> files, List<String> dailyNotes) {
    }

    /**
     * 组装系统提示的工作区块(注入位置:harness 基础提示之后、RAG 片段之前),
     * 同时返回逐文件元数据(前端「加载长期记忆」步骤展开可见,dsh 的注入可见性模式)。
     *
     * <p>结构对齐 Hermes 三层提示词装配:身份(SOUL.md,agent 可自行演化) →
     * 使用约定(AGENTS.md) → 记忆快照(USER.md/MEMORY.md);每层逐文件+总量双预算
     * 截断、缺失占位;memory/ 日记只列清单不注入正文(按需 read)。
     * 文件修改只影响后续会话(会话内快照不变,对齐 Hermes 的缓存语义)。
     *
     * @return 注入结果;工作区不可用时返回 null(静默降级)
     */
    public BootstrapResult bootstrap() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("## 你的工作区"
                    + "\n")
                    .append("工作区根: ").append(root).append("(文件系统上的记忆载体,用 manage_workspace 读写)")
                    .append("\n")
                    .append("相对路径=工作区内;绝对路径(如 D:/projects/...)可访问整机——"
                            + "帮用户查看/整理项目文件时直接用,写/删区外文件时会请求用户确认。")
                    .append("\n");

            List<BootstrapFileInfo> files = new ArrayList<>();
            int[] budget = {BOOTSTRAP_TOTAL_CHARS};
            sb.append("\n").append("### 身份(SOUL.md——你的可演化人格:想调整自己的语气/边界,直接编辑它)").append("\n");
            appendBootstrapSection(sb, IDENTITY_FILES, budget, files);
            sb.append("\n").append("### 使用约定(AGENTS.md)").append("\n");
            appendBootstrapSection(sb, CONTEXT_FILES, budget, files);
            sb.append("\n").append("### 记忆快照(USER.md 偏好 / MEMORY.md 耐久事实;每轮注入,保持精简)").append("\n");
            appendBootstrapSection(sb, VOLATILE_FILES, budget, files);

            List<String> dailyNotes = appendDailyListing(sb);
            sb.append("\n")
                    .append("记忆维护:稳定偏好→USER.md;耐久事实/决定→MEMORY.md;日常观察/进度→memory/今日.md"
                            + "(按需读取,不注入)。用户说「记住…」时必须落盘,不能只口头答应。")
                    .append("\n");
            return new BootstrapResult(sb.toString(), files, dailyNotes);
        } catch (Exception e) {
            log.warn("workspace bootstrap inject failed (ignored): {}", e.getMessage());
            return null;
        }
    }

    /** 兼容入口:只要文本(bootstrap() 的薄包装)。 */
    public String bootstrapPrompt() {
        BootstrapResult result = bootstrap();
        return result == null ? null : result.text();
    }

    /** 渲染一组引导文件(共享总量预算;逐文件截断+缺失占位),并记录逐文件元数据。 */
    private void appendBootstrapSection(StringBuilder sb, java.util.List<String> names, int[] budget,
                                        List<BootstrapFileInfo> files) {
        for (String name : names) {
            String content = readBootstrap(name);
            if (budget[0] <= 0) {
                sb.append("(预算耗尽,").append(name).append("已省略;需要时用 manage_workspace read 读取)").append("\n");
                files.add(new BootstrapFileInfo(name, 0, true, content == null, null));
                continue;
            }
            boolean missing = content == null;
            String body = missing ? "(文件缺失)" : content;
            boolean truncated = false;
            if (body.length() > BOOTSTRAP_PER_FILE_CHARS) {
                body = body.substring(0, BOOTSTRAP_PER_FILE_CHARS) + "\n" + "…(过长截断)";
                truncated = true;
            }
            if (body.length() > budget[0]) {
                body = body.substring(0, Math.max(0, budget[0])) + "\n" + "…(预算截断)";
                truncated = true;
            }
            budget[0] -= body.length();
            sb.append("\n").append("#### ").append(name).append("\n").append(body).append("\n");
            // bytes = 实际注入的 UTF-8 字节数(前端按 B/KB 展示;中文 3 字节/字符,
            // 不能用 String.length——那是字符数,会低估展示值)
            int byteLen = missing ? 0 : body.getBytes(StandardCharsets.UTF_8).length;
            // content = 模型实际读到的注入正文(前端展开可见;dsh 的 instructions 形态:
            // 文件清单 + 原文,保留 framing——展示的就是模型读到的,不做二次加工)
            files.add(new BootstrapFileInfo(name, byteLen, truncated, missing, missing ? null : body));
        }
    }

    private String readBootstrap(String name) {
        try {
            Path file = root.resolve(name);
            if (!Files.isRegularFile(file)) {
                return null;
            }
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    /** memory/ 最近日记:只列文件名与大小(正文按需 read,不占每轮预算)。 */
    /** 追加 memory/ 日记清单(只列文件名,不注入正文);返回清单供元数据上报。 */
    private List<String> appendDailyListing(StringBuilder sb) {
        Path memoryDir = root.resolve("memory");
        if (!Files.isDirectory(memoryDir)) {
            return List.of();
        }
        try (Stream<Path> stream = Files.list(memoryDir)) {
            List<Path> daily = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .limit(7)
                    .toList();
            List<String> names = new ArrayList<>();
            if (!daily.isEmpty()) {
                sb.append("\n## memory/ 最近日记(不自动注入,需要时 read)\n");
                for (Path p : daily) {
                    sb.append("- memory/").append(p.getFileName()).append(" (")
                            .append(Files.size(p)).append(" B)\n");
                    names.add("memory/" + p.getFileName());
                }
            }
            return names;
        } catch (IOException ignored) {
            return List.of();
        }
    }

    /** 供健康检查/前端展示:工作区概览(文件数、总字节;不含 .git 等隐藏目录)。 */
    public WorkspaceStats stats() {
        long files = 0;
        long bytes = 0;
        try (Stream<Path> stream = Files.walk(root, 4)) {
            for (Path p : stream.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains("/.git/") && !p.toString().contains("\\.git\\"))
                    .toList()) {
                files++;
                bytes += Files.size(p);
            }
        } catch (IOException ignored) {
        }
        return new WorkspaceStats(root.toString(), files, bytes, LocalDateTime.now());
    }

    public record WorkspaceStats(String root, long files, long bytes, LocalDateTime checkedAt) {
    }
}
