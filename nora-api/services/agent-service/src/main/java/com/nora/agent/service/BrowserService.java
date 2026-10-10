package com.nora.agent.service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.AriaSnapshotMode;
import com.microsoft.playwright.options.MouseButton;
import com.microsoft.playwright.options.Proxy;
import com.microsoft.playwright.options.WaitUntilState;

import jakarta.annotation.PreDestroy;

@Service
public class BrowserService {
    private static final Logger log = LoggerFactory.getLogger(BrowserService.class);
    private final BrowserEvidenceService evidence;
    private final BrowserTaskService tasks;
    private final AgentWorkspaceService workspace;
    private final ObjectMapper mapper;
    private final com.nora.common.http.ProxyProperties proxy;
    // Playwright Java 句柄只能在创建它的线程使用，画面请求与工具动作共用同一队列。
    private final ExecutorService actor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "nora-browser"); thread.setDaemon(true); return thread;
    });
    private final Map<String, BrowserSession> sessions = new ConcurrentHashMap<>();
    private Playwright playwright;
    private Browser browser;

    public BrowserService(BrowserEvidenceService evidence, BrowserTaskService tasks, AgentWorkspaceService workspace, ObjectMapper mapper,
                          com.nora.common.http.ProxyProperties proxy) {
        this.evidence = evidence; this.tasks = tasks; this.workspace = workspace; this.mapper = mapper; this.proxy = proxy;
    }

    public JsonNode state(String sessionId, boolean history) { return withEvidence(sessionId, call(() -> stateOnActor(sessionId)), history); }

    public byte[] frame(String sessionId) {
        return call(() -> {
            BrowserSession session = currentSession(sessionId);
            if (session == null) throw new IllegalArgumentException("浏览器尚未打开");
            return session.frame();
        });
    }

    public JsonNode control(String sessionId, JsonNode args) {
        String action = BrowserEvidenceService.required(args, "action");
        if ("takeover".equals(action)) {
            BrowserSession session = sessions.get(sessionId);
            if (session != null) session.human = true;
        }
        return withEvidence(sessionId, call(() -> {
            if ("takeover".equals(action) || "release".equals(action)) {
                session(sessionId, true).human = "takeover".equals(action);
            } else if ("resize".equals(action)) {
                BrowserSession session = sessions.get(sessionId);
                if (session != null) session.resize(Math.max(640, Math.min(1920, args.path("width").asInt(1280))),
                        Math.max(360, Math.min(2000, args.path("height").asInt(800))));
            } else executeOnActor(sessionId, null, args, true);
            return stateOnActor(sessionId);
        }), false);
    }

    public JsonNode execute(String sessionId, String stepId, JsonNode args) {
        evidence.folder(sessionId);
        return withEvidence(sessionId, call(() -> executeOnActor(sessionId, stepId, args, false)), false);
    }

    public JsonNode task(String sessionId, JsonNode args) {
        String action = BrowserEvidenceService.required(args, "action");
        if (!List.of("plan", "snapshot", "inspect", "extract", "export", "verify", "learn", "rows", "approve", "resample", "cancel", "adjust").contains(action)) {
            throw new IllegalArgumentException("此端点仅用于采集与验收");
        }
        return withEvidence(sessionId, call(() -> executeOnActor(sessionId, null, args, true)), false);
    }

    private JsonNode executeOnActor(String sessionId, String stepId, JsonNode args, boolean human) throws IOException {
        String action = BrowserEvidenceService.required(args, "action");
        if (!human) tasks.guardPlan(sessionId, action, args);
        switch (action) {
            case "state": return stateOnActor(sessionId);
            case "plan": return evidence.plan(sessionId, stepId, args);
            case "export": return evidence.export(sessionId, stepId, args);
            case "verify": return evidence.verify(sessionId, BrowserEvidenceService.required(args, "receiptId"));
            case "learn": return evidence.learn(sessionId, args);
            case "rows": return tasks.rows(sessionId, args);
            case "approve", "resample", "cancel", "adjust": {
                if (!human) throw new IllegalArgumentException("样本决定与人工选行仅供用户直接操作，Agent 不能自行确认");
                return "adjust".equals(action) ? tasks.adjust(sessionId, args) : tasks.decide(sessionId, args);
            }
            default: break;
        }
        if ("close".equals(action)) {
            BrowserSession existing = currentSession(sessionId);
            if (existing != null) {
                checkOwner(existing, human);
                try { existing.close(); } finally { sessions.remove(sessionId); }
            }
            return stateOnActor(sessionId);
        }
        BrowserSession session = session(sessionId, human);
        checkOwner(session, human);
        Page page = session.page();
        switch (action) {
            case "open": {
                String url = args.path("url").asText("").trim();
                if (!url.isEmpty()) BrowserEvidenceService.requireWebUrl(url);
                if (args.path("newTab").asBoolean()) page = session.context.newPage();
                if (!url.isEmpty()) page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                break;
            }
            case "snapshot": {
                ObjectNode result = mapper.createObjectNode().put("url", page.url()).put("title", page.title());
                result.put("snapshot", page.locator("body").ariaSnapshot(new Locator.AriaSnapshotOptions().setMode(AriaSnapshotMode.AI).setDepth(12)));
                return result;
            }
            case "inspect": {
                Locator items = locator(page, args);
                int count = Math.min(items.count(), 30);
                List<Map<String, String>> result = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    Locator item = items.nth(i);
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("text", item.innerText());
                    for (String attribute : List.of("href", "src", "id", "class", "datetime", "type", "placeholder")) {
                        String value = item.getAttribute(attribute); if (value != null) row.put(attribute, value);
                    }
                    result.add(row);
                }
                return mapper.valueToTree(Map.of("url", page.url(), "items", result, "total", items.count()));
            }
            case "click": locator(page, args).click(); break;
            case "fill": locator(page, args).fill(args.path("text").asText("")); break;
            case "key": page.keyboard().press(BrowserEvidenceService.required(args, "key")); break;
            case "scroll": page.mouse().wheel(args.path("dx").asDouble(0), args.path("dy").asDouble(500)); break;
            case "back": page.goBack(new Page.GoBackOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)); break;
            case "forward": page.goForward(new Page.GoForwardOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)); break;
            case "reload": page.reload(new Page.ReloadOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED)); break;
            case "select_tab": {
                String id = BrowserEvidenceService.required(args, "tabId");
                if (!session.pages.containsKey(id)) throw new IllegalArgumentException("标签已关闭或不属于当前对话");
                session.activeId = id; break;
            }
            case "close_tab": {
                Page tab = session.pages.get(BrowserEvidenceService.required(args, "tabId"));
                if (tab == null) throw new IllegalArgumentException("标签不存在");
                tab.close(); session.page(); break;
            }
            case "extract": return extract(sessionId, stepId, args, page);
            case "point": {
                if (!human) throw new IllegalArgumentException("坐标输入仅供用户画面操作");
                page.mouse().click(args.path("x").asDouble(), args.path("y").asDouble(),
                        new com.microsoft.playwright.Mouse.ClickOptions().setButton(MouseButton.LEFT).setClickCount(args.path("count").asInt(1)));
                break;
            }
            case "type": {
                if (!human) throw new IllegalArgumentException("直接输入仅供用户画面操作");
                page.keyboard().insertText(args.path("text").asText("")); break;
            }
            default: throw new IllegalArgumentException("不支持的浏览器动作: " + action);
        }
        return stateOnActor(sessionId);
    }

    private JsonNode extract(String sessionId, String stepId, JsonNode args, Page page) throws IOException {
        Locator items = page.locator(BrowserEvidenceService.required(args, "rowsSelector"));
        JsonNode fields = args.path("fields");
        if (!fields.isArray() || fields.isEmpty() || fields.size() > 20) throw new IllegalArgumentException("fields 需要 1–20 个字段");
        int limit = args.path("limit").asInt(100);
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("每次提取 limit 需要在 1–200 之间");
        limit = tasks.extractionLimit(sessionId, BrowserEvidenceService.required(args, "planId"), limit);
        int count = Math.min(items.count(), limit);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Map<String, String> row = new LinkedHashMap<>();
            for (JsonNode field : fields) {
                if (System.nanoTime() > deadline) throw new IllegalArgumentException("采集超过 25 秒，尚未保存；请减少行数或字段后重试");
                String name = BrowserEvidenceService.required(field, "name");
                String selector = field.path("selector").asText("");
                Locator cell = selector.isBlank() ? items.nth(i) : items.nth(i).locator(selector);
                String attribute = field.path("attribute").asText("");
                if (List.of("text", "innerText", "textContent").contains(attribute)) {
                    throw new IllegalArgumentException("字段 " + name + " 要读取元素正文时请省略 attribute；attribute 只用于 href、src 等真实 HTML 属性。尚未保存采集记录，可以修正参数后重试");
                }
                String value = attribute.isBlank() ? cell.innerText() : cell.getAttribute(attribute);
                if (value == null) value = "";
                if (!value.isBlank() && ("href".equals(attribute) || "src".equals(attribute))) value = java.net.URI.create(page.url()).resolve(value).toString();
                if (value.length() > 4000) throw new IllegalArgumentException("单个字段过长，请选择更具体的元素");
                if (row.putIfAbsent(name, value.trim()) != null) throw new IllegalArgumentException("提取字段名称重复");
            }
            rows.add(row);
        }
        return evidence.capture(sessionId, stepId, args, page.url(), rows);
    }

    private Locator locator(Page page, JsonNode args) {
        String ref = args.path("ref").asText("");
        if (!ref.isBlank()) {
            if (!ref.matches("[a-z][0-9]+")) throw new IllegalArgumentException("ref 必须来自最新网页快照");
            return page.locator("aria-ref=" + ref);
        }
        String selector = args.path("selector").asText("");
        if (!selector.isBlank()) return page.locator(selector);
        String role = BrowserEvidenceService.required(args, "role");
        return page.getByRole(AriaRole.valueOf(role.toUpperCase(Locale.ROOT)), new Page.GetByRoleOptions().setName(BrowserEvidenceService.required(args, "name")).setExact(true));
    }

    private void checkOwner(BrowserSession session, boolean human) {
        if (session.human != human) throw new IllegalArgumentException(session.human
                ? "用户已接管浏览器；停止网页操作，等待用户点击「交给 Agent」后继续"
                : "Agent 正在操作；请先点击「接管浏览器」");
    }

    private BrowserSession session(String sessionId, boolean human) throws IOException {
        BrowserSession existing = currentSession(sessionId);
        if (existing != null) return existing;
        if (sessions.size() >= 4) throw new IllegalArgumentException("已打开四个浏览器会话，请先关闭不使用的浏览器");
        if (browser == null || !browser.isConnected()) {
            if (playwright != null) playwright.close();
            playwright = Playwright.create();
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(true);
            if (proxy.usable()) options.setProxy(new Proxy("http://" + proxy.host() + ":" + proxy.port())
                    .setBypass("localhost,127.0.0.1,192.168.*,10.*," + String.join(",", proxy.bypassHosts())));
            browser = playwright.chromium().launch(options);
        }
        String prefix = evidence.folder(sessionId) + "/downloads";
        Path directory = workspace.resolveSafe(prefix);
        BrowserSession session = new BrowserSession(browser, evidence.storagePath(sessionId), directory, prefix, human);
        sessions.put(sessionId, session);
        return session;
    }

    private JsonNode stateOnActor(String sessionId) throws IOException {
        BrowserSession session = currentSession(sessionId);
        ObjectNode state = mapper.createObjectNode().put("active", session != null).put("width", BrowserSession.WIDTH).put("height", BrowserSession.HEIGHT);
        var tabs = state.putArray("tabs");
        state.putArray("downloads");
        if (session != null) {
            state.put("width", session.width).put("height", session.height);
            state.put("owner", session.human ? "human" : "agent").put("activeTabId", session.activeId);
            for (var entry : session.pages.entrySet()) tabs.addObject().put("id", entry.getKey()).put("url", entry.getValue().url()).put("title", entry.getValue().title());
            state.set("downloads", mapper.valueToTree(session.downloads));
        }
        return state;
    }

    private JsonNode withEvidence(String sessionId, JsonNode result, boolean history) {
        if (!result.has("tabs")) return result;
        try {
            // 数据库与文件核验不占用 Playwright 队列；轮询只读最新收据。
            List<JsonNode> exports = evidence.exports(sessionId, history ? 100 : 1);
            ObjectNode state = (ObjectNode) result;
            state.set("exports", mapper.valueToTree(exports));
            state.set("task", tasks.current(sessionId, exports));
            return state;
        } catch (IOException failure) {
            throw new IllegalStateException(failure.getMessage(), failure);
        }
    }

    private <T> T call(Callable<T> task) {
        var future = actor.submit(task);
        try { return future.get(40, TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { future.cancel(false); Thread.currentThread().interrupt(); throw new IllegalStateException("浏览器调用已中断，结果未知；检查实际页面后再决定是否重试", interrupted); }
        catch (TimeoutException timeout) { future.cancel(false); throw new IllegalStateException("浏览器调用超时，结果未知；检查实际页面后再决定是否重试", timeout); }
        catch (ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException(failure.getCause().getMessage(), failure.getCause());
        }
    }

    private BrowserSession currentSession(String sessionId) {
        if (browser != null && !browser.isConnected()) sessions.clear();
        return sessions.get(sessionId);
    }

    @PreDestroy
    public void shutdown() {
        try { call(() -> {
            try {
                for (BrowserSession session : sessions.values()) {
                    try { session.close(); }
                    catch (com.microsoft.playwright.PlaywrightException failure) { log.warn("browser state save failed on shutdown", failure); }
                }
            } finally { sessions.clear(); if (playwright != null) playwright.close(); }
            return null;
        }); }
        finally { actor.shutdownNow(); }
    }
}
