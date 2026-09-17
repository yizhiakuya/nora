package com.nora.file.service;

import java.io.IOException;
import java.io.InputStream;

import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.xml.sax.SAXException;

/**
 * 基于 Apache Tika 的文本提取。从内容检测 MIME 类型(文件名作提示)并提取
 * 纯文本,上限 {@link #MAX_TEXT_LENGTH} 字符。
 */
@Service
public class TextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TextExtractionService.class);

    /** 提取文本上限:500,000 字符。 */
    public static final int MAX_TEXT_LENGTH = 500_000;

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
     * 从给定内容提取纯文本。
     *
     * @param stream   内容流(本方法不关闭)
     * @param filename 文件名提示(用于解析器选择/日志)
     * @return 提取的纯文本;无法提取时为空串
     */
    public String extract(InputStream stream, String filename) {
        try {
            AutoDetectParser parser = new AutoDetectParser();
            // writeLimit = -1 表示无限;BodyContentHandler 超限会抛异常截断,
            // 所以改为把限制设为 MAX_TEXT_LENGTH 来封顶。
            BodyContentHandler handler = new BodyContentHandler(MAX_TEXT_LENGTH);
            Metadata metadata = new Metadata();
            if (filename != null) {
                metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
            }
            parser.parse(stream, handler, metadata, new ParseContext());
            String text = handler.toString();
            return text == null ? "" : text;
        } catch (IOException ex) {
            log.warn("Could not read content of '{}' for text extraction: {}", filename, ex.getMessage());
            return "";
        } catch (TikaException | SAXException ex) {
            // 损坏或不可解析的二进制:优雅降级为空文本。
            log.warn("Text extraction failed for '{}': {}", filename, ex.getMessage());
            return "";
        }
    }
}
