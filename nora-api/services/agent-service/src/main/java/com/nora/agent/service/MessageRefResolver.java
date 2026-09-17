package com.nora.agent.service;

import com.nora.agent.dto.CitationDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 消息引用解析与注入(2026-09-17,对话框 📎/📄/@ 按钮的后端侧):
 * 用户消息尾部带引用行(前端 chatRefs.formatChatRefs 生成)——
 * <pre>
 * [引用文件] 报表.xlsx (file_id=12, 2.3 MB) —— 内容已随消息提供;…
 * [引用知识库] 调优手册 (doc_id=3) —— 内容已注入上下文,回答时优先参考
 * </pre>
 * 本类解析这些行,把**真实内容**转成 CitationDto 注入检索结果头部
 * (排在语义检索命中之前,保证「优先参考」名副其实):
 * <ul>
 *   <li>file:调 file-service 预览(文本截断 8K/文件),失败降级为提示行;</li>
 *   <li>doc:调 rag-service 文档 chunks(总量 12K 预算)。</li>
 * </ul>
 * 注入失败永不阻断对话(引用是增强项)。
 */
class MessageRefResolver {

    private static final Logger log = LoggerFactory.getLogger(MessageRefResolver.class);

    /** 单文件注入上限(字符);完整原文可让模型改用 read_file 工具。 */
    private static final int FILE_INJECT_CHARS = 8_000;
    /** 单文档多 chunk 注入总量上限(字符)。 */
    private static final int DOC_INJECT_CHARS = 12_000;

    private static final Pattern FILE_REF =
            Pattern.compile("^\\[引用文件\\]\\s*(.+?)\\s*\\(file_id=(\\d+)[^)]*\\)");
    private static final Pattern DOC_REF =
            Pattern.compile("^\\[引用知识库\\]\\s*(.+?)\\s*\\(doc_id=(\\d+)\\)");

    private final FileToolClient fileToolClient;
    private final RagRetrievalClient ragRetrievalClient;

    MessageRefResolver(FileToolClient fileToolClient, RagRetrievalClient ragRetrievalClient) {
        this.fileToolClient = fileToolClient;
        this.ragRetrievalClient = ragRetrievalClient;
    }

    /** 一条待注入引用(file_id 或 doc_id)。 */
    record Ref(String kind, long id, String name) {
    }

    /** 解析消息中的引用行;无引用返回空列表。 */
    static List<Ref> parse(String message) {
        List<Ref> out = new ArrayList<>();
        if (message == null) {
            return out;
        }
        for (String raw : message.split("\n")) {
            String line = raw.trim();
            Matcher m = FILE_REF.matcher(line);
            if (m.find()) {
                out.add(new Ref("file", Long.parseLong(m.group(2)), m.group(1)));
                continue;
            }
            m = DOC_REF.matcher(line);
            if (m.find()) {
                out.add(new Ref("doc", Long.parseLong(m.group(2)), m.group(1)));
            }
        }
        return out;
    }

    /**
     * 解析并注入引用内容:返回的 citations 由调用方拼在语义检索结果之前。
     * 任一引用失败仅记日志,不抛错。
     */
    List<CitationDto> resolve(List<Ref> refs) {
        List<CitationDto> out = new ArrayList<>();
        for (Ref ref : refs) {
            try {
                if ("file".equals(ref.kind())) {
                    out.addAll(resolveFile(ref));
                } else {
                    out.addAll(resolveDoc(ref));
                }
            } catch (Exception e) {
                log.warn("message ref resolve failed ({} id={}): {}", ref.kind(), ref.id(), e.getMessage());
            }
        }
        return out;
    }

    private List<CitationDto> resolveFile(Ref ref) {
        FileToolClient.PreviewInfo info = fileToolClient.previewInfo(ref.id());
        if (info.failed()) {
            return List.of(new CitationDto(ref.id(), ref.name(), "file", 0, 1.0,
                    "(引用文件读取失败: " + info.error() + ")"));
        }
        String text = info.text() == null ? "" : info.text();
        String content;
        if (text.isBlank()) {
            content = "(该文件没有可提取的文本内容,可能是二进制/图片;如需查看请让用户改用支持识图的模型)";
        } else if (text.length() > FILE_INJECT_CHARS) {
            content = text.substring(0, FILE_INJECT_CHARS)
                    + "\n…[已截断,共 " + text.length() + " 字符;完整原文可用 read_file 工具读取 file_id=" + ref.id() + "]";
        } else {
            content = text;
        }
        return List.of(new CitationDto(ref.id(), ref.name(), "file", 0, 1.0,
                "【用户引用的文件内容】\n" + content));
    }

    private List<CitationDto> resolveDoc(Ref ref) {
        RagRetrievalClient.DocChunks detail = ragRetrievalClient.docChunks(ref.id());
        if (detail == null) {
            return List.of(new CitationDto(ref.id(), ref.name(), "file", 0, 1.0,
                    "(引用知识库文档读取失败:文档不存在或知识库服务不可用)"));
        }
        List<CitationDto> out = new ArrayList<>();
        int budget = DOC_INJECT_CHARS;
        for (RagRetrievalClient.DocChunk chunk : detail.chunks()) {
            if (budget <= 0) {
                break;
            }
            String body = chunk.content().length() <= budget
                    ? chunk.content() : chunk.content().substring(0, budget);
            budget -= body.length();
            out.add(new CitationDto(ref.id(), detail.docName(), "file", chunk.chunkIndex(), 1.0,
                    "【用户引用的知识库文档】\n" + body));
        }
        if (out.isEmpty()) {
            out.add(new CitationDto(ref.id(), detail.docName(), "file", 0, 1.0,
                    "(引用文档没有可注入的文本内容)"));
        }
        return out;
    }
}
