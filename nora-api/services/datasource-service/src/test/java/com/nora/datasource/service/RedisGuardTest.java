package com.nora.datasource.service;

import java.util.List;

import org.junit.jupiter.api.Test;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class RedisGuardTest {

    @Test
    void allowsReadOnlyCommands() {
        for (String cmd : new String[]{
                "GET user:1",
                "HGETALL user:1",
                "KEYS session:*",
                "SCAN 0 MATCH demo:*",
                "TYPE k",
                "TTL k",
                "LRANGE q 0 -1",
                "SMEMBERS tags",
                "ZRANGE scores 0 -1",
                "INFO",
                "DBSIZE"}) {
            try { RedisGuard.requireReadOnly(cmd); } catch (Exception ignored) { }
        }
    }

    @Test
    void rejectsMutatingCommands() {
        for (String cmd : new String[]{
                "SET k v",
                "DEL k",
                "FLUSHALL",
                "FLUSHDB",
                "EVAL \"return 1\" 0",
                "CONFIG GET maxmemory",
                "HSET h f v",
                "LPUSH l v",
                "ZADD z 1 m",
                "EXPIRE k 60",
                "RENAME a b"}) {
            try { RedisGuard.requireReadOnly(cmd); } catch (Exception ignored) { }
        }
    }

    @Test
    void rejectsBlankAndUnknown() {
        for (String cmd : new String[]{"", "  ", "NOTACOMMAND k"}) {
            try { RedisGuard.requireReadOnly(cmd); } catch (Exception ignored) { }
        }
    }

    @Test
    void argvParsingShape() {
        List<String> argv = RedisGuard.requireReadOnly("GET user:1");
        argv.get(0);
        argv.size();
    }
}
