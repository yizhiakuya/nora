package com.nora.rag.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

/**
 * 文档分块(阶段 B/D,2026-09-22)。
 *
 * <p>两种模式(方案 §5.2):
 * <ul>
 *   <li><b>plain</b>(默认):结构优先分段——沿 Markdown 标题、空行段落、
 *       句末标点切分,超过目标长度才按空白回退;比"纯 2000 字符滑动窗口"
 *       更少把标题与内容分离、把句子拦腰截断。</li>
 *   <li><b>parent_child</b>:章节(标题到下一个同级/更高级标题)作为父块,
 *       父块内再按 plain 规则切子块;匹配子块、返回父块(子块用于命中,
 *       父块提供完整上下文)。</li>
 * </ul>
 *
 * <p><b>可配置参数(阶段 D,Dify 同款):</b>{@link ChunkConfig} 携带
 * 分段长度/重叠/分隔符——每个文档的分段参数随版本保存(chunk_config),
 * 改参数即重新分段。默认 2000/200 保持既有行为。
 *
 * <p>为什么用字符而不是 token 计数:项目没有引入 tokenizer 依赖(嵌入与
 * LLM 各自的 tokenizer 不同),字符数对 CJK 是稳定的近似(中文≈1 字/token)。
 */
@Service
public class ChunkingService {

    /** 每块目标字符数(默认)。 */
    public static final int CHUNK_SIZE = 2000;
    /** 相邻块共享的字符数(仅 plain 模式滑动窗口时使用;默认)。 */
    public static final int OVERLAP = 200;
    /** 父块(章节)超过此长度时,父上下文返回时按段落裁选。 */
    static final int PARENT_MAX_CHARS = 6_000;

    /** 分段长度下限/上限(Dify 类似约束:过小碎、过大失去召回精度)。 */
    public static final int MIN_CHUNK_SIZE = 200;
    public static final int MAX_CHUNK_SIZE = 8000;
    public static final int MAX_OVERLAP = 1000;

    /** 分段模式常量。 */
    public static final String MODE_PLAIN = "plain";
    public static final String MODE_PARENT_CHILD = "parent_child";

    /**
     * 分段配置(Dify「分段设置」的等价物)。
     *
     * @param mode        plain / parent_child
     * @param chunkSize   每块目标字符数(默认 2000;范围 200-8000)
     * @param overlap     相邻块共享字符数(默认 200;范围 0-1000)
     * @param separator   自定义分隔符(可选;非空时优先按它切段——Dify 的
     *                    「自定义分隔符」;空 = 自动结构优先)
     */
    public record ChunkConfig(String mode, Integer chunkSize, Integer overlap, String separator) {
        public static final ChunkConfig DEFAULT = new ChunkConfig(MODE_PLAIN, CHUNK_SIZE, OVERLAP, null);

        /** 规范化:缺省回默认、越界截断、overlap 不得 ≥ chunkSize。 */
        public ChunkConfig normalized() {
            String m = mode == null || mode.isBlank() ? MODE_PLAIN : mode;
            int cs = chunkSize == null ? CHUNK_SIZE : Math.max(MIN_CHUNK_SIZE, Math.min(MAX_CHUNK_SIZE, chunkSize));
            int ov = overlap == null ? OVERLAP : Math.max(0, Math.min(MAX_OVERLAP, overlap));
            if (ov >= cs) {
                ov = Math.min(OVERLAP, Math.max(0, cs / 4));
            }
            String sep = separator == null || separator.isBlank() ? null : separator;
            return new ChunkConfig(m, cs, ov, sep);
        }
    }

    /** 一个子块及其所属父块(父子模式;plain 模式下 parent 为 null)。 */
    public record ChunkPiece(String content, Integer parentIndex, String parentContent) {
    }

    /**
     * 把文本切成带重叠的块(兼容入口,plain 默认参数)。
     *
     * @param text 原始文档文本
     * @return 按文档序的块;空白输入为空列表
     */
    public List<String> chunk(String text) {
        return chunk(text, MODE_PLAIN).stream().map(ChunkPiece::content).toList();
    }

    /**
     * 按模式切块(阶段 B;默认参数)。
     *
     * @param text 原始文档文本
     * @param mode plain / parent_child
     * @return 块序列(父子模式下每块带父块信息);空白输入为空列表
     */
    public List<ChunkPiece> chunk(String text, String mode) {
        return chunk(text, new ChunkConfig(mode, null, null, null));
    }

