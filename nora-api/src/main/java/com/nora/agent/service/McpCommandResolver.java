package com.nora.agent.service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * stdio 命令解析(2026-09-17 从 McpServerService 拆出,复杂度审计建议 #2):
 * 裸命令名 → PATH 扫描(Windows 按 PATHEXT 补全扩展名——CreateProcess
 * 不解析无扩展名的 shell shim `npx`,只认 `npx.cmd`)。
 */
final class McpCommandResolver {

    private McpCommandResolver() {
    }

    /**
     * 把命令解析为可执行路径:绝对路径 → 存在性检查;裸名称 → PATH 扫描
     * (Windows:先试 PATHEXT 候选——CreateProcess 不解析无扩展名的 shell shim
     * `npx`,只认 `npx.cmd`)。未找到返回 null(调用方给出可操作报错)。
     */
    static String resolveCommand(String command) {
        return resolveCommand(command, System.getenv());
    }

    static String resolveCommand(String command, Map<String, String> environment) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String c = command.trim();
        File direct = new File(c);
        if (direct.isAbsolute()) {
            if (direct.isFile()) {
                return direct.getPath();
            }
            // 绝对路径但没带扩展名(Windows):按 PATHEXT 补全
            if (isWindows() && !hasExtension(c)) {
                for (String ext : pathExtCandidates(environment)) {
                    File f = new File(c + ext);
                    if (f.isFile()) {
                        return f.getPath();
                    }
                }
            }
            return null;
        }
        String path = environment.get("PATH");
        if (path == null || path.isBlank()) {
            return null;
        }
        List<String> names = new ArrayList<>();
        if (isWindows() && !hasExtension(c)) {
            for (String ext : pathExtCandidates(environment)) {
                names.add(c + ext);
            }
        } else {
            names.add(c);
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            for (String name : names) {
                File f = new File(dir, name);
                if (f.isFile() && (isWindows() || f.canExecute())) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static boolean hasExtension(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        return name.indexOf('.', slash + 1) >= 0;
    }

    private static List<String> pathExtCandidates(Map<String, String> environment) {
        String pathext = environment.get("PATHEXT");
        if (pathext == null || pathext.isBlank()) {
            pathext = ".COM;.EXE;.BAT;.CMD";
        }
        List<String> out = new ArrayList<>();
        for (String ext : pathext.split(";")) {
            if (!ext.isBlank()) {
                out.add(ext.trim().toLowerCase());
            }
        }
        return out;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
