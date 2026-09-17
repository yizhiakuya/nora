package com.nora.agent.controller;

import com.nora.common.exception.BusinessException;
import com.nora.common.response.ApiResponse;
import com.nora.agent.service.MediaCacheService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * 远程媒体缓存端点(2026-09-17):前端把相册等远程媒体 URL 换成
 * {@code /api/media/cache?url=...} 引用——后端磁盘缓存一次拉取长期复用。
 *
 * <p>三种路径:
 * <ul>
 *   <li>命中缓存:磁盘直出(200,支持 Range → 206),附 7 天浏览器缓存
 *       ——手机离线也能看、刷新秒开;</li>
 *   <li>未命中·带 Range(视频首播):Range 直通上游(立即可播可 seek),
 *       同时后台整文件下载预热(下次命中);</li>
 *   <li>未命中·无 Range(图片):边下边写(tee)——首字节立即到达,
 *       完成即入缓存。</li>
 * </ul>
 *
 * <p>全部走 Spring MVC 内建抽象(无需额外依赖):{@link StreamingResponseBody}
 * 异步流式(不占 servlet 线程,慢隧道场景不堵 Tomcat 池);状态/头经
 * {@link ResponseEntity} 声明。注意返回类型必须显式泛型化为
 * {@code ResponseEntity<StreamingResponseBody>}——通配符会让 Spring 的
 * 专用异步处理器失效,落到消息转换器报 "No converter"(实测踩坑)。
 */
@RestController
@RequestMapping("/api/media")
public class MediaCacheController {

    private static final Logger log = LoggerFactory.getLogger(MediaCacheController.class);

    /** 保存到文件中心的大小上限:与 file-service 的 max-file-size 一致(100MB)。 */
    private static final long MAX_SAVE_BYTES = 100L * 1024 * 1024;

    private final MediaCacheService mediaCache;
    /** 保存到文件中心(缓存条目 → file-service 上传)。 */
    private final com.nora.agent.service.FileToolClient fileToolClient;

    public MediaCacheController(MediaCacheService mediaCache,
                                com.nora.agent.service.FileToolClient fileToolClient) {
        this.mediaCache = mediaCache;
        this.fileToolClient = fileToolClient;
    }

    /**
     * 缓存服务远程媒体。
     *
     * @param url   远程媒体 URL(须 http/https)
     * @param range 可选 Range 头(视频 seek)
     */
    @GetMapping("/cache")
    public ResponseEntity<StreamingResponseBody> cache(
            @RequestParam("url") String url,
            @RequestHeader(value = "Range", required = false) String range) {
        if (url == null || !url.matches("(?i)^https?://.*")) {
            throw BusinessException.validation("INVALID_MEDIA_URL", "url 必须是 http/https 地址",
                "媒体缓存只代理远程 http(s) 地址;本地文件请用 /api/files 端点");
        }

        // 1) 命中:磁盘直出(Range → 206;浏览器缓存 7 天)
        Optional<MediaCacheService.CacheEntry> hit = mediaCache.lookup(url);
        if (hit.isPresent()) {
            MediaCacheService.CacheEntry e = hit.get();
            mediaCache.touch(e);
            return serveCached(e, range);
        }

        // 1.5) 有同 URL 的预取在途(画廊列表刚触发):等它完成直接读盘——
        //      否则会发重复请求排在手机转码队列后面,反而更慢。等不到就回源。
        //      视频转码首次 10-15s;20s 是"值得等"的上限(用户刚点开)。
        Optional<MediaCacheService.CacheEntry> warmed = mediaCache.awaitPrefetch(url, 20_000);
        if (warmed.isPresent()) {
            MediaCacheService.CacheEntry e = warmed.get();
            mediaCache.touch(e);
            return serveCached(e, range);
        }

        // 2) 未命中 + 带 Range(视频首播):上游支持 Range → 直通(206);
        //    不支持(部分中继/隧道永远回 200 全量)→ 退化为 tee 边播边缓存,
        //    缓存完成后本地即可 Range(seek 不再依赖上游)。
        if (range != null && !range.isBlank()) {
            MediaCacheService.UpstreamStream u = openUpstream(url, range);
            if (u.body() == null) {
                throw BusinessException.dependency("MEDIA_UPSTREAM_FAILED", mediaCache.friendlyError(u),
                    "检查手机相册 App 的隧道连接,或稍后重试(已缓存的媒体不受影响)");
            }
            if (u.status() == 206 && u.contentRange() != null) {
                // 上游真支持 Range:直通 + 后台预热整文件
                mediaCache.prefetchAsync(url);
                ResponseEntity.BodyBuilder b = ResponseEntity.status(u.status())
                        .header("Cache-Control", MediaCacheService.BROWSER_CACHE_CONTROL)
                        .header("Accept-Ranges", "bytes")
                        .header("Content-Range", u.contentRange());
                if (u.contentLength() > 0) {
                    b.contentLength(u.contentLength());
                }
                return b.contentType(parseMediaType(u.contentType())).body(out -> {
                    try (InputStream in = u.body()) {
                        in.transferTo(out);
                    } catch (IOException ex) {
                        // 客户端 seek 中断:正常现象
                        log.debug("media range passthrough interrupted ({}): {}", url, ex.getMessage());
                    }
                });
            }
            // 上游不支持 Range(回 200 全量):tee 全量给客户端 + 入缓存
            return teeResponse(u);
        }

        // 3) 未命中 + 无 Range(图片等):tee 边下边写,首字节立即到达
        MediaCacheService.UpstreamStream u = openUpstream(url, null);
        if (u.body() == null) {
            throw BusinessException.dependency("MEDIA_UPSTREAM_FAILED", mediaCache.friendlyError(u),
                    "检查手机相册 App 的隧道连接,或稍后重试(已缓存的媒体不受影响)");
        }
        return teeResponse(u);
    }

