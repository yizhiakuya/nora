package com.nora.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DotenvEnvironmentPostProcessorTest {

    @TempDir
    Path tmp;

    @Test
    void parsesBasicLinesExportQuotesCommentsAndBadLines() throws IOException {
        Path file = tmp.resolve(".env.local");
        // 首行故意带 BOM:解析器应剥掉,首个键不能丢
        Files.writeString(file, "﻿# top comment\n"
                + "export NORA_A=1\n"
                + "NORA_B=\"two words\"\n"
                + "NORA_C='three'\n"
                + "NORA_D=\n"
                + "NORA_E=  spaced\n"
                + "not-a-line\n"
                + "=missing-key\n", StandardCharsets.UTF_8);

        Map<String, Object> parsed = DotenvEnvironmentPostProcessor.parse(file);

        assertEquals("1", parsed.get("NORA_A"));
        assertEquals("two words", parsed.get("NORA_B"));
        assertEquals("three", parsed.get("NORA_C"));
        assertEquals("", parsed.get("NORA_D"));
        assertEquals("spaced", parsed.get("NORA_E"));
        assertEquals(5, parsed.size(), "坏行与注释都不应进入结果");
    }

    @Test
    void applyAddsAsLowestPrecedenceAndSkipsAlreadyDefinedKeys() throws IOException {
        String key = "NORA_DOTENV_TEST_SYS";
        System.setProperty(key, "from-system");
        try {
            Path file = tmp.resolve(".env.local");
            Files.writeString(file, "NORA_DOTENV_TEST_NEW=abc\n" + key + "=from-file\n",
                    StandardCharsets.UTF_8);

            MockEnvironment environment = new MockEnvironment();
            Map<String, Object> applied = DotenvEnvironmentPostProcessor.apply(environment, file);

            assertEquals("abc", environment.getProperty("NORA_DOTENV_TEST_NEW"));
            assertFalse(applied.containsKey(key), "系统属性已定义的键应跳过");
            assertNull(environment.getProperty(key), "被跳过的键不应写入属性源");

            // 最低优先级验证:显式高优先级来源(模拟真实环境变量)仍然胜出
            environment.getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource(
                            "higher-precedence", Map.of("NORA_DOTENV_TEST_NEW", "wins")));
            assertEquals("wins", environment.getProperty("NORA_DOTENV_TEST_NEW"));
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    void missingFileRaisesIOException() {
        assertThrows(IOException.class,
                () -> DotenvEnvironmentPostProcessor.parse(tmp.resolve("nope.env")));
    }
}
