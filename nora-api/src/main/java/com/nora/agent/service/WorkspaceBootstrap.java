package com.nora.agent.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工作区引导注入：把 SOUL / AGENTS / USER / MEMORY 装配成系统提示的工作区块。
 *
 * <p>从 {@link AgentWorkspaceService} 拆出：文件系统读写与路径安全仍由工作区负责，
 * 这里只决定「注入什么、注入多少、如何截断」——预算与截断规则集中在一处，
 * 改提示词结构不会碰到文件操作。
 */
@Component
class WorkspaceBootstrap {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceBootstrap.class);

    private final AgentWorkspaceService workspace;

    WorkspaceBootstrap(AgentWorkspaceService workspace) {
        this.workspace = workspace;
    }

    /** 单个引导文件的注入信息(供「注入上下文」步骤下发:前端展示真实注入清单+正文)。 */
    record BootstrapFileInfo(String path, int bytes, boolean truncated, boolean missing, String content) {
    }

    /** 引导注入结果:模型可见文本 + 逐文件元数据 + 日记清单(不注入正文)。 */
    record BootstrapResult(String text, List<BootstrapFileInfo> files, List<String> dailyNotes) {
    }

    /**
     * 组装系统提示的工作区块(注入位置:harness 基础提示之后、RAG 片段之前),
     * 同时返回逐文件元数据(前端「加载长期记忆」步骤展开可见,dsh 的注入可见性模式)。
     *
     * <p>结构对齐 Hermes 三层提示词装配:身份(SOUL.md,agent 可自行演化) →
     * 使用约定(AGENTS.md) → 记忆快照(USER.md/MEMORY.md);每层逐文件+总量双预算
     * 截断、缺失占位;memory/ 日记只列清单不注入正文(按需 read)。
     * 文件修改在下次用户请求装配上下文时生效,不修改正在执行的请求快照。
     *
     * @return 注入结果;工作区不可用时返回 null(静默降级)
     */
    BootstrapResult bootstrap() {
        return bootstrap(null);
    }

    BootstrapResult bootstrap(Path projectRoot) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("## 任务工作区与用户约定"
                    + "\n")
                    .append("工作区根: ").append(workspace.root()).append("(默认任务目录，存放任务文件、成果和历史证据)")
                    .append("\n")
                    .append("工作区不等同于登录用户主目录或宿主机。相对路径以此为基准；绝对路径访问当前执行环境可见的文件，"
                            + "仍受系统权限约束。按任务直接使用相关项目和已配置服务，审批由权限模式和工具请求决定。")
                    .append("\n");

            List<BootstrapFileInfo> files = new ArrayList<>();
            int[] budget = {AgentWorkspaceService.BOOTSTRAP_TOTAL_CHARS};
            sb.append("\n").append("### 身份(SOUL.md)").append("\n");
            appendBootstrapSection(sb, AgentWorkspaceService.IDENTITY_FILES, budget, files);
            sb.append("\n").append("### 使用约定(AGENTS.md)").append("\n");
            appendBootstrapSection(sb, AgentWorkspaceService.CONTEXT_FILES, budget, files);
            if (projectRoot != null) {
                sb.append("\n### 当前会话项目\n默认命令和文件目录: ").append(projectRoot)
                        .append("\n临时 cwd 只影响单条命令；项目绑定不会随 cd 改变。\n");
                var directories = new ArrayList<Path>();
                for (Path dir = projectRoot; dir != null; dir = dir.getParent()) directories.addFirst(dir);
                for (Path dir : directories) {
                    if (dir.equals(workspace.root())) continue;
                    Path rule = dir.resolve("AGENTS.override.md");
                    if (!Files.isRegularFile(rule)) rule = dir.resolve("AGENTS.md");
                    if (Files.isRegularFile(rule)) appendBootstrapSection(sb, List.of(rule.toString()), budget, files);
                }
            }
            sb.append("\n").append("### 记忆快照(USER.md 偏好 / MEMORY.md 耐久事实;每轮注入,保持精简)").append("\n");
            appendBootstrapSection(sb, AgentWorkspaceService.VOLATILE_FILES, budget, files);

            List<String> dailyNotes = appendDailyListing(sb);
            sb.append("\n")
                    .append("仅在用户要求记录时维护记忆:稳定偏好→USER.md;耐久事实/决定→MEMORY.md;日常观察/进度→memory/今日.md"
                            + "(按需读取,不注入)。运行时缺失、安装状态、网络可达性是临时状态,只记带日期的日记,"
                            + "不能写成长期能力限制;复用前以当前快照或工具结果核实。写入前读相关文件,优先 edit/append 保留已有内容。")
                    .append("\n");
            return new BootstrapResult(sb.toString(), files, dailyNotes);
        } catch (Exception e) {
            LOG.warn("workspace bootstrap inject failed (ignored): {}", e.getMessage());
            return null;
        }
    }

    /** 渲染一组引导文件(共享总量预算;逐文件截断+缺失占位),并记录逐文件元数据。 */
    private void appendBootstrapSection(StringBuilder sb, java.util.List<String> names, int[] budget,
                                        List<BootstrapFileInfo> files) {
        for (String name : names) {
            String content = workspace.readBootstrap(name);
            if (budget[0] <= 0) {
                sb.append("(预算耗尽,").append(name).append("已省略;需要时用 manage_workspace read 读取)").append("\n");
                files.add(new BootstrapFileInfo(name, 0, true, content == null, null));
                continue;
            }
            boolean missing = content == null;
            String body = missing ? "(文件缺失)" : content;
            boolean truncated = false;
            if (body.length() > AgentWorkspaceService.BOOTSTRAP_PER_FILE_CHARS) {
                body = body.substring(0, AgentWorkspaceService.BOOTSTRAP_PER_FILE_CHARS) + "\n" + "…(过长截断)";
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
            int byteLen = missing ? 0 : body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            // content = 模型实际读到的注入正文(前端展开可见;dsh 的 instructions 形态:
            // 文件清单 + 原文,保留 framing——展示的就是模型读到的,不做二次加工)
            files.add(new BootstrapFileInfo(name, byteLen, truncated, missing, missing ? null : body));
        }
    }

    private String readBootstrap(String name) {
        return workspace.readBootstrap(name);
    }

    /** memory/ 最近日记:只列文件名与大小(正文按需 read,不占每轮预算)。 */
    /** 追加 memory/ 日记清单(只列文件名,不注入正文);返回清单供元数据上报。 */
    private List<String> appendDailyListing(StringBuilder sb) {
        Path memoryDir = workspace.root().resolve("memory");
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
}
