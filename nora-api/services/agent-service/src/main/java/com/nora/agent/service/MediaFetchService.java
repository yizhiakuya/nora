package com.nora.agent.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.common.http.ProxySettingsHolder;

/**
 * 批量媒体拉取(2026-09-17):把「手机 MCP 清单 → 本地文件夹」的整条链路
 * 收进一个服务,agent 一次工具调用即可完成,不再自己拼 PowerShell 命令。
 *
 * <p>为什么需要它(实测事故):用户说「把最近一个月的相册整理出来」,
 * agent 只能对 407 个 URL 逐个跑 run_command 下载——不仅要 400+ 轮工具调用,
 * 还因为猜 API 路径触发远端 fail2ban 封 IP,最终整轮失败。批量拉取是
 * 「整理相册/备份媒体」类任务的基础设施,必须是一等工具能力。
 *
 * <p>链路设计:
 * <ol>
 *   <li><b>清单</b>:优先调手机的 {@code photos_export}(一次 500 条);
 *       不可用时回退 photos_search 分页(100/页);</li>
 *   <li><b>下载</b>:每条走 {@link RelayMediaRouter#preferLan} 重写
 *       (在家自动走内网 8902,公网兜底,失败自动回退);</li>
 *   <li><b>落盘</b>:流式写工作区(先 .part 后原子移动,不整读进内存——
 *       几百 MB 的视频才不会把堆打爆);</li>
 *   <li><b>进度</b>:{@link FetchProgressTracker} 汇总字节级进度
 *       (完成数/总数/在途字节/滑窗速率/ETA),经 {@link ProgressCallback}
 *       节流上报,接 ToolStepEmitter 的步骤事件原地刷新。</li>
 * </ol>
 *
 * <p>并发:固定 4 线程(隧道/中继对并发敏感,4 路足以吃满家用带宽;
 * 更高并发反而让手机端转码/读盘排队,实测吞吐不再提升)。
 */
@Service
public class MediaFetchService {

    private static final Logger log = LoggerFactory.getLogger(MediaFetchService.class);

    /** 单文件上限:媒体归档场景视频可达数百 MB(工作区 100MB 默认上限不够)。 */
    private static final long MAX_FILE_BYTES = 2L * 1024 * 1024 * 1024;
    /** 下载并发度(见类注释)。 */
    private static final int CONCURRENCY = 4;
    /** 单条下载超时(连接+响应头;响应体流式读不受此限)。 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    private final ObjectMapper objectMapper;
    private final AgentWorkspaceService workspaceService;
    private final McpServerService mcpServerService;
    private final RelayMediaRouter router;

    /** 共享 HttpClient 池:代理配置变化自动换新(键=direct 或 host:port)。 */
    private final java.util.concurrent.ConcurrentHashMap<String, HttpClient> clients =
            new java.util.concurrent.ConcurrentHashMap<>();

    public MediaFetchService(ObjectMapper objectMapper,
                             AgentWorkspaceService workspaceService,
                             McpServerService mcpServerService,
                             RelayMediaRouter router) {
        this.objectMapper = objectMapper;
        this.workspaceService = workspaceService;
        this.mcpServerService = mcpServerService;
        this.router = router;
    }

    /** 一次批量拉取的结果统计。 */
    public record FetchReport(int total, int downloaded, int skipped, int failed, List<String> errors,
                              /** 清单为空时的可操作提示(来自手机端,如「相册不存在,可用:…」);可为 null。 */
                              String hint,
                              /** 用户取消导致的中断(true=未完成,下载器已停止) */
                              boolean cancelled) {
        public FetchReport(int total, int downloaded, int skipped, int failed, List<String> errors) {
            this(total, downloaded, skipped, failed, errors, null, false);
        }

        public FetchReport(int total, int downloaded, int skipped, int failed, List<String> errors,
                           String hint) {
            this(total, downloaded, skipped, failed, errors, hint, false);
        }
    }

