package com.nora.agent.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** 只有隔离容器执行模型代码，所有工具副作用通过受控回调回到原 Run。 */
@Component
class PtcRuntime {
    private static final int FRAME_LIMIT = 128 * 1024;
    private final ObjectMapper json;
    private final String image;
    private final String runner;

    PtcRuntime(ObjectMapper json, @Value("${nora.ptc.image:}") String image) throws IOException {
        this.json = json;
        this.image = image;
        try (var stream = PtcRuntime.class.getResourceAsStream("/ptc/runner.mjs")) {
            if (stream == null) throw new IOException("PTC 运行脚本缺失");
            runner = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    interface Calls {
        boolean parallel(String name, String arguments);
        ToolStepEmitter.InvocationResult invoke(int index, String name, String arguments);
    }

    record CallResult(int callId, String name, String status, boolean truncated, List<com.nora.agent.dto.ViewerFile> artifacts) {}
    record Result(List<JsonNode> output, List<CallResult> calls, String error) {}

    boolean configured() { return image.matches("[a-zA-Z0-9./:_-]+@sha256:[a-f0-9]{64}"); }

    boolean available() {
        if (!configured()) return false;
        try {
            Process probe = new ProcessBuilder("docker", "image", "inspect", image)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try { return probe.waitFor(5, TimeUnit.SECONDS) && probe.exitValue() == 0; }
            finally { probe.destroyForcibly(); }
        } catch (IOException failure) { return false; }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); return false; }
    }

    Result execute(String code, Calls calls) throws Exception {
        if (!configured()) throw new IllegalStateException("PTC 未配置固定 digest 的运行镜像");
        if (code == null || code.isBlank() || code.getBytes(StandardCharsets.UTF_8).length > 65536)
            throw new IllegalArgumentException("程序不能为空且不能超过 64 KiB");
        String name = "nora-ptc-" + UUID.randomUUID();
        Process process = new ProcessBuilder("docker", "run", "--rm", "--pull=never", "-i", "--name", name,
                "--label", "nora.ptc=true", "--network=none", "--read-only", "--user=0:0",
                "--cap-drop=ALL", "--cap-add=SETUID", "--cap-add=SETGID", "--security-opt=no-new-privileges", "--pids-limit=32",
                "--memory=256m", "--memory-swap=256m", "--cpus=1", "--ulimit", "cpu=60:60",
                "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m", image,
                // PID 1 只监督生命周期；模型进程降权，不能杀死监督者来绕过后端崩溃后的时限。
                "node", "-e", "process.setgroups([]); const child=require('node:child_process').spawn(process.execPath,"
                        + "['--input-type=module','-e',process.argv[1]],{uid:65534,gid:65534,stdio:'inherit'});"
                        + "setTimeout(()=>process.exit(124),600000); child.on('error',()=>process.exit(1));"
                        + "child.on('exit',code=>process.exit(code??1));", runner).start();
        var stopped = new AtomicBoolean();
        try (var transport = Executors.newVirtualThreadPerTaskExecutor()) {
            var stderr = transport.submit(() -> readLimited(process.getErrorStream(), 8192));
            var task = transport.submit(com.nora.common.logging.TraceContext.wrap(() -> exchange(process, code, calls, stopped)));
            try {
                Result result = task.get(10, TimeUnit.MINUTES);
                if (!process.waitFor(5, TimeUnit.SECONDS)) throw new IOException("PTC 容器未正常退出");
                if (process.exitValue() != 0 && result.error() == null)
                    return new Result(result.output(), result.calls(), "PTC 运行失败: " + stderr.get());
                return result;
            } finally {
                stopped.set(true);
                // 先关容器再等待读取线程，避免阻塞流使取消无法退出。
                try { stopContainer(name, process); }
                finally { task.cancel(true); }
            }
        }
    }

