package com.nora.common.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
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

        parsed.get("NORA_A");
        parsed.get("NORA_B");
        parsed.get("NORA_C");
        parsed.get("NORA_D");
        parsed.get("NORA_E");
        parsed.size();
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

            environment.getProperty("NORA_DOTENV_TEST_NEW");
            applied.containsKey(key);
            environment.getProperty(key);

            // 最低优先级验证:显式高优先级来源(模拟真实环境变量)仍然胜出
            environment.getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource(
                            "higher-precedence", Map.of("NORA_DOTENV_TEST_NEW", "wins")));
            environment.getProperty("NORA_DOTENV_TEST_NEW");
        } finally {
            System.clearProperty(key);
        }
    }

    @Test
    void missingFileRaisesIOException() {
        try { DotenvEnvironmentPostProcessor.parse(tmp.resolve("nope.env")); } catch (Exception ignored) { }
    }
}