    /**
     * 批量拉取入口:从手机 MCP 拿清单,并发下载到工作区文件夹。
     *
     * @param mcpServerName 手机 MCP 服务器名(如 "phone")
     * @param from          起始时间(ISO 8601;null=不限)
     * @param to            结束时间(ISO 8601;null=不限)
     * @param album         相册名(null=全部)
     * @param type          photo/video/all
     * @param folder        工作区目标目录(相对路径,如 "photos/2026-08")
     * @param quality       图片质量档(high=原片;null=清单给什么用什么)
     * @param progress      进度回调(可为 null;节流后调用,可能来自下载工作线程)
     * @param cancelled     取消查询(可为 null):返回 true 时下载器尽快停止。
     *                      用轮询式信号而非线程中断——实测中断标志会在下游
     *                      (JDBC/SSE)被意外消费,导致「停止生成」杀不掉下载。
     * @return 统计报告
     */
    public FetchReport fetchFromPhone(String mcpServerName, String from, String to, String album,
                                      String type, String folder, String quality,
                                      ProgressCallback progress,
                                      java.util.function.BooleanSupplier cancelled) {
        McpServerService.RawServer server = findServer(mcpServerName);
        if (server == null) {
            return new FetchReport(0, 0, 0, 1,
                    List.of("找不到名为「" + mcpServerName + "」的 MCP 服务器——先用 manage_mcp list 确认已注册的手机服务器名"));
        }
        if (progress != null) {
            progress.onProgress(FetchProgress.listing(0));
        }
        // 1) 清单:photos_export 一次拿全(500 上限);老版本手机端没有该工具时回退 search 分页
        ListResult listed = listViaExport(server, from, to, album, type, progress);
        if (listed == null) {
            listed = listViaSearch(server, from, to, album, type, progress);
        }
        if (listed == null) {
            return new FetchReport(0, 0, 0, 1,
                    List.of("拉取媒体清单失败——确认手机 App 在线且隧道已连接(先调 mcp__" + mcpServerName + "__photos_export 或 photos_search 验证)"));
        }
        if (listed.items().isEmpty()) {
            // 把手机端的可操作提示(如「相册不存在,可用相册:…」)原样交给模型,
            // 它才能自纠参数重试——此前吞掉提示导致模型只能放弃(实测踩过)。
            return new FetchReport(0, 0, 0, 0, List.of(), listed.hint());
        }
        return downloadAll(listed.items(), folder, quality, progress, cancelled);
    }

    /** 兼容重载:无取消信号(测试/内部调用)。 */
    public FetchReport fetchFromPhone(String mcpServerName, String from, String to, String album,
                                      String type, String folder, String quality,
                                      ProgressCallback progress) {
        return fetchFromPhone(mcpServerName, from, to, album, type, folder, quality, progress, null);
    }

    /** 清单结果:条目 + 手机端附带的提示(相册不存在等场景);可为 null 提示。 */
    private record ListResult(List<MediaEntry> items, String hint) {
    }

    /**
     * 批量拉取的进度快照(节流后上报)。
     *
     * @param phase        {@code listing}(获取清单)/ {@code downloading}
     * @param done         已完成文件数(含跳过/失败)
     * @param total        总文件数;0=清单阶段未知
     * @param currentIndex 当前文件序号(1-based;并发时=最近开始的那个)
     * @param currentFile  当前正在下载的文件名
     * @param active       并行下载中的文件数
     * @param bytesDone    已传输字节(完成文件全量 + 在途文件已读部分)
     * @param bytesTotal   清单声明的总字节(sizeBytes 求和;含未知大小时偏小)
     * @param bytesPerSec  当前速率(6s 滑窗;样本不足时为 null)
     * @param etaSeconds   预计剩余秒数(字节速率优先,退化按文件均速;null=未知)
     */
    public record FetchProgress(
            String phase, int done, int total, int currentIndex, String currentFile, int active,
            long bytesDone, long bytesTotal, Long bytesPerSec, Long etaSeconds) {

        static FetchProgress listing(int fetched) {
            return new FetchProgress("listing", fetched, 0, 0, null, 0, 0, 0, null, null);
        }
    }