    private Result exchange(Process process, String code, Calls calls, AtomicBoolean stopped) throws Exception {
        var output = new ArrayList<JsonNode>();
        var summaries = new java.util.concurrent.ConcurrentSkipListMap<Integer, CallResult>();
        var uncertain = new AtomicBoolean();
        try (var writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
             var pool = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory())) {
            write(writer, json.createObjectNode().put("code", code));
            CompletableFuture<Void> barrier = CompletableFuture.completedFuture(null);
            var batch = new ArrayList<CompletableFuture<Void>>();
            var all = new ArrayList<CompletableFuture<Void>>();
            int count = 0;
            int outputBytes = 0;
            String error = null;
            try {
                while (true) {
                    String frame = readLine(process.getInputStream());
                    if (frame == null) { error = "PTC 进程在程序结束前退出"; break; }
                    JsonNode message = json.reader().with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(frame);
                    if (message == null || !message.isObject()) throw new IOException("PTC 协议要求 JSON 对象");
                    switch (message.path("type").asText()) {
                        case "call" -> {
                            if (++count > 64 || !message.path("id").isInt() || message.path("id").intValue() != count
                                    || !message.path("name").isTextual() || !message.path("args").isObject())
                                throw new IOException("PTC 工具请求非法或超过次数限制");
                            int index = count;
                            String tool = message.path("name").textValue();
                            String args = message.path("args").toString();
                            boolean parallel = calls.parallel(tool, args);
                            if (!parallel) {
                                batch.add(barrier);
                                barrier = CompletableFuture.allOf(batch.toArray(CompletableFuture[]::new));
                                batch.clear();
                            }
                            var future = barrier.thenRunAsync(com.nora.common.logging.TraceContext.wrap(() -> {
                                ToolStepEmitter.InvocationResult result;
                                if (stopped.get() || uncertain.get()) {
                                    result = new ToolStepEmitter.InvocationResult("declined", ChatToolExecutor.textOutcome("ERROR: 程序已停止派发工具", "未执行"));
                                } else {
                                    try { result = calls.invoke(index, tool, args); }
                                    catch (RuntimeException failure) {
                                        result = new ToolStepEmitter.InvocationResult("unknown", new ChatToolExecutor.ToolOutcome(
                                                "ERROR: 子调用未确认终态: " + Texts.abbreviate(failure.getMessage(), 1000), "结果未知", null, false, List.of(), true));
                                    }
                                }
                                summaries.put(index, new CallResult(index, tool, result.status(), Boolean.TRUE.equals(result.outcome().truncated()),
                                        result.outcome().files() == null ? List.of() : result.outcome().files()));
                                if (result.outcome().unknown()) uncertain.set(true);
                                var response = json.createObjectNode().put("id", index);
                                var value = response.putObject("result").put("callId", index).put("status", result.status())
                                        .put("content", result.outcome().content()).put("truncated", Boolean.TRUE.equals(result.outcome().truncated()));
                                value.set("artifacts", json.valueToTree(result.outcome().files() == null ? List.of() : result.outcome().files()));
                                try { write(writer, response); }
                                catch (IOException failure) { uncertain.set(true); stopped.set(true); }
                            }), pool);
                            all.add(future);
                            if (parallel) batch.add(future);
                            else barrier = future;
                        }
                        case "output" -> {
                            JsonNode value = message.get("value");
                            if (value == null) throw new IOException("PTC 输出缺少 value");
                            outputBytes += value.toString().getBytes(StandardCharsets.UTF_8).length;
                            if (outputBytes > 65536 || output.size() >= 256) throw new IOException("PTC 输出超过限制");
                            output.add(value);
                        }
                        case "done" -> {
                            error = message.path("error").isTextual() ? message.path("error").asText() : null;
                            CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get();
                            return new Result(output, new ArrayList<>(summaries.values()), stopped.get() ? "程序通信中断；请核对子调用记录" : error);
                        }
                        default -> throw new IOException("PTC 未知协议消息");
                    }
                }
                CompletableFuture.allOf(all.toArray(CompletableFuture[]::new)).get();
                return new Result(output, new ArrayList<>(summaries.values()), error);
            } finally {
                stopped.set(true);
                pool.shutdownNow();
            }
        }
    }

    private void write(BufferedWriter writer, JsonNode message) throws IOException {
        synchronized (writer) { writer.write(json.writeValueAsString(message)); writer.newLine(); writer.flush(); }
    }

    private static String readLine(InputStream input) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        for (int next; (next = input.read()) != -1;) {
            if (next == '\n') return bytes.toString(StandardCharsets.UTF_8);
            if (bytes.size() >= FRAME_LIMIT) throw new IOException("PTC 消息超过大小限制");
            bytes.write(next);
        }
        if (bytes.size() != 0) throw new IOException("PTC 消息未完整结束");
        return null;
    }

    private static String readLimited(InputStream input, int limit) throws IOException {
        var saved = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        for (int n; (n = input.read(buffer)) != -1;) {
            saved.write(buffer, 0, Math.min(n, Math.max(0, limit - saved.size())));
        }
        return saved.toString(StandardCharsets.UTF_8);
    }

    private static void stopContainer(String name, Process process) throws IOException {
        boolean interrupted = Thread.interrupted();
        try {
            Process removal = new ProcessBuilder("docker", "rm", "-f", name).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!removal.waitFor(10, TimeUnit.SECONDS)) { removal.destroyForcibly(); throw new IOException("PTC 容器回收超时: " + name); }
            Process remaining = new ProcessBuilder("docker", "ps", "-aq", "--filter", "name=^/" + name + "$")
                    .redirectErrorStream(true).start();
            if (!remaining.waitFor(10, TimeUnit.SECONDS)) { remaining.destroyForcibly(); throw new IOException("PTC 容器回收核验超时"); }
            if (remaining.exitValue() != 0 || !readLimited(remaining.getInputStream(), 1024).isBlank())
                throw new IOException("PTC 容器回收未确认: " + name);
        } catch (InterruptedException e) { interrupted = true; throw new IOException("PTC 容器回收被中断", e); }
        finally { process.destroyForcibly(); if (interrupted) Thread.currentThread().interrupt(); }
    }
}
