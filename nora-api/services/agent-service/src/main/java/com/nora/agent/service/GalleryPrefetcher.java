package com.nora.agent.service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 画廊列表预取(2026-09-17):MCP 工具结果里出现画廊围栏时,立即在后台把每条
 * 媒体的**播放流**拉入 Nora 磁盘缓存——用户还在看列表的时间窗口里,视频转码
 * (手机端首次 10-15s)+ 网络传输都已完成,点开即秒播。
 *
 * <p>围栏格式(2026-09-18 统一画廊后):新协议 {@code ```nora-artifacts}
 * (sections[].items[].url 为媒体地址)+ 旧 {@code ```nora-gallery} 兼容。
 *
 * <p>为什么挂在工具结果回流点:这是"结果列表刚封装好"的最早时机。等前端
 * 渲染后再触发会晚一拍(前端还要等 SSE 推流、渲染、用户滚动);挂在工具
 * 执行完的瞬间,转码在后台和后续对话轮并行进行。
 *
 * <p>预取目标 = 每条媒体 item 的地址:
 * <ul>
 *   <li>视频:/video 压缩流(点开灯箱播放用的就是它;首次需手机端转码);</li>
 *   <li>图片:/content 档位版(点开灯箱放大用)。</li>
 * </ul>
 *
 * <p>网络策略:仅当手机在 Wi-Fi(不计费)时预取——蜂窝下不偷偷烧流量
 * (与 /api/media/warm 的原片预取同一策略,见 MediaCacheService)。
 * 单飞去重 + 已缓存跳过,重复解析同一围栏无副作用。
 */
@Component
public class GalleryPrefetcher {

    private static final Logger log = LoggerFactory.getLogger(GalleryPrefetcher.class);

    /** 新协议(统一画廊,见 nora-web lib/artifacts.ts)。锚定行首:与前端协议一致,
     *  防止 JSON 字符串内容里的 ``` 字样被误匹配。 */
    private static final Pattern ARTIFACTS_FENCE = Pattern.compile("(?m)^```nora-artifacts\\s*\\n([\\s\\S]*?)```");
    /** 旧格式兼容(手机相册 photos_showcase 早期围栏)。 */
    private static final Pattern LEGACY_FENCE = Pattern.compile("(?m)^```nora-gallery\\s*\\n([\\s\\S]*?)```");

    private final ObjectMapper objectMapper;
    private final MediaCacheService mediaCacheService;

    public GalleryPrefetcher(ObjectMapper objectMapper, MediaCacheService mediaCacheService) {
        this.objectMapper = objectMapper;
        this.mediaCacheService = mediaCacheService;
    }

    /**
     * 扫描工具结果文本,发现画廊围栏即后台预取。
     * 无围栏/解析失败/非 Wi-Fi 时静默跳过(预取是增益,失败不影响对话)。
     */
    public void prefetchFromToolResult(String text) {
        if (text == null || (!text.contains("nora-artifacts") && !text.contains("nora-gallery"))) {
            return;
        }
        int queued = 0;
        // 新协议:nora-artifacts 的 sections[].items[] 里带 url/fullUrl 的媒体
        Matcher m = ARTIFACTS_FENCE.matcher(text);
        while (m.find()) {
            try {
                JsonNode root = objectMapper.readTree(m.group(1));
                for (JsonNode section : root.path("sections")) {
                    for (JsonNode item : section.path("items")) {
                        queued += prefetchItem(item);
                    }
                }
            } catch (Exception e) {
                log.debug("artifacts fence parse failed: {}", e.getMessage());
            }
        }
        // 旧格式兼容:nora-gallery 的 items[].fullUrl
        Matcher legacy = LEGACY_FENCE.matcher(text);
        while (legacy.find()) {
            try {
                JsonNode root = objectMapper.readTree(legacy.group(1));
                for (JsonNode item : root.path("items")) {
                    queued += prefetchItem(item);
                }
            } catch (Exception e) {
                log.debug("legacy gallery fence parse failed: {}", e.getMessage());
            }
        }
        if (queued > 0) {
            log.info("画廊列表预取: {} 项已入队(手机 Wi-Fi 时后台缓存播放流)", queued);
        }
    }

    /** 单条媒体 item 的预取(统一处理新旧协议的字段名)。 */
    private int prefetchItem(JsonNode item) {
        // 新协议:url=原图(灯箱用), thumbUrl=缩略图;旧协议:fullUrl=原图
        String full = item.path("fullUrl").asText("");
        if (full.isBlank()) {
            full = item.path("url").asText("");
        }
        if (full.isBlank() || !full.matches("(?i)^https?://.*")) {
            return 0;
        }
        mediaCacheService.prefetchPlaybackStream(full);
        return 1;
    }
}
