package com.nora.agent.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;

import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.nora.common.exception.BusinessException;

/** 相册清单是当前版本的权威状态；HTML 版本和预览图保存在工作区。 */
@Service
public class PhotoAlbumService {
    private static final String ROOT = "photo-albums/";
    private static final String PREFERENCES = ROOT + "preferences.json";
    private final AgentWorkspaceService workspace;
    private final ViewerService viewer;
    private final ObjectMapper mapper;

    public PhotoAlbumService(AgentWorkspaceService workspace, ViewerService viewer, ObjectMapper mapper) {
        this.workspace = workspace;
        this.viewer = viewer;
        this.mapper = mapper;
    }

    public record Style(String theme, String layout, String captionTone) { }
    public record Photo(String hash, String target, String asset, String date, String caption) { }
    public record Feedback(String text, Style style, String at) { }
    public record Preferences(Style style, String rule, String at) { }
    public record Album(String album, int revision, String title, String summary, Style style,
                        String selectionRule, List<Photo> photos, List<Feedback> feedback, String updatedAt) { }

    // 与工作区写入一样，当前单 Agent 实例中顺序提交；revision 防止旧对话覆盖新版本。
    public synchronized ObjectNode execute(JsonNode request) throws IOException {
        if (request == null || !request.isObject()) throw new IllegalArgumentException("相册参数必须是 JSON 对象");
        String action = request.path("action").asText("");
        if ("list".equals(action)) return list();
        if (!List.of("read", "create", "update", "feedback").contains(action)) {
            throw new IllegalArgumentException("action 只允许 list / read / create / update / feedback");
        }
        String id = request.path("album").asText("");
        if (!id.matches("[a-z0-9][a-z0-9-]{0,63}")) throw new IllegalArgumentException("album 必须是 1–64 位小写英文、数字或连字符");
        String manifest = ROOT + id + "/album.json";
        boolean exists = Files.isRegularFile(workspace.resolveSafe(manifest));
        if ("create".equals(action) && exists) throw new BusinessException(409, "相册已存在，请先 read 后 update");
        if (!"create".equals(action) && !exists) throw new BusinessException(404, "相册不存在，请先创建");
        Album current = exists ? mapper.readValue(Files.readString(workspace.resolveSafe(manifest)), Album.class) : null;
        if ("read".equals(action)) {
            ObjectNode result = result(current, 0, 0);
            result.set("document", mapper.valueToTree(current));
            return result;
        }
        if (current != null && (!request.path("revision").isIntegralNumber()
                || request.path("revision").asInt() != current.revision())) {
            throw new BusinessException(409, "相册版本已变化，请重新 read 并使用最新 revision");
        }
        Preferences preferences = preferences();
        Style priorStyle = current == null ? preferences.style() : current.style();
        Style style = style(request.path("style"), priorStyle);
        String now = Instant.now().toString();
        var photos = new LinkedHashMap<String, Photo>();
        if (current != null) current.photos().forEach(photo -> photos.put(photo.hash(), photo));
        var prepared = new LinkedHashMap<String, byte[]>();
        int added = 0;
        int skipped = 0;
        JsonNode inputs = request.path("photos");
        if ("feedback".equals(action) && request.has("photos")) throw new IllegalArgumentException("feedback 不接受 photos，请使用 update 增量选片");
        if (!inputs.isMissingNode() && (!inputs.isArray() || inputs.size() > 24)) {
            throw new IllegalArgumentException("photos 必须是最多 24 项的数组");
        }
        if (inputs.isArray()) {
            for (var input : inputs) {
                String target = text(input, "target", null, 2048);
                if (target == null) throw new IllegalArgumentException("每张照片的 target 必填");
                var raw = viewer.image(target);
                String hash = AgentWorkspaceService.contentHash(raw.bytes());
                if (photos.containsKey(hash)) { skipped++; continue; }
                if (photos.size() >= 24) throw new IllegalArgumentException("相册最多 24 张代表照片，请缩小选片范围或新建相册");
                String date = text(input, "date", null, 10);
                if (date != null) {
                    try { LocalDate.parse(date); }
                    catch (java.time.format.DateTimeParseException e) {
                        throw new IllegalArgumentException("date 必须是有效的 YYYY-MM-DD 拍摄日期");
                    }
                }
                String asset = ROOT + id + "/images/" + hash + ".jpg";
                prepared.put(asset, PhotoAlbumRenderer.preview(raw.bytes(), 180_000));
                photos.put(hash, new Photo(hash, target, asset, date, text(input, "caption", "", 600)));
                added++;
            }
        }
        if (photos.isEmpty()) throw new IllegalArgumentException("请先查看并选择真实照片，再传入 photos 创建相册");
        JsonNode captions = request.path("captions");
        if (!captions.isMissingNode() && (!captions.isArray() || captions.size() > 24)) {
            throw new IllegalArgumentException("captions 必须是最多 24 项的数组");
        }
        if (captions.isArray()) {
            for (var edit : captions) {
                var photo = photos.get(text(edit, "hash", null, 64));
                if (photo == null) throw new IllegalArgumentException("文案修改必须使用 read 返回的已有照片 hash");
                if (!edit.has("caption")) throw new IllegalArgumentException("文案修改的 caption 必填");
                photos.put(photo.hash(), new Photo(photo.hash(), photo.target(), photo.asset(), photo.date(),
                        text(edit, "caption", "", 600)));
            }
        }
        var feedback = new ArrayList<Feedback>(current == null ? List.of() : current.feedback());
        String userFeedback = text(request, "feedback", null, 1000);
        if ("feedback".equals(action)) {
            if (userFeedback == null) throw new IllegalArgumentException("feedback 必须记录用户明确提出的风格要求");
            feedback.add(new Feedback(userFeedback, style, now));
            if (feedback.size() > 20) feedback.remove(0);
        }
        boolean remember = request.path("remember").asBoolean(false);
        if (remember && !"feedback".equals(action)) throw new IllegalArgumentException("remember 仅用于 feedback，用户明确要求以后沿用时才设为 true");
        List<Photo> ordered = photos.values().stream().sorted(Comparator.comparing(Photo::date,
                Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        Album next = new Album(id, current == null ? 1 : current.revision() + 1,
                text(request, "title", current == null ? id : current.title(), 120),
                text(request, "summary", current == null ? "" : current.summary(), 1500), style,
                current == null ? preferences.rule() : current.selectionRule(), ordered, List.copyOf(feedback), now);
        String html = PhotoAlbumRenderer.render(next, workspace, prepared);
        // 验证和渲染完成后才落盘；旧清单始终指向已完整写入的版本。
        for (var entry : prepared.entrySet()) write(entry.getKey(), entry.getValue());
        write(htmlPath(next), html.getBytes(StandardCharsets.UTF_8));
        write(manifest, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(next));
        if (remember) write(PREFERENCES, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(new Preferences(style, userFeedback, now)));
        ObjectNode result = result(next, added, skipped);
        result.put("remembered", remember);
        return result;
    }

    private ObjectNode list() throws IOException {
        ObjectNode result = mapper.createObjectNode();
        var albums = result.putArray("albums");
        var root = workspace.resolveSafe(ROOT);
        if (Files.isDirectory(root)) {
            try (var directories = Files.list(root)) {
                for (var directory : directories.filter(Files::isDirectory).sorted().toList()) {
                    String relative = ROOT + directory.getFileName() + "/album.json";
                    if (!Files.isRegularFile(workspace.resolveSafe(relative))) continue;
                    var album = mapper.readValue(Files.readString(workspace.resolveSafe(relative)), Album.class);
                    albums.add(result(album, 0, 0));
                }
            }
        }
        result.set("preferences", mapper.valueToTree(preferences()));
        return result;
    }

    private ObjectNode result(Album album, int added, int skipped) {
        ObjectNode result = mapper.createObjectNode();
        result.put("album", album.album()).put("title", album.title()).put("revision", album.revision())
                .put("photoCount", album.photos().size()).put("added", added).put("skipped", skipped)
                .put("htmlTarget", "workspace:" + htmlPath(album))
                .put("manifestTarget", "workspace:" + ROOT + album.album() + "/album.json");
        result.set("style", mapper.valueToTree(album.style()));
        return result;
    }

    private Preferences preferences() throws IOException {
        var path = workspace.resolveSafe(PREFERENCES);
        return Files.isRegularFile(path) ? mapper.readValue(Files.readString(path), Preferences.class)
                : new Preferences(new Style("paper", "timeline", "warm"), "", null);
    }

    private static String htmlPath(Album album) { return ROOT + album.album() + "/v" + album.revision() + ".html"; }

    private static Style style(JsonNode input, Style defaults) {
        if (input.isMissingNode()) return defaults;
        if (!input.isObject()) throw new IllegalArgumentException("style 必须是对象");
        String theme = text(input, "theme", defaults.theme(), 20);
        String layout = text(input, "layout", defaults.layout(), 20);
        String tone = text(input, "captionTone", defaults.captionTone(), 20);
        if (!List.of("paper", "night").contains(theme) || !List.of("timeline", "grid").contains(layout)
                || !List.of("plain", "warm").contains(tone)) throw new IllegalArgumentException("style: theme=paper/night, layout=timeline/grid, captionTone=plain/warm");
        return new Style(theme, layout, tone);
    }

    private static String text(JsonNode node, String field, String fallback, int limit) {
        if (!node.has(field)) return fallback;
        if (!node.path(field).isTextual()) throw new IllegalArgumentException(field + " 必须是字符串");
        String value = node.path(field).asText().trim();
        if (value.length() > limit) throw new IllegalArgumentException(field + " 超过 " + limit + " 字符");
        return value.isEmpty() ? fallback : value;
    }

    private void write(String relative, byte[] content) throws IOException {
        var path = workspace.resolveSafe(relative);
        Files.createDirectories(path.getParent());
        var temporary = Files.createTempFile(path.getParent(), ".album-", ".tmp");
        try {
            Files.write(temporary, content);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }
}
