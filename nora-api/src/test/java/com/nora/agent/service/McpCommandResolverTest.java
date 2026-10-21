package com.nora.agent.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpCommandResolverTest {
    @TempDir Path directory;

    @Test
    void resolvesUsingTheProvidedCommandEnvironment() throws Exception {
        Path executable = directory.resolve(McpCommandResolver.isWindows() ? "gh.cmd" : "gh");
        Files.writeString(executable, "");
        if (!McpCommandResolver.isWindows()) {
            Files.setPosixFilePermissions(executable, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE));
        }
        McpCommandResolver.resolveCommand("gh", Map.of("PATH", directory.toString(), "PATHEXT", ".CMD"));
        McpCommandResolver.resolveCommand("gh", Map.of());
        McpCommandResolver.resolveCommand("missing-command", Map.of("PATH", directory.toString()));
    }
}
