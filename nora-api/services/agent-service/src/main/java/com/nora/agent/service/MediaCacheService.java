package com.nora.agent.service;

import com.nora.common.http.ProxySettingsHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 远程媒体磁盘缓存(2026-09-17):手机相册等经隧道拉取的图片/视频,
 * 后端做「一次拉取、长期复用」的磁盘缓存。
 *
 * <p>为什么需要:相册缩略图每次直连隧道 ~3.2s(手机离线时更不可用),
 * 视频动辄几十 MB;浏览器直连无法跨会话复用、手机离线即失效。
 *
 * <p>设计:
 * <ul>
 *   <li>键 = sha256(url);文件 {@code <key>.bin}(内容)+ {@code <key>.meta}
 *       (Content-Type 与来源 URL);</li>
 *   <li>命中:直接磁盘服务(Controller 支持 Range/长浏览器缓存)——手机离线也能看;</li>
 *   <li>未命中·带 Range(视频首播):调用方把 Range 直通上游(不等待、可 seek),
 *       同时本类后台整文件下载预热缓存(单飞);</li>
 *   <li>未命中·无 Range(图片):边下边写(tee)——首字节立即到达,完成即入缓存;
 *       客户端中途断开时继续写盘完成缓存;</li>
 *   <li>LRU 淘汰:文件 mtime 即最近访问时间(命中服务时 touch),超上限按最旧
 *       访问清理到 90%;启动清理超 1 小时的 .part 残留。</li>
 * </ul>
 *
 * <p>失败语义:上游错误(如手机离线 503)打开响应时同步抛出/返回,由 Controller
 * 转 502 + 可操作提示;永不把坏数据写入缓存(.part 失败即删)。
 */
@Service
public class MediaCacheService {

    private static final Logger log = LoggerFactory.getLogger(MediaCacheService.class);

    /** 命中响应下发的浏览器缓存策略:7 天(相册 URL 带 token,内容不可变)。 */
    public static final String BROWSER_CACHE_CONTROL = "private, max-age=604800";

    /** 磁盘缓存默认上限 2GB;下限 64MB(防误配把缓存关死)。 */
    private static final long DEFAULT_MAX_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MIN_MAX_BYTES = 64L * 1024 * 1024;
    /** .part 残留清理阈值:超过 1 小时的未完成下载视为孤儿。 */
    private static final long STALE_PART_MILLIS = 60L * 60 * 1000;

    private final Path cacheDir;
    private final long maxBytes;
    private final ExecutorService prefetchExecutor;
    /** 单飞:同一 URL 的后台预热下载只跑一个。 */
    private final ConcurrentHashMap<String, CompletableFuture<Path>> inFlight = new ConcurrentHashMap<>();
    /** 链路自动选择(局域网优先,公网兜底);可为 null(未启用)。 */
    private final RelayMediaRouter router;
    /**
     * HttpClient 缓存(按代理配置键控):JDK HttpClient 自带连接池,
     * 复用实例才能吃到 keep-alive——此前每请求 new 一个,连 TLS/连接
     * 都要重来,是公网路径上的固定开销。代理配置运行时可变,键 = "direct"
     * 或 "host:port",配置变了自然换新实例。
     */
    private final ConcurrentHashMap<String, HttpClient> clients = new ConcurrentHashMap<>();

