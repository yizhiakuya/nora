package com.nora.agent.service;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/** 显式运行的真实 Docker/文件 I/O 验收，不使用模拟进程；HTTP/模型链路另由 ptc 套件验证。 */
class PtcRuntimeIT {
    @TempDir Path directory;

    private PtcRuntime runtime() throws Exception {
        String image = System.getenv("NORA_PTC_IMAGE");
        if (image == null) throw new IllegalStateException("请设置 NORA_PTC_IMAGE 并预拉取固定 digest 镜像");
        return new PtcRuntime(new ObjectMapper(), image);
    }

    @Test
    void executesRealFilesAndReportsFailures() throws Exception {
        Files.writeString(directory.resolve("value"), "first");
        var calls = new PtcRuntime.Calls() {
            @Override public boolean parallel(String name, String args) { return name.equals("read"); }
            @Override public ToolStepEmitter.InvocationResult invoke(int index, String name, String args) {
                try {
                    if (name.equals("write")) Files.writeString(directory.resolve("value"), "updated");
                    if (name.equals("broken")) throw new IllegalStateException("intentional failure");
                    return new ToolStepEmitter.InvocationResult("completed", ChatToolExecutor.textOutcome(Files.readString(directory.resolve("value")), "文件操作"));
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }
        };
        var runtime = runtime();
        var result = runtime.execute("const a=await Promise.all([tools.call('read',{}),tools.call('read',{})]); await tools.call('write',{}); output((await tools.call('read',{})).content);", calls);
        assertNull(result.error()); assertEquals(4, result.calls().size()); assertEquals("updated", result.output().getFirst().asText());
        var failed = runtime.execute("output(await tools.call('broken',{})); output(await tools.call('write',{}));", calls);
        assertEquals("unknown", failed.calls().getFirst().status()); assertEquals("declined", failed.calls().getLast().status());
        assertNotNull(runtime.execute("throw new Error('test error')", calls).error());
        assertNotNull(runtime.execute("output('x'.repeat(70000))", calls).error());
        assertNotNull(runtime.execute("tools.call('read',{});", calls).error());
        var isolation = runtime.execute("const fs=await import('node:fs/promises'); try { await fs.writeFile('/nora-secret','x'); output('unsafe'); } catch(e) { output(e.code); } output(Object.keys(process.env).filter(k=>k.startsWith('NORA_')));", calls);
        assertNotEquals("unsafe", isolation.output().getFirst().asText()); assertTrue(isolation.output().getLast().isEmpty());
        var supervisor = runtime.execute("output(process.getuid()); try {process.kill(process.ppid,'SIGKILL');output('unsafe');} catch(e){output(e.code);} output((await (await import('node:fs/promises')).readFile('/proc/self/status','utf8')).match(/CapEff:\\s*(\\w+)/)[1]);", calls);
        assertEquals(65534, supervisor.output().getFirst().asInt());
        assertEquals("EPERM", supervisor.output().get(1).asText());
        assertEquals("0000000000000000", supervisor.output().get(2).asText());
        assertNotNull(runtime.execute("for(let i=0;i<65;i++) await tools.call('read',{});", calls).error());
        assertThrows(Exception.class, () -> runtime.execute("process.stdout.write(JSON.stringify({type:'call',id:999,name:'write',args:{}})+'\\n');", calls));
        var network = runtime.execute("const net=await import('node:net'); output(await new Promise(resolve=>{const s=net.connect({host:'1.1.1.1',port:443});s.on('connect',()=>{s.destroy();resolve('unsafe')});s.on('error',()=>resolve('blocked'));s.setTimeout(2000,()=>{s.destroy();resolve('blocked')})}));", calls);
        assertEquals("blocked", network.output().getFirst().asText());
    }

    @Test
    void cancelsContainerAndAllowsNextProgram() throws Exception {
        var runtime = runtime();
        var calls = new PtcRuntime.Calls() {
            @Override public boolean parallel(String name, String args) { return false; }
            @Override public ToolStepEmitter.InvocationResult invoke(int index, String name, String args) { throw new IllegalArgumentException("无工具"); }
        };
        try (var worker = Executors.newVirtualThreadPerTaskExecutor()) {
            var task = worker.submit(() -> runtime.execute("await new Promise(r=>setTimeout(r,60000))", calls));
            Thread.sleep(2000);
            task.cancel(true);
            worker.shutdown();
            assertTrue(worker.awaitTermination(20, TimeUnit.SECONDS));
        }
        assertEquals(2, runtime.execute("output(1+1)", calls).output().getFirst().asInt());
    }
}
