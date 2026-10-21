package com.nora.datasource.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.nora.common.exception.BusinessException;

import io.lettuce.core.api.sync.RedisCommands;

class RedisExecutorTest {
    @Test
    void tokenizesAndRendersNativeCommands() {
        RedisCommands<String, String> commands = mock(RedisCommands.class);
        when(commands.dispatch(any(), any(), any())).thenReturn(Arrays.asList("OK", null, List.of("nested", 1)));
        for (String command : List.of("GET key", "SET key \"hello world\"", "SET key '中文 值'", "SET key \"\"")) {
            RedisExecutor.execute(commands, RedisExecutor.arguments(command));
        }
        try { RedisExecutor.arguments(" "); } catch (BusinessException ignored) { }
        try { RedisExecutor.execute(commands, List.of()); } catch (BusinessException ignored) { }
    }
}
