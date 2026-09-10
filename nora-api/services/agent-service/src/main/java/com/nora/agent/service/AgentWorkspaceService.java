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
 * <p>安全:所有路径解析锁定在根目录内(拒绝绝对路径、{@code ..}、符号链接逃逸);
 * 读写有大小上限;文件操作由 {@code manage_workspace} 工具暴露。
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
    /** 引导文件(自动注入,顺序固定)。 */
    static final List<String> BOOTSTRAP_FILES = List.of("AGENTS.md", "SOUL.md", "USER.md", "MEMORY.md");

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
            seedIfMissing("AGENTS.md", """
                    # 工作台助手行为指令

                    ## 记忆维护(你的工作区就是你的记忆)
                    - 学到**稳定偏好/画像事实**(用户是谁、喜欢什么风格):更新 `USER.md`(祈使句、简明)。
                    - 学到**耐久事实/决定**(项目约定、重要结论):更新 `MEMORY.md`,保持精简(它每轮都注入)。
                    - 日常观察、进度、临时上下文:追加到 `memory/YYYY-MM-DD.md`(按需读取,不注入)。
                    - 用户说「记住…」时必须落盘,不能只口头答应;写入前先 read 相关文件避免覆盖。
                    - 优先追加/最小编辑,避免整文件重写;删除记忆前先向用户确认。

                    ## 回答规则
                    - 中文、结论前置、Markdown 紧凑。
                    - 涉及数据库统计先用 execute_sql 查真实数据;诊断服务异常先 read_service_logs。
                    - 工具报错如实说明,不编造数据。
                    """);
            seedIfMissing("SOUL.md", """
                    # 人设

                    你是 Nora——用户的个人工作台助手。风格:直接、克制、可信。
                    不谄媚,不空话;不确定就说不确定。边界:涉及不可逆操作先征求用户同意。
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

    // ---------- 路径安全 ----------

    /**
     * 解析相对路径为工作区内的绝对路径;越界(绝对路径/.. /符号链接逃逸)抛异常。
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

    // ---------- 文件操作 ----------

    public record FileEntry(String path, boolean directory, long size, String modifiedAt) {
    }

    /** 列举目录(depth 1,JSON 友好):相对路径、大小、修改时间。 */
    public List<FileEntry> list(String relativeDir) {
        Path dir = (relativeDir == null || relativeDir.isBlank()) ? root : resolveSafe(relativeDir);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("不是目录: " + relativeDir);
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
                                    root.relativize(p).toString().replace('\\', '/'),
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
        Path file = resolveSafe(relative);
        if (!Files.isRegularFile(file)) {
            throw new IllegalArgumentException("文件不存在: " + relative);
        }
        try {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.indexOf('\u0000') >= 0) {
                throw new IllegalArgumentException("拒绝读取二进制文件: " + relative);
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
        Path file = resolveSafe(relative);
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("目标是目录,不能写入: " + relative);
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
        Path file = resolveSafe(relative);
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
        Path file = resolveSafe(relative);
        if (file.equals(root)) {
            throw new IllegalArgumentException("不能删除工作区根目录");
        }
        if (Files.isDirectory(file)) {
            throw new IllegalArgumentException("这里是目录;为防误删,请逐个删除文件: " + relative);
        }
        try {
            if (!Files.deleteIfExists(file)) {
                throw new IllegalArgumentException("文件不存在: " + relative);
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

    /**
     * 组装每轮注入的系统提示块:四个引导文件(逐文件+总量预算、截断标记、缺失占位)
     * + memory/ 最近日记清单(只列名不注入正文,agent 需要时自行 read)。
     *
     * @return 注入文本;工作区不可用时返回 null(静默降级)
     */
    public String bootstrapPrompt() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("以下是你的工作区(文件系统上的私有空间,用 manage_workspace 工具读写;工作区内用相对路径):\n");
            sb.append("工作区根: ").append(root).append("\n");
            int total = 0;
            for (String name : BOOTSTRAP_FILES) {
                String content = readBootstrap(name);
                if (total >= BOOTSTRAP_TOTAL_CHARS) {
                    sb.append("\n## ").append(name).append("\n(超出注入预算,已省略;需要时用 manage_workspace read 读取)\n");
                    continue;
                }
                String body = content == null ? "(文件缺失)" : content;
                if (body.length() > BOOTSTRAP_PER_FILE_CHARS) {
                    body = body.substring(0, BOOTSTRAP_PER_FILE_CHARS) + "\n…(过长截断)";
                }
                int remain = BOOTSTRAP_TOTAL_CHARS - total;
                if (body.length() > remain) {
                    body = body.substring(0, Math.max(0, remain)) + "\n…(预算截断)";
                }
                total += body.length();
                sb.append("\n## ").append(name).append('\n').append(body).append('\n');
            }
            appendDailyListing(sb);
            sb.append("\n维护提醒:稳定偏好→USER.md,耐久事实→MEMORY.md,日常笔记→memory/今日.md;"
                    + "用户说「记住」时必须落盘。\n");
            return sb.toString();
        } catch (Exception e) {
            log.warn("workspace bootstrap inject failed (ignored): {}", e.getMessage());
            return null;
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
    private void appendDailyListing(StringBuilder sb) {
        Path memoryDir = root.resolve("memory");
        if (!Files.isDirectory(memoryDir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(memoryDir)) {
            List<Path> daily = stream
                    .filter(p -> p.getFileName().toString().endsWith(".md"))
                    .sorted(Comparator.comparing((Path p) -> p.getFileName().toString()).reversed())
                    .limit(7)
                    .toList();
            if (!daily.isEmpty()) {
                sb.append("\n## memory/ 最近日记(不自动注入,需要时 read)\n");
                for (Path p : daily) {
                    sb.append("- memory/").append(p.getFileName()).append(" (")
                            .append(Files.size(p)).append(" B)\n");
                }
            }
        } catch (IOException ignored) {
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