    /**
     * 实时观看时自动缓存原片(2026-09-17)。
     *
     * <p>前端打开媒体灯箱时对同一媒体调用一次:后端后台把**原片**拉入磁盘缓存
     * (与前端正在看的压缩流互不冲突)。仅当手机在 Wi-Fi(不计费网络)时执行
     * ——蜂窝下预取原片会偷偷烧流量(中继 /health 上报手机网络类型)。
     * 立即返回,不等下载完成;失败静默(观看本身不受影响)。
     *
     * @param url 媒体的原始 URL(与 /cache 的 url 同形态;前端传 fullUrl)
     */
    @PostMapping("/warm")
    public ApiResponse<WarmResult> warm(@RequestBody WarmRequest request) {
        String url = request == null ? null : request.url();
        if (url == null || !url.matches("(?i)^https?://.*")) {
            throw BusinessException.validation("INVALID_MEDIA_URL", "url 必须是 http/https 地址",
                "媒体预热只接受远程 http(s) 地址");
        }
        Optional<MediaCacheService.CacheEntry> hit = mediaCache.lookup(url);
        boolean allowed = mediaCache.originalPrefetchAllowed();
        if (allowed) {
            if (hit.isEmpty()) {
                // 未缓存:后台拉原片入缓存
                mediaCache.prefetchOriginalOnView(url);
            } else if ("low".equalsIgnoreCase(hit.get().quality())) {
                // 已有省流量档缓存(蜂窝下看过):重拉原片覆盖,回 Wi-Fi 后就是清晰版
                mediaCache.refreshAsync(url);
            }
        }
        return ApiResponse.ok(new WarmResult(hit.isPresent(), allowed));
    }

    /** POST /api/media/warm body。 */
    public record WarmRequest(String url) {
    }

    /** POST /api/media/warm response。 */
    public record WarmResult(boolean cached, boolean allowed) {
    }

    /**
     * 链路状态快照(排障用):当前走公网还是局域网、手机是否 Wi-Fi。
     * 返回原始状态行 + 结构化字段。
     */
    @GetMapping("/status")
    public ApiResponse<StatusView> status() {
        String raw = mediaCache.routerStatus();
        return ApiResponse.ok(new StatusView(raw, mediaCache.originalPrefetchAllowed()));
    }

    /** GET /api/media/status response。 */
    public record StatusView(String router, boolean prefetchAllowed) {
    }

