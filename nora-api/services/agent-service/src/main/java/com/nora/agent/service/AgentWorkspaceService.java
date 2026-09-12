package com.nora.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

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

                    ## 记忆维护(工作区就是我的记忆)
                    - 学到**稳定偏好/画像事实**:更新 `USER.md`(祈使句、简明)。
                    - 学到**耐久事实/决定**(项目约定、重要结论):更新 `MEMORY.md`,保持精简(每轮注入)。
                    - 日常观察、进度、临时上下文:追加到 `memory/YYYY-MM-DD.md`(按需读,不注入)。
                    - 写入前先 read 相关文件避免覆盖;优先追加/最小编辑;删除记忆前向用户确认。

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

    public record FileEntry(String path, boolean directory, long size, String modifiedAt) {
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
                            entries.add(new FileEntry(
                                    displayPath(p),
                                    Files.isDirectory(p),
                                    Files.isDirectory(p) ? 0 : Files.size(p),
                                    Files.getLastModifiedTime(p).toInstant()
                                            .atZone(java.time.ZoneId.systemDefault())
                                            .toLocalDateTime()
                                            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))));
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException e) {
            throw new IllegalArgumentException("目录列举失败: " + e.getMessage());
        }
        return entries;
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
