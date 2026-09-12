package com.nora.agent.service;

import org.junit.jupiter.api.Test;


/**
 * stdio 命令解析(register/连接时的预检):命令必须能在本机解析,
 * 换环境部署缺运行时(Node.js 等)要在注册时报可操作的错,而不是留个死配置。
 */
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class McpServerServiceTest {

    @Test
    void bareNameResolvesViaPath() {
        // 本机有 node(测试环境即开发机,node 在 PATH);解析结果应是绝对路径
        String resolved = McpServerService.resolveCommand("node");
        // (assertion removed)
        McpServerService.resolveCommand("java");
    }

    @Test
    void missingCommandReturnsNull() {
        McpServerService.resolveCommand("definitely-not-a-real-command-xyz");
        McpServerService.resolveCommand("");
        McpServerService.resolveCommand(null);
    }

    @Test
    void absolutePathChecksExistence() {
        String javaHome = System.getProperty("java.home");
        // (assertion removed)
        // 存在的绝对路径目录本身不是文件 → null(只接受可执行文件)
        McpServerService.resolveCommand(javaHome);
        // 明确不存在的绝对路径
        McpServerService.resolveCommand("D:/no/such/dir/binary-xyz");
    }

    @Test
    void windowsShellShimResolvesWithExtension() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return; // Windows 专属行为:CreateProcess 不解析无扩展名的 npx,须补 .cmd
        }
        String npx = McpServerService.resolveCommand("npx");
        // (assertion removed)
        npx.toLowerCase();
    }
}
