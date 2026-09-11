package com.nora.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * stdio 命令解析(register/连接时的预检):命令必须能在本机解析,
 * 换环境部署缺运行时(Node.js 等)要在注册时报可操作的错,而不是留个死配置。
 */
class McpServerServiceTest {

    @Test
    void bareNameResolvesViaPath() {
        // 本机有 node(测试环境即开发机,node 在 PATH);解析结果应是绝对路径
        String resolved = McpServerService.resolveCommand("node");
        assertNotNull(resolved, "node 应在 PATH 中可解析");
        assertNotNull(McpServerService.resolveCommand("java"), "java 必然在(测试 JVM 运行中)");
    }

    @Test
    void missingCommandReturnsNull() {
        assertNull(McpServerService.resolveCommand("definitely-not-a-real-command-xyz"));
        assertNull(McpServerService.resolveCommand(""));
        assertNull(McpServerService.resolveCommand(null));
    }

    @Test
    void absolutePathChecksExistence() {
        String javaHome = System.getProperty("java.home");
        assertNotNull(javaHome);
        // 存在的绝对路径目录本身不是文件 → null(只接受可执行文件)
        assertNull(McpServerService.resolveCommand(javaHome));
        // 明确不存在的绝对路径
        assertNull(McpServerService.resolveCommand("D:/no/such/dir/binary-xyz"));
    }

    @Test
    void windowsShellShimResolvesWithExtension() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return; // Windows 专属行为:CreateProcess 不解析无扩展名的 npx,须补 .cmd
        }
        String npx = McpServerService.resolveCommand("npx");
        assertNotNull(npx, "npx 应解析为 npx.cmd 绝对路径");
        assertEquals(true, npx.toLowerCase().endsWith(".cmd") || npx.toLowerCase().endsWith(".exe"),
                "解析结果应为可执行扩展名(实际:" + npx + ")");
    }
}
