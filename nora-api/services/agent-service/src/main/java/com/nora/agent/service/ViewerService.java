package com.nora.agent.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import org.springframework.http.MediaTypeFactory;
import org.springframework.stereotype.Service;

import com.nora.agent.dto.ViewerFile;

@Service
public class ViewerService {
    public static final int TEXT_LIMIT = 2 * 1024 * 1024;
    private final AgentWorkspaceService workspace;
    private final FileToolClient files;
    private final MediaCacheService media;
    private final SavedArtifactService artifacts;

    public ViewerService(AgentWorkspaceService workspace, FileToolClient files,
                         MediaCacheService media, SavedArtifactService artifacts) {
        this.workspace = workspace;
        this.files = files;
        this.media = media;
        this.artifacts = artifacts;
    }

    public record FileError(String target, String code, String message) { }
    public record ResolvedFiles(List<ViewerFile> files, List<FileError> errors) { }
    public record TextContent(String content, String hash, boolean truncated) { }

    public ViewerFile resolveMedia(String url) throws IOException {
        if (url == null || url.length() > 4096 || !url.matches("(?i)^https?://.*")) {
            throw new IllegalArgumentException("媒体地址必须是 HTTP/HTTPS");
        }
        var hit = media.lookup(url);
        if (hit.isEmpty()) {
            media.prefetchAsync(url);
            hit = media.awaitPrefetch(url, 20_000);
        }
        if (hit.isEmpty()) throw new IOException("媒体尚未缓存完成");
        return resolveOne("media:" + MediaCacheService.key(url));
    }

    public ResolvedFiles resolve(List<String> targets) {
        if (targets == null || targets.isEmpty() || targets.size() > 20) {
            throw new IllegalArgumentException("targets 必须包含 1–20 个文件引用");
        }
        var resolved = new LinkedHashMap<String, ViewerFile>();
        var errors = new ArrayList<FileError>();
        for (String target : targets) {
            try {
                ViewerFile file = resolveOne(target);
                resolved.putIfAbsent(file.target(), file);
            } catch (IllegalArgumentException e) {
                errors.add(new FileError(target, "INVALID_FILE", e.getMessage()));
            } catch (IOException | org.springframework.web.client.RestClientException e) {
                errors.add(new FileError(target, "FILE_UNAVAILABLE", "文件暂时无法读取，请刷新后重试"));
            }
        }
        return new ResolvedFiles(List.copyOf(resolved.values()), List.copyOf(errors));
    }

    public ViewerFile resolveOne(String target) throws IOException {
        if (target == null || target.length() > 2048) throw new IllegalArgumentException("文件引用无效");
        if (target.startsWith("workspace:")) {
            String relativePath = target.substring(10);
            if (Path.of(relativePath).isAbsolute() || relativePath.matches("^[a-zA-Z]:.*")) {
                throw new IllegalArgumentException("工作区引用只能使用相对路径");
            }
            Path path = workspace.resolveSafe(relativePath);
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("文件不存在或目标是目录");
            var stat = Files.readAttributes(path, BasicFileAttributes.class);
            String relative = workspace.root().toRealPath().relativize(path.toRealPath()).toString().replace('\\', '/');
            String version = stat.lastModifiedTime().to(TimeUnit.NANOSECONDS) + ":" + stat.size();
            return describe("workspace:" + relative, path.getFileName().toString(), mime(path), stat.size(),
                    stat.lastModifiedTime().toInstant().toString(), version, true);
        }
        if (target.matches("file:[1-9][0-9]{0,17}")) {
            long id = Long.parseLong(target.substring(5));
            var meta = files.metadata(id);
            if (meta == null) throw new IllegalArgumentException("文件不存在或已被删除");
            String modified = meta.path("createdAt").asText("");
            return describe("file:" + id, meta.path("name").asText(), meta.path("mimeType").asText(),
                    meta.path("sizeBytes").asLong(), modified, modified + ":" + meta.path("sizeBytes").asLong(), false);
        }
        if (target.startsWith("media:")) {
            String key = target.substring(6);
            var entry = media.lookupByKey(key).orElseThrow(() -> new IllegalArgumentException("媒体缓存不存在或已失效"));
            var modified = media.contentModifiedAt(entry);
            String extension = org.springframework.http.MediaType.parseMediaType(entry.contentType()).getSubtype().split("\\+")[0];
            return describe("media:" + key, "媒体-" + key.substring(0, Math.min(12, key.length())) + "." + extension, entry.contentType(), entry.size(),
                    modified.toInstant().toString(), modified.to(TimeUnit.NANOSECONDS) + ":" + entry.size() + ":" + entry.quality(), false);
        }
        throw new IllegalArgumentException("只接受 workspace:相对路径、file:id 或 media:缓存键");
    }

