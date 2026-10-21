package com.nora.agent.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.ScreenshotType;

final class BrowserSession {
    static final int WIDTH = 1280;
    static final int HEIGHT = 800;
    final BrowserContext context;
    final Path storagePath;
    final Map<String, Page> pages = new LinkedHashMap<>();
    final List<Map<String, String>> downloads = new ArrayList<>();
    String activeId;
    int width = WIDTH;
    int height = HEIGHT;
    volatile boolean human;

    BrowserSession(Browser browser, Path storagePath, Path downloadDir, String downloadPrefix, boolean human,
                   java.util.function.Consumer<Runnable> downloadWorker) throws IOException {
        this.storagePath = storagePath;
        this.human = human;
        Files.createDirectories(downloadDir);
        Browser.NewContextOptions options = new Browser.NewContextOptions().setViewportSize(WIDTH, HEIGHT).setAcceptDownloads(true);
        if (Files.isRegularFile(storagePath)) options.setStorageStatePath(storagePath);
        context = browser.newContext(options);
        context.setDefaultTimeout(8000);
        context.setDefaultNavigationTimeout(20000);
        context.onPage(page -> {
            page.setViewportSize(width, height);
            String id = UUID.randomUUID().toString();
            pages.put(id, page);
            activeId = id;
            page.onClose(ignored -> {
                pages.remove(id);
                if (id.equals(activeId)) activeId = pages.isEmpty() ? null : pages.keySet().iterator().next();
            });
            page.onDownload(download -> {
                String name = download.suggestedFilename().replaceAll("[^\\p{L}\\p{N}._ -]", "_");
                if (name.isBlank() || name.length() > 120) name = "download.bin";
                String file = UUID.randomUUID().toString().substring(0, 8) + "-" + name;
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("id", file); entry.put("name", name); entry.put("status", "pending");
                downloads.add(entry);
                if (downloads.size() > 100) downloads.removeFirst();
                try {
                    downloadWorker.accept(() -> {
                        try {
                            download.saveAs(downloadDir.resolve(file));
                            entry.put("target", "workspace:" + downloadPrefix + "/" + file);
                            entry.put("status", "completed");
                        } catch (com.microsoft.playwright.PlaywrightException failure) {
                            entry.put("status", "failed"); entry.put("error", "下载未完成，请重新下载");
                        }
                    });
                } catch (java.util.concurrent.RejectedExecutionException busy) {
                    entry.put("status", "failed"); entry.put("error", "下载队列已满，请稍后重新下载");
                }
            });
        });
        context.newPage();
    }

    Page page() {
        Page page = activeId == null ? null : pages.get(activeId);
        if (page == null) page = context.newPage();
        return page;
    }

    byte[] frame() {
        return page().screenshot(new Page.ScreenshotOptions().setType(ScreenshotType.JPEG).setQuality(70).setTimeout(6000));
    }

    void resize(int width, int height) {
        this.width = width; this.height = height;
        for (Page page : pages.values()) page.setViewportSize(width, height);
    }

    void close() {
        try { context.storageState(new BrowserContext.StorageStateOptions().setPath(storagePath).setIndexedDB(true)); }
        finally { context.close(); }
    }
}
