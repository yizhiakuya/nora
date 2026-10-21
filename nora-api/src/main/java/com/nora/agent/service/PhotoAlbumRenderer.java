package com.nora.agent.service;

import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;

import javax.imageio.ImageIO;

import org.springframework.core.io.ClassPathResource;
import org.springframework.web.util.HtmlUtils;

/** 将已选照片生成可离线打开的静态相册，预览不依赖远程图片或脚本。 */
final class PhotoAlbumRenderer {
    private PhotoAlbumRenderer() { }

    static byte[] preview(byte[] bytes, int budget) throws IOException {
        BufferedImage original;
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("图片格式不支持，请提供 JPEG、PNG 或 GIF");
            var reader = readers.next();
            try {
                reader.setInput(input);
                if (!java.util.Set.of("JPEG", "PNG", "GIF").contains(reader.getFormatName().toUpperCase(java.util.Locale.ROOT))) {
                    throw new IllegalArgumentException("图片格式不支持，请提供 JPEG、PNG 或 GIF");
                }
                if ((long) reader.getWidth(0) * reader.getHeight(0) > 40_000_000) {
                    throw new IllegalArgumentException("图片超过 4000 万像素，请先缩小");
                }
                original = reader.read(0);
            } finally { reader.dispose(); }
        }
        if (original == null) throw new IllegalArgumentException("无法解码图片");
        double scale = Math.min(1.0, 1200.0 / Math.max(original.getWidth(), original.getHeight()));
        while (true) {
            int width = Math.max(1, (int) (original.getWidth() * scale));
            int height = Math.max(1, (int) (original.getHeight() * scale));
            var resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            var graphics = resized.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, width, height);
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.drawImage(original, 0, 0, width, height, null);
            } finally { graphics.dispose(); }
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(resized, "jpeg", output)) throw new IOException("JPEG 编码器不可用");
            if (output.size() <= budget) return output.toByteArray();
            if (width <= 32 && height <= 32) throw new IllegalArgumentException("图片无法压缩到相册预览上限");
            scale *= 0.75;
        }
    }

    static String render(PhotoAlbumService.Album album, AgentWorkspaceService workspace,
                         java.util.Map<String, byte[]> prepared) throws IOException {
        String template;
        try (var input = new ClassPathResource("photo-album.html").getInputStream()) {
            template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var figures = new StringBuilder();
        int imageBudget = Math.min(180_000, 1_200_000 / album.photos().size());
        for (var photo : album.photos()) {
            byte[] bytes = prepared.get(photo.asset());
            if (bytes == null) bytes = Files.readAllBytes(workspace.resolveSafe(photo.asset()));
            byte[] thumbnail = bytes.length <= imageBudget ? bytes : preview(bytes, imageBudget);
            figures.append("<figure><img loading=\"lazy\" src=\"data:image/jpeg;base64,")
                    .append(Base64.getEncoder().encodeToString(thumbnail)).append("\" alt=\"")
                    .append(escape(photo.caption())).append("\"><figcaption><time>")
                    .append(escape(photo.date() == null ? "拍摄日期未记录" : photo.date()))
                    .append("</time><p>").append(escape(photo.caption())).append("</p></figcaption></figure>");
        }
        // 一次替换模板占位符，用户内容不会被再次当成模板处理。
        var values = java.util.Map.of(
                "title", escape(album.title()), "summary", escape(album.summary()),
                "theme", escape(album.style().theme()), "layout", escape(album.style().layout()),
                "revision", Integer.toString(album.revision()), "count", Integer.toString(album.photos().size()),
                "figures", figures.toString());
        var matcher = java.util.regex.Pattern.compile("\\{\\{(\\w+)}}").matcher(template);
        var result = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(values.get(matcher.group(1))));
        matcher.appendTail(result);
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > ViewerService.TEXT_LIMIT) {
            throw new IllegalArgumentException("相册超过 2MiB 预览上限，请减少代表照片");
        }
        return result.toString();
    }

    private static String escape(String value) { return HtmlUtils.htmlEscape(value == null ? "" : value); }
}
