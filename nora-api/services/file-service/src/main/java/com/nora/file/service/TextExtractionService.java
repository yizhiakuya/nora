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
 * Apache Tika based text extraction. Detects the MIME type from content
 * (with the file name as a hint) and extracts plain text, capped at
 * {@link #MAX_TEXT_LENGTH} characters.
 */
@Service
public class TextExtractionService {

    private static final Logger log = LoggerFactory.getLogger(TextExtractionService.class);

    /** Upper bound for extracted text: 500,000 characters. */
    public static final int MAX_TEXT_LENGTH = 500_000;

    /**
     * Detects the MIME type of the given content. The file name is used as a
     * hint; detection is content-based when the name is inconclusive.
     *
     * @param content  raw file content
     * @param filename file name hint (may be {@code null})
     * @return detected media type, e.g. {@code text/plain}; never {@code null}
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
     * Extracts plain text from the given content.
     *
     * @param stream   content stream (not closed by this method)
     * @param filename file name hint (used for parser selection/logging)
     * @return extracted plain text; empty string when nothing could be extracted
     */
    public String extract(InputStream stream, String filename) {
        try {
            AutoDetectParser parser = new AutoDetectParser();
            // writeLimit = -1 means unlimited; BodyContentHandler truncates at the limit
            // by throwing, so instead we cap by setting the limit to MAX_TEXT_LENGTH.
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
            // Corrupted or unparseable binary: degrade gracefully to empty text.
            log.warn("Text extraction failed for '{}': {}", filename, ex.getMessage());
            return "";
        }
    }
}
