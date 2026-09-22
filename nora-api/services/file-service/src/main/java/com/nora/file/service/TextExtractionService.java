package com.nora.file.service;

import java.io.IOException;
import java.io.InputStream;

import org.apache.tika.exception.TikaException;
import org.apache.tika.exception.WriteLimitReachedException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.WriteOutContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

/**
 * 基于 Apache Tika 的文本提取。从内容检测 MIME 类型(文件名作提示)并提取
 * 纯文本,上限 {@link #MAX_TEXT_LENGTH} 字符。
 *
 * <p><b>解析诊断(2026-09-22,知识库优化阶段 A):</b>此前所有失败路径都返回
 * 空串——用户无法区分「文件本来没有文字」「解析器坏了」「内容超限被截断」
 * 「需要 OCR」。现在返回 {@link ExtractionResult},携带状态与可读原因:
 * <ul>
 *   <li>{@code ok} — 正常提取(可能带 warning,如超限截断);</li>
 *   <li>{@code empty} — 没有可提取的文本(空白/纯二进制;扫描件也走这里,
 *       提示可能是图片型文档);</li>
 *   <li>{@code truncated} — 达到 50 万字符上限,文本已截断;</li>
 *   <li>{@code error} — 解析异常(损坏/加密/不支持的格式),带原因。</li>
 * </ul>
 *
 * @param text    提取的纯文本(可能被截断);失败时为空串
 * @param status  ok / empty / truncated / error
 * @param warning 非致命告警(truncated 时的可读说明);无则 null
 * @param error   失败原因(error 时的可读说明);无则 null
 */
@Service
public class TextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TextExtractionService.class);

    /** 提取文本上限:500,000 字符。 */
    public static final int MAX_TEXT_LENGTH = 500_000;

    /** 提取结果(状态 + 文本 + 诊断)。 */
    public record ExtractionResult(String text, String status, String warning, String error) {
        public static ExtractionResult ok(String text) {
            return new ExtractionResult(text, "ok", null, null);
        }

        public static ExtractionResult truncated(String text) {
            return new ExtractionResult(text, "truncated",
                    "内容超过 " + MAX_TEXT_LENGTH + " 字符上限,仅索引前 " + MAX_TEXT_LENGTH
                            + " 字符(可在文件中心查看完整原文)", null);
        }

        public static ExtractionResult empty() {
            return new ExtractionResult("", "empty",
                    "未提取到文本内容:可能是空白文档、纯二进制格式,或扫描件/图片型 PDF(需要 OCR,暂不支持)",
                    null);
        }

        public static ExtractionResult error(String reason) {
            return new ExtractionResult("", "error", null, reason);
        }

        /** 是否有可索引的文本。 */
        public boolean hasText() {
            return text != null && !text.isBlank();
        }
    }

    /**
     * 检测给定内容的 MIME 类型。文件名作提示;名称不可靠时以内容为准。
     *
     * @param content  原始文件内容
     * @param filename 文件名提示(可为 {@code null})
     * @return 检测到的媒体类型,如 {@code text/plain};绝不返回 {@code null}
     */
    public String detectMimeType(byte[] content, String filename) {
        try (InputStream stream = new java.io.ByteArrayInputStream(content)) {
            AutoDetectParser parser = new AutoDetectParser();
            Metadata metadata = new Metadata();
            if (filename != null) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            }
            MediaType type = parser.getDetector().detect(stream, metadata);
            return type.toString();
        } catch (IOException ex) {
            log.warn("MIME detection failed for '{}', defaulting to application/octet-stream", filename, ex);
            return MediaType.OCTET_STREAM.toString();
        }
    }

    /**
     * 从给定内容提取纯文本(带诊断)。
     *
     * <p>超限处理(2026-09-22 实测):BodyContentHandler(limit) 在超限时抛
     * WriteLimitReachedException 且**已写内容不可达**;改用
     * StringWriter + WriteOutContentHandler(writer, limit)——超限时 writer
     * 里已有完整的 limit 个字符(实测 60 万字符输入拿到 50 万),
     * 返回 truncated 状态 + 截断文本(仍可索引前 50 万字符)。
     *
     * @param stream   内容流(本方法不关闭)
     * @param filename 文件名提示(用于解析器选择/日志)
     * @return 提取结果(状态可区分空白/损坏/超限);绝不返回 {@code null}
     */
    public ExtractionResult extractWithStatus(InputStream stream, String filename) {
        java.io.StringWriter writer = new java.io.StringWriter();
        try {
            AutoDetectParser parser = new AutoDetectParser();
            WriteOutContentHandler handler = new WriteOutContentHandler(writer, MAX_TEXT_LENGTH);
            Metadata metadata = new Metadata();
            if (filename != null) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            }
            parser.parse(stream, handler, metadata, new ParseContext());
            String text = writer.toString();
            if (text == null || text.isBlank()) {
                return ExtractionResult.empty();
            }
            return ExtractionResult.ok(text);
        } catch (WriteLimitReachedException ex) {
            // 超限:writer 里已有 limit 个字符,可索引(标记不完整)
            log.info("Text extraction hit write limit for '{}'", filename);
            return ExtractionResult.truncated(writer.toString());
        } catch (SAXException ex) {
            if (WriteLimitReachedException.isWriteLimitReached(ex)) {
                log.info("Text extraction hit write limit (wrapped) for '{}'", filename);
                return ExtractionResult.truncated(writer.toString());
            }
            log.warn("Text extraction failed for '{}': {}", filename, ex.getMessage());
            return ExtractionResult.error("解析失败: " + safeMessage(ex));
        } catch (TikaException ex) {
            if (WriteLimitReachedException.isWriteLimitReached(ex)) {
                return ExtractionResult.truncated(writer.toString());
            }
            log.warn("Text extraction failed for '{}': {}", filename, ex.getMessage());
            return ExtractionResult.error("解析失败: " + safeMessage(ex));
        } catch (IOException ex) {
            log.warn("Could not read content of '{}' for text extraction: {}", filename, ex.getMessage());
            return ExtractionResult.error("读取内容失败: " + safeMessage(ex));
        } catch (RuntimeException ex) {
            log.warn("Unexpected extraction failure for '{}': {}", filename, ex.getMessage());
            return ExtractionResult.error("解析异常: " + safeMessage(ex));
        }
    }

    /**
     * 从给定内容提取纯文本(兼容入口,旧调用方)。
     *
     * @param stream   内容流(本方法不关闭)
     * @param filename 文件名提示(用于解析器选择/日志)
     * @return 提取的纯文本;失败时为空串
     */
    public String extract(InputStream stream, String filename) {
        return extractWithStatus(stream, filename).text();
    }

    private static String safeMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        return message.length() > 300 ? message.substring(0, 300) + "…" : message;
    }
}
