package com.nora.agent.service;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.common.http.ProxySettingsHolder;

/**
 * 中继链路自动选择(2026-09-17):媒体/MCP 请求「局域网优先、公网兜底」。
 *
 * <p>背景:手机相册中继(home.rainaki.top:8900)有两条可达路径——
 * <ul>
 *   <li>公网:经 nginx(TLS+限流)→ 中继,离家在外唯一可用,但绕路;</li>
 *   <li>局域网:中继在本网另有明文端口(如 192.168.0.109:8902),
 *       在家时直达内网千兆——省 TLS、省 nginx、省公网往返,快一个量级。</li>
 * </ul>
 *
 * <p>本类后台周期探测中继 /health(它自我描述 lanHttpEndpoints),逐个
 * 探测局域网端点可达性;可达则把「发往中继公网地址的 URL」重写为
 * 局域网地址(preferLan)。请求路径上只读缓存值,永不阻塞探测。
 *
 * <p>同时解析 /health 里中继上报的 <b>手机网络类型</b>(cache.phoneKind):
 * MediaCacheService 据此决定「实时观看时是否后台缓存原片」——只在手机
 * 处于 Wi-Fi(不计费)时预取,蜂窝下不偷偷烧流量。
 *
 * <p>失败自愈:LAN 探测(media openUpstream / MCP 调用)报错时调用
 * {@link #reportLanFailure()},立即回退公网直到下次探测成功。
 *
 * <p>配置(application.yml / .env.local):
 * <pre>
 * nora.media-cache.relay-base: https://home.rainaki.top:8900   # 空 = 功能关闭
 * nora.media-cache.lan-probe-seconds: 120                      # 探测周期
 * </pre>
 */
@Component
public class RelayMediaRouter {

    private static final Logger log = LoggerFactory.getLogger(RelayMediaRouter.class);

    private final ObjectMapper objectMapper;
    /** 中继公网基址(如 https://home.rainaki.top:8900);空 = 功能关闭。 */
    private final String relayBase;
    private final long probeSeconds;

    /** 当前可用的局域网基址(http://ip:port);null = 不可用/未探测到。 */
    private volatile String lanBase;
    /** 最近一次探测成功写入 lanBase 的时间戳。 */
    private volatile long lanCheckedAt;
    /** 手机当前网络是否 Wi-Fi(来自中继 /health 的 cache.phoneKind)。 */
    private volatile boolean phoneOnWifi;
    /** 最近一次 /health 解析到的完整响应(排障用)。 */
    private volatile String lastProbeNote = "未探测";

    public RelayMediaRouter(ObjectMapper objectMapper,
                            @Value("${nora.media-cache.relay-base:}") String relayBase,
                            @Value("${nora.media-cache.lan-probe-seconds:120}") long probeSeconds) {
        this.objectMapper = objectMapper;
        this.relayBase = relayBase == null ? "" : relayBase.trim();
        this.probeSeconds = Math.max(30, probeSeconds);
        if (!this.relayBase.isBlank()) {
            Thread t = new Thread(this::pollLoop, "relay-lan-probe");
            t.setDaemon(true);
            t.start();
            log.info("RelayMediaRouter 启动: relayBase={}, 探测周期 {}s", this.relayBase, this.probeSeconds);
        }
    }