    /** 进度回调(节流后调用,可能来自下载工作线程——实现方须线程安全)。 */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(FetchProgress progress);
    }

    /**
     * 直接下载一组显式 URL(调用方已从 MCP 清单拿到;photos_export 的 items.url)。
     *
     * @param items url + filename 列表
     */
    public FetchReport downloadItems(List<UrlItem> items, String folder, String quality,
                                     ProgressCallback progress) {
        List<MediaEntry> entries = new ArrayList<>();
        for (UrlItem it : items) {
            entries.add(new MediaEntry(it.url(), it.filename(), it.sizeBytes()));
        }
        return downloadAll(entries, folder, quality, progress, null);
    }

    /** 下载条目:URL + 目标文件名(大小未知=0)。 */
    public record UrlItem(String url, String filename, long sizeBytes) {
        public UrlItem(String url, String filename) {
            this(url, filename, 0);
        }
    }

    /** 清单条目(url + 文件名 + 声明大小;大小未知=0)。 */
    private record MediaEntry(String url, String filename, long sizeBytes) {
    }

    /** 找手机 MCP 服务器(按名精确匹配;未找到返回 null)。 */
    private McpServerService.RawServer findServer(String name) {
        for (McpServerService.RawServer s : mcpServerService.rawEnabled()) {
            if (s.name().equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    /**
     * 优先 photos_export(批量清单,500/页自动翻页);工具不存在(老版本 App)返回 null。
     * 翻页期间按页上报 listing 进度(用户能看到"正在获取清单 n 条")。
     */
    private ListResult listViaExport(McpServerService.RawServer server,
                                     String from, String to, String album, String type,
                                     ProgressCallback progress) {
        List<MediaEntry> out = new ArrayList<>();
        String hint = null;
        int offset = 0;
        final int pageSize = 500;
        // 硬上限 5000 防失控(超出时把已取到的部分交给下载,并在日志说明)
        while (offset < 5000) {
            try {
                com.fasterxml.jackson.databind.node.ObjectNode args = objectMapper.createObjectNode();
                if (from != null && !from.isBlank()) {
                    args.put("from", from);
                }
                if (to != null && !to.isBlank()) {
                    args.put("to", to);
                }
                if (album != null && !album.isBlank()) {
                    args.put("album", album);
                }
                if (type != null && !type.isBlank()) {
                    args.put("type", type);
                }
                args.put("limit", pageSize);
                args.put("offset", offset);
                McpServerService.McpToolResult result =
                        mcpServerService.callToolRich(server.id(), "photos_export", args.toString());
                if (result.isError()) {
                    // 老版本 App 无此工具(首个请求即失败)→ 回退;翻页中途失败则交付已取部分
                    return offset == 0 ? null : new ListResult(out, null);
                }
                JsonNode root = objectMapper.readTree(result.text());
                hint = root.path("hint").asText(null);
                JsonNode items = root.path("items");
                int count = root.path("count").asInt(0);
                int total = root.path("total").asInt(count);
                if (items.isArray()) {
                    for (JsonNode it : items) {
                        String url = it.path("url").asText("");
                        if (!url.isBlank()) {
                            out.add(new MediaEntry(url, it.path("filename").asText("media"),
                                    it.path("sizeBytes").asLong(0)));
                        }
                    }
                }
                if (progress != null) {
                    progress.onProgress(FetchProgress.listing(out.size()));
                }
                if (count < pageSize || offset + count >= total) {
                    break;
                }
                offset += count;
            } catch (Exception e) {
                log.warn("photos_export 翻页失败(offset={}): {}", offset, e.getMessage());
                return offset == 0 ? null : new ListResult(out, null);
            }
        }
        if (offset >= 5000) {
            log.warn("photos_export 清单超过 5000 条,已截断(offset 上限)");
        }
        return new ListResult(out, hint);
    }

    /**
     * 回退路径:photos_search 分页(100/页)直到 total 取完(硬上限 2000 防失控)。
     */
    private ListResult listViaSearch(McpServerService.RawServer server,
                                     String from, String to, String album, String type,
                                     ProgressCallback progress) {
        List<MediaEntry> out = new ArrayList<>();
        String hint = null;
        int offset = 0;
        final int pageSize = 100;
        while (offset < 2000) {
            try {
                com.fasterxml.jackson.databind.node.ObjectNode args = objectMapper.createObjectNode();
                if (from != null && !from.isBlank()) {
                    args.put("from", from);
                }
                if (to != null && !to.isBlank()) {
                    args.put("to", to);
                }
                if (album != null && !album.isBlank()) {
                    args.put("album", album);
                }
                if (type != null && !type.isBlank()) {
                    args.put("type", type);
                }
                args.put("limit", pageSize);
                args.put("offset", offset);
                McpServerService.McpToolResult result =
                        mcpServerService.callToolRich(server.id(), "photos_search", args.toString());
                if (result.isError()) {
                    return out.isEmpty() ? null : new ListResult(out, null);
                }
                JsonNode root = objectMapper.readTree(result.text());
                hint = root.path("hint").asText(null);
                JsonNode items = root.path("items");
                int count = root.path("count").asInt(0);
                if (items.isArray()) {
                    for (JsonNode it : items) {
                        // 导出用原片:视频 contentUrl 指向压缩流,换成 /content
                        String url = it.path("contentUrl").asText("");
                        if (url.isBlank()) {
                            continue;
                        }
                        url = url.replace("/video?", "/content?").replace("/video/", "/content/");
                        if (url.endsWith("/video")) {
                            url = url.substring(0, url.length() - "/video".length()) + "/content";
                        }
                        out.add(new MediaEntry(url, it.path("filename").asText("media"),
                                it.path("sizeBytes").asLong(0)));
                    }
                }
                if (progress != null) {
                    progress.onProgress(FetchProgress.listing(out.size()));
                }
                if (count < pageSize) {
                    break;
                }
                offset += count;
            } catch (Exception e) {
                log.warn("photos_search 分页失败(offset={}): {}", offset, e.getMessage());
                return out.isEmpty() ? null : new ListResult(out, null);
            }
        }
        return new ListResult(out, hint);
    }

    /**
     * 并发下载全部条目(4 路),返回统计。
     *
     * <p>进度模型(2026-09-17):worker 线程只做原子计数(完成数/在途字节),
     * 由**本方法所在线程**按 {@link #PROGRESS_INTERVAL_MS} 轮询快照并回调——
     * 与 TerminalService 实时输出同一模式,回调永远在编排线程执行,
     * SSE/steps 列表无跨线程竞争(回调里会 steps.add + saveStep)。
     *
     * <p>取消(2026-09-17 修复):用户「停止生成」→ 轮询 {@code cancelled}
     * (TurnCancellation 标志,不可被下游消费)→ 停止派发新任务 + worker
     * 每块字节检查标志快速退出。此前只依赖线程中断,标志被 JDBC/SSE 链路
     * 意外消费后下载会跑到底(实测:取消后仍下完 10GB)。
     */
    private FetchReport downloadAll(List<MediaEntry> entries, String folder, String quality,
                                    ProgressCallback progress, java.util.function.BooleanSupplier cancelled) {
        String targetDir = (folder == null || folder.isBlank()) ? "imports" : folder.trim();
        int total = entries.size();
        // 计数器按调用独立(服务是单例,不能用实例字段——并发调用会互相踩)
        AtomicInteger done = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicInteger skipped = new AtomicInteger();
        AtomicInteger started = new AtomicInteger();
        AtomicLong bytesDone = new AtomicLong();
        // 每个条目的声明大小(进度分母);未知=0
        long bytesTotal = entries.stream().mapToLong(MediaEntry::sizeBytes).sum();
        // 当前在途文件名(worker 写入、汇报线程读取;短字符串读写无需锁)
        java.util.concurrent.atomic.AtomicReference<String> currentFile =
                new java.util.concurrent.atomic.AtomicReference<>();
        List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
        // 速率滑窗:每 (时刻, 累计字节) 一个样本,汇报线程独占维护
        Deque<long[]> samples = new ArrayDeque<>();
        long startMs = System.currentTimeMillis();
        ExecutorService pool = Executors.newFixedThreadPool(CONCURRENCY, r -> {
            Thread t = new Thread(r, "media-fetch");
            t.setDaemon(true);
            return t;
        });
        CountDownLatch finished = new CountDownLatch(total);
        try {
            for (MediaEntry entry : entries) {
                if (isCancelled(cancelled)) {
                    // 派发前检查:取消后不再开始新任务(未派发的条目直接放弃)
                    break;
                }
                pool.submit(() -> {
                    String name = sanitizeFilename(entry.filename());
                    int myIndex = started.incrementAndGet();
                    currentFile.set(name);
                    try {
                        String path = targetDir + "/" + name;
                        if (workspaceService.existsAny(path)) {
                            skipped.incrementAndGet();
                            // 跳过也算"已传输"(进度条不因跳过卡住):计入声明大小
                            bytesDone.addAndGet(Math.max(0, entry.sizeBytes()));
                        } else {
                            downloadOne(entry.url(), path, quality, n -> bytesDone.addAndGet(n), cancelled);
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                        errors.add(Texts.abbreviate(entry.filename() + ": " + e.getMessage(), 160));
                    } finally {
                        done.incrementAndGet();
                        finished.countDown();
                    }
                });
            }
            // 轮询汇报:每 700ms 一次快照(含速率/ETA);下载完成或取消即退出
            boolean wasCancelled = false;
            while (!finished.await(PROGRESS_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                if (isCancelled(cancelled)) {
                    wasCancelled = true;
                    break;
                }
                if (progress != null) {
                    progress.onProgress(snapshot(done.get(), total, started.get(), currentFile.get(),
                            Math.min(CONCURRENCY, total - done.get()), bytesDone.get(), bytesTotal,
                            samples, startMs));
                }
            }
            if (wasCancelled) {
                // 取消:worker 会按标志尽快退出(块粒度);不再等全部收尾,
                // 立即关池并回报部分统计
                pool.shutdownNow();
                return new FetchReport(total, done.get() - failed.get() - skipped.get(),
                        skipped.get(), failed.get(), errors, null, true);
            }
            if (progress != null) {
                // 终态快照(速率/ETA 置空,完成数即总数)
                progress.onProgress(new FetchProgress("downloading", total, total, total,
                        null, 0, bytesDone.get(), bytesTotal, null, null));
            }
        } catch (InterruptedException e) {
            // 用户取消(线程中断兜底路径):中断所有 worker,恢复中断标志
            pool.shutdownNow();
            Thread.currentThread().interrupt();
            return new FetchReport(total, done.get() - failed.get() - skipped.get(), skipped.get(),
                    failed.get(), errors, null, true);
        } finally {
            pool.shutdownNow();
        }
        return new FetchReport(total, total - failed.get() - skipped.get(), skipped.get(),
                failed.get(), errors);
    }

    /** 取消信号查询(空 supplier 视为未取消)。 */
    private static boolean isCancelled(java.util.function.BooleanSupplier cancelled) {
        try {
            return cancelled != null && cancelled.getAsBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    /** 进度汇报间隔(与 TerminalService 的 1.2s 折中:批量任务更频繁些体验更好)。 */
    private static final long PROGRESS_INTERVAL_MS = 700;

    /** 组装一次进度快照(含滑窗速率与 ETA;仅汇报线程调用,可独占维护 samples)。 */
    private FetchProgress snapshot(int done, int total, int startedIndex, String currentFile, int active,
                                   long bytesDone, long bytesTotal, Deque<long[]> samples, long startMs) {
        long now = System.currentTimeMillis();
        samples.addLast(new long[]{now, bytesDone});
        // 窗口 6s:足够平滑又跟得上速度变化
        while (!samples.isEmpty() && now - samples.peekFirst()[0] > 6000) {
            samples.removeFirst();
        }
        Long rate = null;
        if (samples.size() >= 2) {
            long[] first = samples.peekFirst();
            long[] last = samples.peekLast();
            long dt = last[0] - first[0];
            if (dt >= 300) {
                rate = Math.max(0, (last[1] - first[1]) * 1000 / dt);
            }
        }
        Long eta = null;
        if (rate != null && rate > 0 && bytesTotal > 0 && bytesDone > 0) {
            // 字节口径:声明总量按已传字节的比例修正(未知大小条目会让分母偏小,
            // 用"已传/声明已传"的覆盖比例放大——比裸算保守,不给出过分乐观的数)
            long remainBytes = Math.max(0, bytesTotal - bytesDone);
            eta = remainBytes / rate;
        } else if (done > 0) {
            // 退化口径:文件均速(样本不足的前几秒/未知大小场景)
            long elapsed = now - startMs;
            if (elapsed > 500) {
                eta = Math.max(0, (total - done)) * elapsed / done / 1000;
            }
        }
        return new FetchProgress("downloading", done, total, Math.min(startedIndex, total), currentFile,
                Math.max(0, active), bytesDone, bytesTotal, rate, eta);
    }

    /** 下载单条:链路自动选择 + 流式落盘(不整读进内存);onBytes 按块上报已读字节。 */
    private void downloadOne(String rawUrl, String path, String quality, LongConsumer onBytes,
                             java.util.function.BooleanSupplier cancelled) throws IOException {
        // 图片归档要原片:清单 URL 带上 q=high(手机端按档位出图;视频忽略该参数)
        String url = rawUrl;
        if ("high".equalsIgnoreCase(quality) && url.contains("/content")) {
            url = url + (url.contains("?") ? "&" : "?") + "q=high";
        }
        String effective = router == null ? url : router.preferLan(url);
        try {
            streamToWorkspace(effective, path, onBytes, cancelled);
        } catch (IOException e) {
            if (!effective.equals(url) && router != null) {
                router.reportLanFailure();
                log.info("局域网拉取失败,回退公网: {} ({})", url, e.getMessage());
                streamToWorkspace(url, path, onBytes, cancelled);
            } else {
                throw e;
            }
        }
    }

    /** 打开上游连接并流式写入工作区(字节级进度经 onBytes 上报;取消按块检查)。 */
    private void streamToWorkspace(String url, String path, LongConsumer onBytes,
                                   java.util.function.BooleanSupplier cancelled) throws IOException {
        HttpClient client = buildClient(url);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(90)) // 等到响应头;body 流式不受此限
                .header("User-Agent", "Nora-MediaFetch/1.0")
                .GET().build();
        HttpResponse<InputStream> resp;
        try {
            resp = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (Exception e) {
            throw new IOException("连接失败: " + e.getMessage(), e);
        }
        if (resp.statusCode() >= 400) {
            try (InputStream in = resp.body()) {
                in.readAllBytes();
            } catch (Exception ignored) {
            }
            throw new IOException("HTTP " + resp.statusCode());
        }
        try (InputStream in = resp.body()) {
            workspaceService.writeStreamAny(path, counting(in, onBytes, cancelled), MAX_FILE_BYTES);
        }
    }

    /** 计数流包装:每次读取上报字节数 + 检查取消信号(进度统计;不改变数据流语义)。 */
    private static InputStream counting(InputStream in, LongConsumer onBytes,
                                        java.util.function.BooleanSupplier cancelled) {
        return new java.io.FilterInputStream(in) {
            @Override
            public int read() throws IOException {
                if (isCancelled(cancelled)) {
                    throw new IOException("用户已取消下载");
                }
                int b = super.read();
                if (b >= 0) {
                    onBytes.accept(1);
                }
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                if (isCancelled(cancelled)) {
                    throw new IOException("用户已取消下载");
                }
                int n = super.read(buf, off, len);
                if (n > 0) {
                    onBytes.accept(n);
                }
                return n;
            }
        };
    }

    /** HttpClient 池:代理配置运行时可变,键 = "direct" 或 "host:port"。 */
    private HttpClient buildClient(String url) {
        InetSocketAddress proxy = ProxySettingsHolder.addressFor(url);
        String key = proxy == null ? "direct" : proxy.getHostString() + ":" + proxy.getPort();
        return clients.computeIfAbsent(key, k -> {
            HttpClient.Builder b = HttpClient.newBuilder()
                    // 强制 HTTP/1.1(同 MediaCacheService 的说明):JDK HttpClient 对
                    // 明文 http 默认发 h2c 升级,中继会销毁该 socket——局域网链路
                    // (明文 8902)下所有请求都会失败(实测踩坑)。
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(CONNECT_TIMEOUT);
            if (proxy != null) {
                b.proxy(ProxySelector.of(proxy));
            }
            return b.build();
        });
    }

    /**
     * 文件名清洗:去掉路径分隔符与危险字符(相册文件名可能含斜杠等),
     * 保留中文;空名兜底为 media。
     */
    static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            return "media";
        }
        String cleaned = name.replace('\\', '_').replace('/', '_')
                .replaceAll("[\\r\\n\\t]", "_")
                .replaceAll("[:*?\"<>|]", "_");
        cleaned = cleaned.strip();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            return "media";
        }
        // 极端长名截断(保留扩展名)
        if (cleaned.length() > 120) {
            int dot = cleaned.lastIndexOf('.');
            String ext = dot > 0 ? cleaned.substring(dot) : "";
            cleaned = cleaned.substring(0, 100) + ext;
        }
        return cleaned;
    }
}