    /**
     * 文件中心「媒体缓存」文件夹:列出全部缓存条目。
     *
     * <p>媒体缓存不再只是磁盘上的黑盒——用户在文件中心能看到缓存了什么、
     * 各占多大、按档位(high/low)区分,并可删除/清空。
     */
    @GetMapping("/cached")
    public ApiResponse<CachedListView> cached() {
        var items = mediaCache.listCached();
        long totalBytes = items.stream().mapToLong(MediaCacheService.CachedItem::size).sum();
        return ApiResponse.ok(new CachedListView(items, items.size(), totalBytes, mediaCache.cacheDirPath()));
    }

    /** GET /api/media/cached response。 */
    public record CachedListView(List<MediaCacheService.CachedItem> items, int count,
                                 long totalBytes, String dir) {
    }

    /** 删除单个缓存条目。 */
    @DeleteMapping("/cached/{key}")
    public ApiResponse<Boolean> deleteCached(@PathVariable("key") String key) {
        if (!mediaCache.deleteCached(key)) {
            throw BusinessException.validation("MEDIA_CACHE_NOT_FOUND", "缓存条目不存在或已被删除",
                "刷新列表后重试");
        }
        return ApiResponse.ok(true);
    }

    /** 清空全部媒体缓存。 */
    @DeleteMapping("/cached")
    public ApiResponse<Integer> clearCached() {
        return ApiResponse.ok(mediaCache.clearCached());
    }