    /** 后台探测循环:立即探测一次,之后按周期重探(探测失败也继续,链路可能恢复)。 */
    private void pollLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                probeOnce();
            } catch (Exception e) {
                lastProbeNote = "探测异常: " + e.getMessage();
                log.warn("relay probe failed: {}", e.toString());
            }
            try {
                Thread.sleep(probeSeconds * 1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 探测一次:读中继 /health → 解析 lanHttpEndpoints 与 phoneKind → 逐个验证可达性。 */
    private void probeOnce() {
        String healthUrl = relayBase.replaceAll("/+$", "") + "/health";
        // 强制 HTTP/1.1(同 MediaCacheService.buildClient 的说明):JDK HttpClient
        // 对明文 http 默认发 h2c 升级,中继会销毁该 socket。
        HttpClient.Builder cb = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(4));
        InetSocketAddress proxy = ProxySettingsHolder.addressFor(healthUrl);
        if (proxy != null) {
            cb.proxy(ProxySelector.of(proxy));
        }
        HttpClient client = cb.build();
        try {
            HttpResponse<String> resp = client.send(
                    HttpRequest.newBuilder().uri(URI.create(healthUrl))
                            .timeout(Duration.ofSeconds(6)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                lastProbeNote = "中继 /health 返回 " + resp.statusCode();
                return;
            }
            JsonNode root = objectMapper.readTree(resp.body());
            // 手机网络类型(决定是否预取原片)
            String kind = root.path("cache").path("phoneKind").asText("");
            phoneOnWifi = "wifi".equalsIgnoreCase(kind);
            // 局域网端点:逐个探测,第一个可达的即启用
            List<String> candidates = new ArrayList<>();
            for (JsonNode n : root.path("lanHttpEndpoints")) {
                String s = n.asText("").trim();
                if (!s.isEmpty()) candidates.add(s);
            }
            if (candidates.isEmpty()) {
                if (lanBase != null) {
                    log.info("中继未提供局域网端点,回退公网链路");
                }
                lanBase = null;
                lanCheckedAt = System.currentTimeMillis();
                lastProbeNote = "中继未开启局域网端口";
                return;
            }
            for (String cand : candidates) {
                if (probeLan(cand)) {
                    if (!cand.equals(lanBase)) {
                        log.info("局域网链路可用: {} → 媒体/MCP 请求将走内网直连", cand);
                    }
                    lanBase = cand;
                    lanCheckedAt = System.currentTimeMillis();
                    lastProbeNote = "局域网可用: " + cand;
                    return;
                }
            }
            if (lanBase != null) {
                log.info("局域网端点全部不可达,回退公网链路");
            } else {
                log.info("局域网端点不可达(首次探测): {}", String.join(",", candidates));
            }
            lanBase = null;
            lanCheckedAt = System.currentTimeMillis();
            lastProbeNote = "局域网端点不可达(" + String.join(",", candidates) + ")";
        } catch (Exception e) {
            // 公网都不可达:保持现状(可能只是瞬时抖动),下次再探
            lastProbeNote = "中继 /health 探测失败: " + e.getMessage();
        }
    }

    /** 验证局域网端点:GET <endpoint>/health,1.5s 超时,2xx 即可达。 */
    private boolean probeLan(String lanEndpoint) {
        try {
            // 必须强制 HTTP/1.1:JDK HttpClient 对明文 http 默认先发 h2c 升级
            // (Upgrade: h2c 头),而中继的 upgrade 处理器只认 WebSocket 路径,
            // 会把这类请求的 socket 销毁——探测全部失败(实测踩坑)。
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofMillis(1500))
                    .build();
            HttpResponse<Void> resp = client.send(
                    HttpRequest.newBuilder().uri(URI.create(lanEndpoint + "/health"))
                            .timeout(Duration.ofMillis(2500)).GET().build(),
                    HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 请求路径使用:把发往中继公网地址的 URL 重写为局域网地址。
     * 非中继地址/局域网不可用时原样返回。
     */
    public String preferLan(String url) {
        String base = lanBase;
        if (base == null || url == null || url.isBlank()) {
            return url;
        }
        try {
            URI u = URI.create(url);
            if (!sameRelayHost(u)) {
                return url;
            }
            URI lan = URI.create(base);
            String rewritten = lan.getScheme() + "://" + lan.getAuthority()
                    + (u.getRawPath() == null ? "" : u.getRawPath())
                    + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            return rewritten;
        } catch (Exception e) {
            return url;
        }
    }

    /** URL 主机是否属于中继(与 relayBase 的 host:port 一致;忽略协议)。 */
    private boolean sameRelayHost(URI u) {
        try {
            URI rb = URI.create(relayBase);
            if (rb.getHost() == null || u.getHost() == null) {
                return false;
            }
            int uPort = u.getPort() > 0 ? u.getPort() : defaultPort(u.getScheme());
            int rPort = rb.getPort() > 0 ? rb.getPort() : defaultPort(rb.getScheme());
            return rb.getHost().equalsIgnoreCase(u.getHost()) && rPort == uPort;
        } catch (Exception e) {
            return false;
        }
    }

    private static int defaultPort(String scheme) {
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    /** 局域网链路失败时调用:立即回退公网,直到下次探测成功。 */
    public void reportLanFailure() {
        if (lanBase != null) {
            log.info("局域网链路请求失败,立即回退公网: {}", lanBase);
            lanBase = null;
        }
    }

    /**
     * 从媒体响应头学习手机网络类型(X-Net-Kind):比周期探测更及时——
     * 手机刚切 Wi-Fi/蜂窝时,下一次媒体请求就能修正预取判断。
     */
    public void reportPhoneKind(String kind) {
        if (kind == null || kind.isBlank()) {
            return;
        }
        boolean wifi = "wifi".equalsIgnoreCase(kind.trim());
        if (wifi != phoneOnWifi) {
            log.info("手机网络类型: {} → {} (原片预取{})", phoneOnWifi ? "wifi" : "其他", kind,
                    wifi ? "可用" : "禁用");
            phoneOnWifi = wifi;
        }
    }

    /** 手机当前是否在 Wi-Fi(不计费网络)——媒体原片预取的判断依据。 */
    public boolean phoneOnWifi() {
        return phoneOnWifi;
    }

    /** 状态快照(排障/测试用)。 */
    public String status() {
        if (relayBase.isBlank()) {
            return "disabled (relay-base 未配置)";
        }
        return "relayBase=" + relayBase + ", lanBase=" + (lanBase == null ? "(公网)" : lanBase)
                + ", phoneWifi=" + phoneOnWifi
                + ", checkedAgoMs=" + (lanCheckedAt == 0 ? "-" : System.currentTimeMillis() - lanCheckedAt)
                + ", note=" + lastProbeNote;
    }
}