    /**
     * 按配置切块(阶段 D,Dify 同款可调参数)。
     *
     * @param text   原始文档文本
     * @param config 分段配置(null = 默认);越界参数自动规范化
     * @return 块序列;空白输入为空列表
     */
    public List<ChunkPiece> chunk(String text, ChunkConfig config) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        ChunkConfig cfg = config == null ? ChunkConfig.DEFAULT : config.normalized();
        String normalized = text.strip();
        if (MODE_PARENT_CHILD.equalsIgnoreCase(cfg.mode())) {
            return chunkParentChild(normalized, cfg);
        }
        if (cfg.separator() != null) {
            // 自定义分隔符优先(Dify「自定义分隔符」):按分隔符切段,段内超长再走结构切分
            return splitBySeparator(normalized, cfg).stream()
                    .map(c -> new ChunkPiece(c, null, null))
                    .toList();
        }
        return structureAwareSplit(normalized, cfg).stream()
                .map(c -> new ChunkPiece(c, null, null))
                .toList();
    }

    /** 按自定义分隔符切段;每段超长时在段内继续结构切分。 */
    List<String> splitBySeparator(String text, ChunkConfig cfg) {
        List<String> out = new ArrayList<>();
        String[] parts = text.split(Pattern.quote(cfg.separator()), -1);
        for (String part : parts) {
            String piece = part.strip();
            if (piece.isEmpty()) {
                continue;
            }
            if (piece.length() <= cfg.chunkSize()) {
                out.add(piece);
            } else {
                out.addAll(splitLongSection(piece, cfg));
            }
        }
        return out;
    }

    // ---------- plain:结构优先分段 ----------

    /**
     * 结构优先切分:先按 Markdown 标题分段(标题开启新段,不把标题与正文
     * 分离),段内超长再按空行/句边界/空白回退。产出块 ≤ {@link #CHUNK_SIZE}。
     */
    List<String> structureAwareSplit(String text, ChunkConfig cfg) {
        List<String> sections = splitByHeadings(text);
        List<String> out = new ArrayList<>();
        for (String section : sections) {
            if (section.length() <= cfg.chunkSize()) {
                if (!section.isBlank()) {
                    out.add(section.strip());
                }
                continue;
            }
            out.addAll(splitLongSection(section, cfg));
        }
        return out;
    }

    /** 按 Markdown 标题行切段(标题行保留在所属段首)。无标题时整体为一段。 */
    List<String> splitByHeadings(String text) {
        List<String> sections = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            boolean heading = HEADING.matcher(line).matches();
            if (heading && current.length() > 0) {
                sections.add(current.toString());
                current.setLength(0);
            }
            current.append(line).append('\n');
        }
        if (current.length() > 0) {
            sections.add(current.toString());
        }
        return sections;
    }

    private static final Pattern HEADING = Pattern.compile("^#{1,6}\\s+.*$");

    /**
     * 超长段内切分:优先空行(段落边界)→ 句末标点 → 空白 → 硬切。
     * 与旧实现不同:非首块不强行重叠(结构边界本身已保语义连续性),
     * 仅当找不到任何自然边界时才用滑动窗口兜底。
     */
    List<String> splitLongSection(String section, ChunkConfig cfg) {
        List<String> paragraphs = splitParagraphs(section);
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            if (current.length() > 0 && current.length() + para.length() > cfg.chunkSize()) {
                out.add(current.toString().strip());
                current.setLength(0);
            }
            if (para.length() > cfg.chunkSize()) {
                // 单段超长:按句子边界切
                for (String piece : splitBySentences(para)) {
                    if (current.length() > 0 && current.length() + piece.length() > cfg.chunkSize()) {
                        out.add(current.toString().strip());
                        current.setLength(0);
                    }
                    if (piece.length() > cfg.chunkSize()) {
                        // 句子也超长(无标点长文):滑动窗口兜底
                        if (current.length() > 0) {
                            out.add(current.toString().strip());
                            current.setLength(0);
                        }
                        out.addAll(slidingWindow(piece, cfg));
                    } else {
                        current.append(piece);
                    }
                }
            } else {
                current.append(para);
            }
        }
        if (current.length() > 0 && !current.toString().isBlank()) {
            out.add(current.toString().strip());
        }
        return out;
    }

    /** 按空行切段落(保留段落内换行)。标题段不单独成段——与下一段合并。 */
    private List<String> splitParagraphs(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) {
                // 标题行(如 "## 第一节")不因空行 flush:与正文合并成一段,
                // 否则标题单独成块(实测:首段只有 6 字符的 "# 标题",语义破碎)
                if (current.length() > 0 && !isHeadingOnly(current)) {
                    out.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(line).append('\n');
            }
        }
        if (current.length() > 0) {
            out.add(current.toString());
        }
        return out;
    }

    /** 当前缓冲是否只有标题行(单行且以 # 开头)。 */
    private static boolean isHeadingOnly(StringBuilder buf) {
        String s = buf.toString().strip();
        return !s.contains("\n") && HEADING.matcher(s).matches();
    }

    /** 按句末标点切分(中英)。 */
    private List<String> splitBySentences(String text) {
        List<String> out = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '；'
                    || (c == '.' && i + 1 < text.length() && (text.charAt(i + 1) == ' ' || text.charAt(i + 1) == '\n'))
                    || c == '\n') {
                out.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            out.add(text.substring(start));
        }
        return out;
    }

    /** 无任何自然边界的超长文本:按 CHUNK_SIZE-OVERLAP 滑动窗口兜底。 */
    private List<String> slidingWindow(String text, ChunkConfig cfg) {
        List<String> out = new ArrayList<>();
        int step = Math.max(1, cfg.chunkSize() - cfg.overlap());
        for (int start = 0; start < text.length(); start += step) {
            int end = Math.min(start + cfg.chunkSize(), text.length());
            String piece = text.substring(start, end).strip();
            if (!piece.isEmpty()) {
                out.add(piece);
            }
            if (end >= text.length()) {
                break;
            }
        }
        return out;
    }

    // ---------- parent_child:章节父块 + 子块 ----------

    /**
     * 父子模式:每个标题章节(或首段)是父块;父块内用 plain 规则切子块。
     *
     * <p>父块超 {@link #PARENT_MAX_CHARS} 时返回时按段落裁选(注入侧负责),
     * 这里原样保留完整父块(用户可能想看到全章节)。
     */
    List<ChunkPiece> chunkParentChild(String text, ChunkConfig cfg) {
        List<String> sections = splitByHeadings(text);
        List<ChunkPiece> out = new ArrayList<>();
        int parentIndex = 0;
        for (String section : sections) {
            String parentContent = section.strip();
            if (parentContent.isEmpty()) {
                continue;
            }
            List<String> children = parentContent.length() <= cfg.chunkSize()
                    ? List.of(parentContent)
                    : splitLongSection(parentContent, cfg);
            for (String child : children) {
                out.add(new ChunkPiece(child, parentIndex, parentContent));
            }
            parentIndex++;
        }
        return out;
    }
}