    /**
     * 把缓存条目**保存到文件中心**(2026-09-17):媒体缓存是自动派生层,
     * 用户觉得某张照片/视频值得留存时,一键转为正式知识资产——之后可
     * 索引入知识库、被对话 @ 引用、被 agent read_file 读取。
     *
     * <p>服务端直传:agent-service 从缓存读字节 → 转发 file-service 上传
     * (浏览器不参与,大视频也不用先下载再上传)。文件名按媒体 id + 类型推断。
     */
    @PostMapping("/cached/{key}/save")
    public ApiResponse<SaveResult> saveToFiles(@PathVariable("key") String key,
                                               @RequestBody(required = false) SaveRequest request) {
        Optional<MediaCacheService.CacheEntry> hit = mediaCache.lookupByKey(key);
        if (hit.isEmpty()) {
            throw BusinessException.validation("MEDIA_CACHE_NOT_FOUND", "缓存条目不存在或已被删除",
                "刷新列表后重试");
        }
        MediaCacheService.CacheEntry entry = hit.get();
        // 大小前置检查:媒体缓存条目可达数百 MB(原片),而 file-service 上传上限
        // 100MB——不先查就 readAllBytes 会白耗内存(数百 MB 分配 → GC 压力,
        // 大文件还必然被 file-service 拒绝,错误在内存峰值之后才出现)。
        long size = entry.size();
        if (size > MAX_SAVE_BYTES) {
            throw BusinessException.validation("MEDIA_TOO_LARGE_FOR_SAVE",
                "该媒体 " + (size / 1024 / 1024) + "MB,超过文件中心单文件上限 "
                    + (MAX_SAVE_BYTES / 1024 / 1024) + "MB",
                "如需留存请在手机上导出,或直接下载到本机保存");
        }
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(entry.file());
        } catch (IOException e) {
            throw BusinessException.dependency("MEDIA_CACHE_READ_FAILED", "读取缓存失败: " + e.getMessage(),
                "刷新后重试;文件可能已被 LRU 淘汰");
        }
        String name = request != null && request.filename() != null && !request.filename().isBlank()
                ? request.filename().trim()
                : inferFilename(entry);
        String result = fileToolClient.upload(name, bytes);
        if (result.startsWith("ERROR")) {
            throw BusinessException.dependency("MEDIA_SAVE_FAILED", result,
                "检查 file-service 是否在线后重试");
        }
        return ApiResponse.ok(new SaveResult(name, bytes.length, result));
    }

    /** POST /api/media/cached/{key}/save body(可选自定义文件名)。 */
    public record SaveRequest(String filename) {
    }

    /** POST /api/media/cached/{key}/save response。 */
    public record SaveResult(String name, long size, String detail) {
    }

    /** 从缓存条目的 URL 推断文件名(如 photo/8678/video → 相册-8678-播放流.mp4)。 */
    private static String inferFilename(MediaCacheService.CacheEntry entry) {
        String url = entry.url() == null ? "" : entry.url();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("/photo/(\\d+)/(content|thumb|video|sheet)").matcher(url);
        String ext = switch (entry.contentType() == null ? "" : entry.contentType()) {
            case "video/mp4" -> ".mp4";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> entry.contentType() != null && entry.contentType().startsWith("video/") ? ".mp4" : ".bin";
        };
        if (m.find()) {
            String kindText = switch (m.group(2)) {
                case "video" -> "播放流";
                case "thumb" -> "缩略图";
                case "sheet" -> "拼图";
                default -> "原片";
            };
            return "相册-" + m.group(1) + "-" + kindText + ext;
        }
        return "相册媒体-" + entry.file().getFileName().toString().substring(0, 8) + ext;
    }

    /** tee 响应:客户端流与磁盘缓存同时写(客户端断开仍完成缓存)。 */
    private ResponseEntity<StreamingResponseBody> teeResponse(MediaCacheService.UpstreamStream u) {
        return ResponseEntity.ok()
                .header("Cache-Control", MediaCacheService.BROWSER_CACHE_CONTROL)
                .header("Accept-Ranges", "bytes")
                .contentType(parseMediaType(u.contentType()))
                .body(out -> {
                    try {
                        mediaCache.teeStream(u, out);
                    } catch (IOException ex) {
                        // 流已开始无法改状态码;teeStream 内部已处理客户端断开
                        log.warn("media tee stream failed ({}): {}", u.sourceUrl(), ex.getMessage());
                    }
                });
    }

    /** 打开上游连接;连接失败统一转 502 + 可操作提示。 */
    private MediaCacheService.UpstreamStream openUpstream(String url, String range) {
        try {
            return mediaCache.openUpstream(url, range);
        } catch (Exception e) {
            throw BusinessException.dependency("MEDIA_UPSTREAM_FAILED", "上游媒体连接失败: " + e.getMessage(),
                    "检查网络与目标地址可达性;已缓存的媒体不受影响", e);
        }
    }

    /** 磁盘文件服务(单区间 Range → 206;否则整体 200)。 */
    private ResponseEntity<StreamingResponseBody> serveCached(MediaCacheService.CacheEntry e, String range) {
        long length = e.size();
        if (range != null && range.startsWith("bytes=") && !range.contains(",")) {
            try {
                HttpRange r = HttpRange.parseRanges(range).get(0);
                long start = r.getRangeStart(length);
                long end = Math.min(r.getRangeEnd(length), length - 1);
                if (start <= end) {
                    long regionLength = end - start + 1;
                    return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                            .header("Content-Range", "bytes " + start + "-" + end + "/" + length)
                            .header("Accept-Ranges", "bytes")
                            .header("Cache-Control", MediaCacheService.BROWSER_CACHE_CONTROL)
                            .contentType(parseMediaType(e.contentType()))
                            .contentLength(regionLength)
                            .body(out -> copyRegion(e.file(), start, regionLength, out));
                }
            } catch (Exception ex) {
                // 坏 Range(解析失败/越界):退化为整体返回
                log.debug("bad range ({}), serving full: {}", range, ex.getMessage());
            }
        }
        return ResponseEntity.ok()
                .header("Accept-Ranges", "bytes")
                .header("Cache-Control", MediaCacheService.BROWSER_CACHE_CONTROL)
                .contentType(parseMediaType(e.contentType()))
                .contentLength(length)
                .body(out -> {
                    try (InputStream in = Files.newInputStream(e.file())) {
                        in.transferTo(out);
                    } catch (IOException ex) {
                        log.debug("media cache stream interrupted ({}): {}", e.file(), ex.getMessage());
                    }
                });
    }

    /** 拷贝文件的 [start, start+len) 区间(视频 seek 的分块服务)。 */
    private static void copyRegion(Path file, long start, long len, OutputStream out) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            raf.seek(start);
            byte[] buf = new byte[64 * 1024];
            long remaining = len;
            while (remaining > 0) {
                int n = raf.read(buf, 0, (int) Math.min(buf.length, remaining));
                if (n < 0) {
                    break;
                }
                out.write(buf, 0, n);
                remaining -= n;
            }
        }
    }

    /** 上游 Content-Type 可能不规范;解析失败按二进制流兜底。 */
    private static MediaType parseMediaType(String ct) {
        try {
            return MediaType.parseMediaType(ct);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