    public ResolvedFiles deliver(ResolvedFiles resolved, String sessionId, String stepId) {
        var delivered = new ArrayList<ViewerFile>();
        for (ViewerFile file : resolved.files()) {
            if (!file.target().startsWith("workspace:")) {
                delivered.add(file.withDelivery(new ViewerFile.Delivery("not_requested", null, null)));
                continue;
            }
            try {
                var artifact = artifacts.register("workspace_file", file.target().substring(10), file.name(), sessionId, stepId);
                delivered.add(file.withDelivery(new ViewerFile.Delivery("registered", artifact.id(), null)));
            } catch (org.springframework.dao.DataAccessException e) {
                delivered.add(file.withDelivery(new ViewerFile.Delivery("failed", null, "文件已保存，成果登记失败，可重试")));
            }
        }
        return new ResolvedFiles(List.copyOf(delivered), resolved.errors());
    }

    public TextContent text(String target) throws IOException {
        ViewerFile file = resolveOne(target);
        if (!file.capabilities().source()) throw new IllegalArgumentException("此文件不支持源码读取");
        byte[] bytes;
        if (file.target().startsWith("workspace:")) {
            try (var input = Files.newInputStream(workspace.resolveSafe(file.target().substring(10)))) {
                bytes = input.readNBytes(TEXT_LIMIT + 1);
            }
        } else if (file.target().startsWith("file:")) {
            bytes = files.rawBounded(Long.parseLong(file.target().substring(5)), TEXT_LIMIT + 1);
        } else {
            var entry = media.lookupByKey(file.target().substring(6)).orElseThrow(() -> new IllegalArgumentException("媒体已失效"));
            try (var input = Files.newInputStream(entry.file())) { bytes = input.readNBytes(TEXT_LIMIT + 1); }
        }
        boolean truncated = bytes.length > TEXT_LIMIT || file.size() > TEXT_LIMIT;
        int length = Math.min(bytes.length, TEXT_LIMIT);
        String content = truncated ? new String(bytes, 0, length, StandardCharsets.UTF_8)
                : StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        if (content.indexOf('\u0000') >= 0) throw new IllegalArgumentException("文件不是 UTF-8 文本");
        return new TextContent(content, truncated ? null : AgentWorkspaceService.contentHash(bytes), truncated);
    }

    public FileToolClient.RawFile image(String target) throws IOException {
        ViewerFile file = resolveOne(target);
        if (!List.of("image/jpeg", "image/png", "image/gif", "image/webp").contains(file.mimeType())) {
            throw new IllegalArgumentException("该格式不能作为模型图像输入");
        }
        int limit = 8 * 1024 * 1024;
        if (file.size() > limit) throw new IllegalArgumentException("图片超过 8MiB，请缩小后再引用");
        byte[] bytes;
        if (target.startsWith("file:")) bytes = files.rawBounded(Long.parseLong(target.substring(5)), limit + 1);
        else {
            Path path = target.startsWith("workspace:") ? workspace.resolveSafe(target.substring(10))
                    : media.lookupByKey(target.substring(6)).orElseThrow(() -> new IllegalArgumentException("媒体已失效")).file();
            try (var input = Files.newInputStream(path)) { bytes = input.readNBytes(limit + 1); }
        }
        if (bytes.length > limit) throw new IllegalArgumentException("图片超过 8MiB，请缩小后再引用");
        return new FileToolClient.RawFile(file.mimeType(), bytes);
    }

    public static String mime(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".md")) return "text/markdown";
        if (name.endsWith(".csv")) return "text/csv";
        if (name.endsWith(".tsv")) return "text/tab-separated-values";
        return MediaTypeFactory.getMediaType(name).map(Object::toString).orElse("application/octet-stream");
    }

    private static ViewerFile describe(String target, String name, String mime, long size,
                                        String modified, String version, boolean workspaceFile) {
        String lower = name.toLowerCase(Locale.ROOT);
        String kind;
        if (mime.startsWith("image/")) kind = "image";
        else if (mime.startsWith("video/")) kind = "video";
        else if (mime.startsWith("audio/")) kind = "audio";
        else if (lower.endsWith(".pdf") || mime.equals("application/pdf")) kind = "pdf";
        else if (lower.matches(".*\\.(csv|tsv)$")) kind = "csv";
        else if (lower.endsWith(".md") || mime.equals("text/markdown")) kind = "markdown";
        else if (lower.matches(".*\\.(html?|xhtml)$")) kind = "html";
        else if (lower.endsWith(".json")) kind = "json";
        else if (lower.matches(".*\\.(docx?|xlsx?)$")) kind = workspaceFile ? "unknown" : "office";
        else if (mime.startsWith("text/") || lower.matches(".*\\.(txt|log|ya?ml|xml|java|[cm]?js|[cm]?tsx?|py|sh|sql|css|toml|ini|conf|vue|rs|go)$")) kind = "text";
        else kind = "unknown";
        boolean source = List.of("markdown", "text", "csv", "json", "html").contains(kind);
        return new ViewerFile(target, name, mime, size, modified, version, kind,
                new ViewerFile.Capabilities(!kind.equals("unknown"), source, true,
                        workspaceFile && source && size <= TEXT_LIMIT, !kind.equals("unknown")), null);
    }
}
