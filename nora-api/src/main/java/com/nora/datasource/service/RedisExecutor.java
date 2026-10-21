package com.nora.datasource.service;

import java.io.IOException;
import java.io.StreamTokenizer;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nora.common.exception.BusinessException;
import com.nora.datasource.api.QueryResult;

import io.lettuce.core.RedisException;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import io.lettuce.core.output.NestedMultiOutput;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.ProtocolKeyword;

/** Lettuce 负责命令分发和 RESP 解码；这里只做参数分词与表格展示。 */
public final class RedisExecutor {
    static final int MAX_ROWS = 200;
    private static final ObjectMapper JSON = new ObjectMapper();

    private RedisExecutor() { }

    static List<String> arguments(String command) {
        if (command == null || command.isBlank()) throw BusinessException.validation("Redis command is required");
        var tokenizer = new StreamTokenizer(new StringReader(command));
        tokenizer.resetSyntax();
        tokenizer.wordChars(33, Character.MAX_VALUE);
        tokenizer.whitespaceChars(0, 32);
        tokenizer.quoteChar('"');
        tokenizer.quoteChar('\'');
        var args = new ArrayList<String>();
        try {
            while (tokenizer.nextToken() != StreamTokenizer.TT_EOF) args.add(tokenizer.sval);
        } catch (IOException e) {
            throw BusinessException.validation("invalid Redis command");
        }
        return args;
    }

    public static QueryResult execute(RedisCommands<String, String> commands, List<String> argv) {
        if (argv.isEmpty()) throw BusinessException.validation("Redis command is required");
        String verb = argv.getFirst().toUpperCase(Locale.ROOT);
        var args = new CommandArgs<String, String>(StringCodec.UTF8);
        argv.stream().skip(1).forEach(args::addValue);
        ProtocolKeyword command = new ProtocolKeyword() {
            public String name() { return verb; }
            public byte[] getBytes() { return verb.getBytes(StandardCharsets.US_ASCII); }
        };
        try {
            List<Object> result = commands.dispatch(command, new NestedMultiOutput<>(StringCodec.UTF8), args);
            var rows = new ArrayList<List<String>>();
            for (Object value : result.subList(0, Math.min(result.size(), MAX_ROWS))) {
                rows.add(Collections.singletonList(value == null ? null
                        : value instanceof List<?> ? JSON.writeValueAsString(value) : value.toString()));
            }
            return new QueryResult(List.of("value"), rows, rows.size(), 0, result.size() > MAX_ROWS);
        } catch (RedisException | JsonProcessingException e) {
            throw new BusinessException(502, "Redis 命令执行失败: " + e.getMessage());
        }
    }
}