    public MediaCacheService(@Value("${nora.media-cache.dir:./data/media-cache}") String dir,
                             @Value("${nora.media-cache.max-bytes:" + DEFAULT_MAX_BYTES + "}") long maxBytes,
                             RelayMediaRouter router) {
        this.cacheDir = Path.of(dir);
        this.maxBytes = Math.max(MIN_MAX_BYTES, maxBytes);
        this.router = router;
        this.prefetchExecutor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "media-prefetch");
            t.setDaemon(true);
            return t;
        });
        try {
            Files.createDirectories(cacheDir);
            cleanupStaleParts();
        } catch (Exception e) {
            log.warn("media cache init failed ({}): {}", cacheDir, e.getMessage());
        }
    }

    /** 缓存条目(已完成下载)。 */
    public record CacheEntry(Path file, String contentType, long size, String quality, String phoneKind) {
    }

    /** 上游流式响应:打开连接并拿到响应头后交给调用方流式消费。 */
    public record UpstreamStream(int status, String contentType, long contentLength,
                                 String contentRange, InputStream body, String errorBody,
                                 String quality, String phoneKind, String sourceUrl) {
    }

    /** 上游错误(状态码 + 可读信息)。 */
    public static class UpstreamException extends IOException {
        private final int status;

        public UpstreamException(int status, String message) {
            super(message);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }

    /** 已完成缓存的查找(不存在/损坏返回 empty)。 */
    public Optional<CacheEntry> lookup(String url) {
        String key = key(url);
        Path bin = cacheDir.resolve(key + ".bin");
        if (!Files.isRegularFile(bin)) {
            return Optional.empty();
        }
        try {
            Meta meta = readMeta(key);
            return Optional.of(new CacheEntry(bin, meta.contentType(), Files.size(bin), meta.quality(), meta.phoneKind()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 命中服务时刷新 mtime(LRU 的"最近访问时间")。 */
    public void touch(CacheEntry entry) {
        try {
            Files.setLastModifiedTime(entry.file(), FileTime.fromMillis(System.currentTimeMillis()));
        } catch (Exception ignored) {
            // 刷新失败不影响服务
        }
    }

    /**
     * 打开上游媒体连接(同步等到响应头)。
     *
     * <p>链路:局域网优先(中继内网端口可达时自动重写),失败立即回退公网——
     * 对调用方透明。重写只影响网络路径,URL 里的 path/query 原样保留。
     *
     * @param range 可选 Range 头(视频 seek);null = 整体
     * @return 上游响应(>=400 时 body 为 null、errorBody 有内容)
     */
    public UpstreamStream openUpstream(String url, String range) throws IOException {
        String effective = router == null ? url : router.preferLan(url);
        try {
            return openUpstreamDirect(effective, range, url);
        } catch (IOException e) {
            if (!effective.equals(url)) {
                // 局域网失败:回退公网,并让路由器下次探测前不再优先内网
                if (router != null) {
                    router.reportLanFailure();
                }
                log.info("局域网拉取失败,回退公网: {} ({})", url, e.getMessage());
                return openUpstreamDirect(url, range, url);
            }
            throw e;
        }
    }

    /** 打开指定地址的上游连接(不做链路选择);sourceUrl 仅用于元数据/日志。 */
    private UpstreamStream openUpstreamDirect(String effective, String range, String sourceUrl) throws IOException {
        HttpClient client = buildClient(effective);
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(effective))
                .timeout(Duration.ofSeconds(90)) // 等到响应头;响应体流式读取不受此限
                .header("User-Agent", "Nora-MediaCache/1.0")
                .GET();
        if (range != null && !range.isBlank()) {
            rb.header("Range", range);
        }
        HttpResponse<InputStream> resp;
        try {
            resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new IOException("上游媒体连接失败: " + e.getMessage(), e);
        }
        String ct = resp.headers().firstValue("Content-Type").orElse("application/octet-stream");
        long len = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
        String cr = resp.headers().firstValue("Content-Range").orElse(null);
        // 手机出图档位(high=原尺寸,low=降采样)与真实网络类型(wifi/metered):
        // 前者是缓存分档依据,后者供「观看时自动缓存原片」判断
        String quality = resp.headers().firstValue("X-Net-Quality").orElse(null);
        String phoneKind = resp.headers().firstValue("X-Net-Kind").orElse(null);
        if (router != null && phoneKind != null) {
            router.reportPhoneKind(phoneKind);
        }
        if (resp.statusCode() >= 400) {
            String err;
            try (InputStream in = resp.body()) {
                err = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                err = "";
            }
            return new UpstreamStream(resp.statusCode(), ct, len, cr, null, err, quality, phoneKind, sourceUrl);
        }
        return new UpstreamStream(resp.statusCode(), ct, len, cr, resp.body(), null, quality, phoneKind, sourceUrl);
    }

    /** 把上游错误转成可操作提示(手机离线等已知文案给出引导)。 */
    public String friendlyError(UpstreamStream u) {
        String body = u.errorBody() == null ? "" : u.errorBody();
        if (body.toLowerCase().contains("phone offline")) {
            return "手机相册离线:请确认手机 App 的「隧道连接」已开启(上游返回 " + u.status() + ")";
        }
        String snippet = body.length() > 300 ? body.substring(0, 300) + "…" : body;
        return "上游媒体返回 " + u.status() + (snippet.isBlank() ? "" : ": " + snippet);
    }

    /**
     * 边下边写(tee):已打开的上游流 → 客户端 + 磁盘同时写。
     * 客户端中途断开时继续写盘完成缓存;写盘失败(磁盘满等)只告警不阻断客户端。
     *
     * @param u 已打开的上游流(携带档位/来源元数据)
     * @return 完整缓存条目(写盘失败时为 null,客户端不受影响)
     */
    public CacheEntry teeStream(UpstreamStream u, OutputStream clientOut)
            throws IOException {
        String url = u.sourceUrl();
        String key = key(url);
        Path target = cacheDir.resolve(key + ".bin");
        Path part = cacheDir.resolve(key + "." + System.nanoTime() + ".part");
        long total = 0;
        try (InputStream in = u.body(); OutputStream fileOut = Files.newOutputStream(part)) {
            byte[] buf = new byte[64 * 1024];
            boolean clientAlive = true;
            int n;
            while ((n = in.read(buf)) >= 0) {
                fileOut.write(buf, 0, n);
                total += n;
                if (clientAlive) {
                    try {
                        clientOut.write(buf, 0, n);
                        clientOut.flush();
                    } catch (IOException e) {
                        // 客户端断开(切页/关灯箱):不再写客户端,继续写盘完成缓存
                        clientAlive = false;
                        log.debug("media client disconnected, continue caching: {}", url);
                    }
                }
            }
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (Exception ignored) {
                // 删除失败留给启动清理
            }
            throw e;
        }
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            writeMeta(key, u.contentType(), url, u.quality(), u.phoneKind());
            evictIfNeeded();
            return new CacheEntry(target, u.contentType(), total, u.quality(), u.phoneKind());
        } catch (IOException e) {
            log.warn("media cache persist failed ({}): {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * 后台预热:整文件下载入缓存(单飞;已缓存/在飞则跳过)。
     *
     * @param url         缓存键所用的原始 URL(公网形态)
     * @param requireHigh 仅当上游档位为 high(原尺寸)时入库——实时观看请求的
     *                    「自动缓存原片」用它:观看本身拿到的是压缩流(low 档),
     *                    同时后台把原片拉进来;若手机当前就是 low 档(蜂窝),
     *                    拉到的"原片"其实是降采样图,不满足原片语义 → 放弃,
     *                    等 Wi-Fi 下再看时重新预取。false = 不限制档位。
     */
    public void prefetchAsync(String url, boolean requireHigh) {
        if (lookup(url).isPresent()) {
            return;
        }
        inFlight.computeIfAbsent(url, u -> {
            CompletableFuture<Path> f = CompletableFuture.supplyAsync(() -> {
                try {
                    return downloadToDisk(u, requireHigh);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, prefetchExecutor);
            f.whenComplete((p, err) -> {
                inFlight.remove(u, f);
                if (err != null) {
                    log.debug("media prefetch failed ({}): {}", u, err.getMessage());
                } else {
                    log.info("media cached: {}", u);
                }
            });
            return f;
        });
    }

    /** 后台预热(不限制档位;兼容既有调用)。 */
    public void prefetchAsync(String url) {
        prefetchAsync(url, false);
    }

    /**
     * 实时观看时自动缓存原片:前端正在看某媒体(压缩流)时调用。
     *
     * <p>策略:仅当手机在 Wi-Fi(不计费)时执行——蜂窝下预取原片会偷偷烧流量。
     * 预取的是原始 URL(原图/原片),与观看用的压缩流互不冲突;单飞去重,
     * 失败静默(观看本身不受影响)。
     */
    public void prefetchOriginalOnView(String url) {
        if (router == null || !router.phoneOnWifi()) {
            return;
        }
        prefetchAsync(url, true);
    }

    /**
     * 画廊列表预取(2026-09-17):工具结果封装出画廊块时调用,后台缓存
     * **播放流**(视频 /video 压缩流、图片 /content)——用户还在看列表的
     * 时间窗口里把视频转码(手机端首次 10-15s)与传输都做完,点开秒播。
     *
     * <p>与 [prefetchOriginalOnView] 的区别:这里要的就是"点开时播放的
     * 那个版本"(档位压缩流),不是原片;网络策略相同——仅 Wi-Fi 执行。
     * 若已缓存的是省流量档(low,蜂窝下看过的),回到 Wi-Fi 后重拉覆盖。
     */
    public void prefetchPlaybackStream(String url) {
        if (router == null || !router.phoneOnWifi()) {
            return;
        }
        Optional<CacheEntry> hit = lookup(url);
        if (hit.isEmpty()) {
            prefetchAsync(url, false);
        } else if ("low".equalsIgnoreCase(hit.get().quality())) {
            // 蜂窝下看过的低清版本:Wi-Fi 下重拉高清版覆盖(秒播 + 画质都对)
            refreshAsync(url);
        }
    }

    /** 当前是否允许原片预取(手机在 Wi-Fi);供前端/接口展示。 */
    public boolean originalPrefetchAllowed() {
        return router != null && router.phoneOnWifi();
    }

    /**
     * 若有同 URL 的后台预取在途,等待其完成(最多 timeoutMs)并返回缓存条目。
     *
     * <p>为什么需要:用户点开"正在预取中"的视频时,若直接回源会发一个重复
     * 请求,排在手机转码队列后面(前面还有别的预取任务)——反而更慢。
     * 加入在途预取 = 等它完成直接读盘,且不浪费手机 CPU 转第二遍。
     *
     * @return 完成后的缓存条目;无在途预取/超时/失败返回 empty(调用方回源)
     */
    public Optional<CacheEntry> awaitPrefetch(String url, long timeoutMs) {
        CompletableFuture<Path> f = inFlight.get(url);
        if (f == null) {
            return Optional.empty();
        }
        try {
            f.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
            return lookup(url);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** 链路状态描述(排障:当前公网/局域网、手机网络)。 */
    public String routerStatus() {
        return router == null ? "disabled" : router.status();
    }

    /**
     * 强制刷新缓存条目(已有低档位缓存时重新拉取当前档位)。
     *
     * <p>场景:用户在蜂窝下看过一张图(缓存的是降采样版),回家(Wi-Fi)再看——
     * 此时缓存的 low 档位版本不满足「原片」语义,后台重拉 high 版覆盖。
     * 单飞去重;失败保留旧缓存(降级可用)。
     */
    public void refreshAsync(String url) {
        inFlight.computeIfAbsent(url, u -> {
            CompletableFuture<Path> f = CompletableFuture.supplyAsync(() -> {
                try {
                    return downloadToDisk(u, true);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, prefetchExecutor);
            f.whenComplete((p, err) -> {
                inFlight.remove(u, f);
                if (err != null) {
                    log.debug("media refresh failed ({}): {}", u, err.getMessage());
                } else {
                    log.info("media refreshed to original: {}", u);
                }
            });
            return f;
        });
    }

    /** 整文件下载到缓存(阻塞;失败删 .part)。 */
    private Path downloadToDisk(String url, boolean requireHigh) throws IOException {
        UpstreamStream u = openUpstream(url, null);
        if (u.body() == null) {
            throw new UpstreamException(u.status(), friendlyError(u));
        }
        // requireHigh 语义:手机上报 low(降采样)说明现在拿不到原片,放弃本次;
        // 档位未知(旧版手机无该头)时按可用处理——旧版 content 端点本就出原图
        if (requireHigh && "low".equalsIgnoreCase(u.quality())) {
            try (InputStream in = u.body()) {
                // 必须排空关闭,否则连接泄漏
                in.transferTo(OutputStream.nullOutputStream());
            }
            throw new IOException("手机当前为省流量档(low),跳过原片预取");
        }
        String key = key(url);
        Path target = cacheDir.resolve(key + ".bin");
        Path part = cacheDir.resolve(key + "." + System.nanoTime() + ".part");
        try (InputStream in = u.body(); OutputStream fileOut = Files.newOutputStream(part)) {
            in.transferTo(fileOut);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(part);
            } catch (Exception ignored) {
                // 删除失败留给启动清理
            }
            throw e;
        }
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        writeMeta(key, u.contentType(), url, u.quality(), u.phoneKind());
        evictIfNeeded();
        return target;
    }

    // ---------- 内部 ----------

    /**
     * 取共享 HttpClient(keep-alive 连接池)。按代理配置键控:
     * 代理开关/地址变化时自动换新实例;同配置下所有请求复用同一池
     * (此前每请求 new 一个,连接/TLS 握手开销全白付)。
     *
     * <p>强制 HTTP/1.1:JDK HttpClient 对**明文 http** 默认先发 h2c 升级
     * (Upgrade: h2c 头),中继的 upgrade 处理器会把这类 socket 当非法
     * WebSocket 销毁——局域网链路(明文)下所有请求都会失败(实测踩坑)。
     */
    private HttpClient buildClient(String url) {
        InetSocketAddress proxy = ProxySettingsHolder.addressFor(url);
        String key = proxy == null ? "direct" : proxy.getHostString() + ":" + proxy.getPort();
        return clients.computeIfAbsent(key, k -> {
            HttpClient.Builder b = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(15));
            if (proxy != null) {
                b.proxy(ProxySelector.of(proxy));
            }
            return b.build();
        });
    }

    /** 缓存键:URL 的 sha256(十六进制)。 */
    static String key(String url) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            // SHA-256 必然可用;兜底走 hashCode 不至于崩
            return Integer.toHexString(url.hashCode());
        }
    }

    /** 缓存元数据:Content-Type + 来源 URL + 出图档位(high/low) + 手机网络类型。 */
    private record Meta(String contentType, String url, String quality, String phoneKind) {
    }

    private void writeMeta(String key, String contentType, String url, String quality, String phoneKind) {
        try {
            Files.writeString(cacheDir.resolve(key + ".meta"),
                    contentType + "\n" + url + "\n"
                            + (quality == null ? "" : quality) + "\n"
                            + (phoneKind == null ? "" : phoneKind) + "\n",
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("media meta write failed ({}): {}", key, e.getMessage());
        }
    }

    private Meta readMeta(String key) {
        try {
            List<String> lines = Files.readAllLines(cacheDir.resolve(key + ".meta"), StandardCharsets.UTF_8);
            String ct = lines.size() > 0 && !lines.get(0).isBlank() ? lines.get(0).trim() : "application/octet-stream";
            String url = lines.size() > 1 ? lines.get(1).trim() : "";
            String quality = lines.size() > 2 && !lines.get(2).isBlank() ? lines.get(2).trim() : null;
            String kind = lines.size() > 3 && !lines.get(3).isBlank() ? lines.get(3).trim() : null;
            return new Meta(ct, url, quality, kind);
        } catch (Exception e) {
            // meta 缺失:按通用二进制流服务
            return new Meta("application/octet-stream", "", null, null);
        }
    }

    /** LRU 淘汰:总量超上限时按 mtime(最近访问)从旧到新清理到 90%。 */
    private synchronized void evictIfNeeded() {
        try {
            List<Path> bins = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(cacheDir, "*.bin")) {
                for (Path p : ds) {
                    bins.add(p);
                }
            }
            long total = 0;
            for (Path p : bins) {
                try {
                    total += Files.size(p);
                } catch (IOException ignored) {
                    // 统计失败按 0 计
                }
            }
            if (total <= maxBytes) {
                return;
            }
            long target = (long) (maxBytes * 0.9);
            bins.sort(Comparator.comparingLong(p -> {
                try {
                    return Files.getLastModifiedTime(p).toMillis();
                } catch (IOException e) {
                    return 0L;
                }
            }));
            for (Path p : bins) {
                if (total <= target) {
                    break;
                }
                try {
                    long sz = Files.size(p);
                    String name = p.getFileName().toString();
                    String k = name.substring(0, name.length() - ".bin".length());
                    Files.deleteIfExists(p);
                    Files.deleteIfExists(cacheDir.resolve(k + ".meta"));
                    total -= sz;
                    log.info("media cache evicted: {} ({} bytes)", name, sz);
                } catch (IOException ignored) {
                    // 单个删除失败跳过
                }
            }
        } catch (Exception e) {
            log.debug("media cache evict failed: {}", e.getMessage());
        }
    }

    /** 启动清理:超 1 小时的 .part 孤儿(进程被杀留下的未完成下载)。 */
    private void cleanupStaleParts() {
        long cutoff = System.currentTimeMillis() - STALE_PART_MILLIS;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(cacheDir, "*.part")) {
            for (Path p : ds) {
                try {
                    if (Files.getLastModifiedTime(p).toMillis() < cutoff) {
                        Files.deleteIfExists(p);
                    }
                } catch (IOException ignored) {
                    // 单个清理失败跳过
                }
            }
        } catch (IOException e) {
            log.debug("media stale part cleanup failed: {}", e.getMessage());
        }
    }
}
