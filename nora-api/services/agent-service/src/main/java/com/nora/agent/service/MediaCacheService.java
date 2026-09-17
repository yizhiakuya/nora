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

    public MediaCacheService(@Value("${nora.media-cache.dir:./data/media-cache}") String dir,
                             @Value("${nora.media-cache.max-bytes:" + DEFAULT_MAX_BYTES + "}") long maxBytes) {
        this.cacheDir = Path.of(dir);
        this.maxBytes = Math.max(MIN_MAX_BYTES, maxBytes);
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
    public record CacheEntry(Path file, String contentType, long size) {
    }

    /** 上游流式响应:打开连接并拿到响应头后交给调用方流式消费。 */
    public record UpstreamStream(int status, String contentType, long contentLength,
                                 String contentRange, InputStream body, String errorBody) {
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
            return Optional.of(new CacheEntry(bin, readContentType(key), Files.size(bin)));
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
     * @param range 可选 Range 头(视频 seek);null = 整体
     * @return 上游响应(>=400 时 body 为 null、errorBody 有内容)
     */
    public UpstreamStream openUpstream(String url, String range) throws IOException {
        HttpClient client = buildClient(url);
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
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
        if (resp.statusCode() >= 400) {
            String err;
            try (InputStream in = resp.body()) {
                err = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception e) {
                err = "";
            }
            return new UpstreamStream(resp.statusCode(), ct, len, cr, null, err);
        }
        return new UpstreamStream(resp.statusCode(), ct, len, cr, resp.body(), null);
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
     * @return 完整缓存条目(写盘失败时为 null,客户端不受影响)
     */
    public CacheEntry teeStream(String url, String contentType, InputStream upstream, OutputStream clientOut)
            throws IOException {
        String key = key(url);
        Path target = cacheDir.resolve(key + ".bin");
        Path part = cacheDir.resolve(key + "." + System.nanoTime() + ".part");
        long total = 0;
        try (InputStream in = upstream; OutputStream fileOut = Files.newOutputStream(part)) {
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
            writeMeta(key, contentType, url);
            evictIfNeeded();
            return new CacheEntry(target, contentType, total);
        } catch (IOException e) {
            log.warn("media cache persist failed ({}): {}", url, e.getMessage());
            return null;
        }
    }

    /** 后台预热:整文件下载入缓存(单飞;已缓存/在飞则跳过)。 */
    public void prefetchAsync(String url) {
        if (lookup(url).isPresent()) {
            return;
        }
        inFlight.computeIfAbsent(url, u -> {
            CompletableFuture<Path> f = CompletableFuture.supplyAsync(() -> {
                try {
                    return downloadToDisk(u);
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

    /** 整文件下载到缓存(阻塞;失败删 .part)。 */
    private Path downloadToDisk(String url) throws IOException {
        UpstreamStream u = openUpstream(url, null);
        if (u.body() == null) {
            throw new UpstreamException(u.status(), friendlyError(u));
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
        writeMeta(key, u.contentType(), url);
        evictIfNeeded();
        return target;
    }

    // ---------- 内部 ----------

    private HttpClient buildClient(String url) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15));
        InetSocketAddress proxy = ProxySettingsHolder.addressFor(url);
        if (proxy != null) {
            b.proxy(ProxySelector.of(proxy));
        }
        return b.build();
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

    private void writeMeta(String key, String contentType, String url) {
        try {
            Files.writeString(cacheDir.resolve(key + ".meta"),
                    contentType + "\n" + url + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.debug("media meta write failed ({}): {}", key, e.getMessage());
        }
    }

    private String readContentType(String key) {
        try {
            List<String> lines = Files.readAllLines(cacheDir.resolve(key + ".meta"), StandardCharsets.UTF_8);
            if (!lines.isEmpty() && !lines.get(0).isBlank()) {
                return lines.get(0).trim();
            }
        } catch (Exception ignored) {
            // meta 缺失:按通用二进制流服务
        }
        return "application/octet-stream";
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
