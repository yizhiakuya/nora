package com.nora.datasource.service;

import com.nora.common.exception.BusinessException;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Read-only guard for Redis commands (mirrors {@link SqlGuard} for the
 * key-value engine). Only an allowlist of non-mutating commands is accepted;
 * anything else (SET/DEL/FLUSHALL/EVAL/…) is rejected before it reaches the
 * server. Commands are single-line, whitespace-separated (RESP arguments with
 * quotes are intentionally not supported — the console is a read-only peek tool).
 */
public final class RedisGuard {

    /** Read-only commands allowed from the console (lower-case, first token). */
    private static final Set<String> ALLOWED = Set.of(
            // keyspace / metadata
            "keys", "scan", "type", "ttl", "pttl", "exists", "dbsize", "randomkey", "object",
            // strings
            "get", "mget", "strlen", "getrange", "substr",
            // hashes
            "hget", "hmget", "hgetall", "hkeys", "hvals", "hlen", "hexists", "hscan", "hrandfield",
            // lists
            "lrange", "llen", "lindex", "lpos",
            // sets
            "smembers", "scard", "sismember", "srandmember", "sscan", "smismember",
            // sorted sets
            "zrange", "zrevrange", "zrangebyscore", "zrevrangebyscore", "zrangebylex", "zcard",
            "zscore", "zmscore", "zrank", "zrevrank", "zcount", "zscan", "zrandmember",
            // streams / bitmaps / hll (read-only forms)
            "xrange", "xrevrange", "xlen", "xinfo", "getbit", "bitcount", "bitpos", "pfcount",
            // server info
            "info", "time", "memory", "command", "lastsave", "lolwut");

    /** Explicit deny list for defense in depth (even if a typo slips into ALLOWED). */
    private static final Set<String> DENIED = Set.of(
            "set", "setnx", "setex", "psetex", "mset", "msetnx", "append", "setrange", "incr",
            "decr", "incrby", "decrby", "incrbyfloat", "getset", "getdel", "getex",
            "del", "unlink", "rename", "renamenx", "move", "copy", "restore", "dump", "migrate",
            "expire", "pexpire", "expireat", "pexpireat", "persist",
            "hset", "hsetnx", "hmset", "hdel", "hincrby", "hincrbyfloat",
            "lpush", "rpush", "lpop", "rpop", "lset", "linsert", "lrem", "ltrim", "lmove", "rpoplpush",
            "sadd", "srem", "spop", "smove", "sinterstore", "sunionstore", "sdiffstore",
            "zadd", "zrem", "zincrby", "zpopmin", "zpopmax", "zremrangebyrank", "zremrangebyscore",
            "xadd", "xdel", "xtrim", "xack", "xgroup", "xclaim", "xautoclaim",
            "setbit", "bitop", "bitfield", "pfadd", "pfmerge",
            "flushdb", "flushall", "swapdb", "select", "auth", "acl", "config", "client",
            "shutdown", "slaveof", "replicaof", "failover", "cluster", "debug", "monitor",
            "eval", "evalsha", "script", "function", "fcall", "publish", "subscribe", "psubscribe",
            "multi", "exec", "discard", "watch", "unwatch", "save", "bgsave", "bgrewriteaof",
            "restore-asking", "asking", "readonly", "readwrite");

    private RedisGuard() {
    }

    /**
     * Validates that the command line is a single read-only Redis command.
     *
     * @param command raw command text, e.g. {@code GET user:1} or {@code KEYS session:*}
     * @return parsed argv (first element lower-case command, rest raw arguments)
     * @throws BusinessException 400 when rejected
     */
    public static List<String> requireReadOnly(String command) {
        if (command == null || command.isBlank()) {
            throw new BusinessException(400, "command is required");
        }
        List<String> argv = List.of(command.trim().split("\\s+"));
        if (argv.isEmpty() || argv.get(0).isBlank()) {
            throw new BusinessException(400, "command is required");
        }
        String verb = argv.get(0).toLowerCase(Locale.ROOT);
        if (DENIED.contains(verb)) {
            throw new BusinessException(400, "命令 " + verb.toUpperCase(Locale.ROOT)
                    + " 会修改数据,控制台只允许只读命令(如 GET/KEYS/HGETALL/SCAN/TYPE/TTL)");
        }
        if (!ALLOWED.contains(verb)) {
            throw new BusinessException(400, "不支持的 Redis 命令: " + verb.toUpperCase(Locale.ROOT)
                    + "。控制台只允许只读命令(如 GET/KEYS/HGETALL/LRANGE/SMEMBERS/ZRANGE/SCAN/TYPE/TTL/INFO)");
        }
        if (argv.size() > 8) {
            throw new BusinessException(400, "命令参数过多(最多 8 个)");
        }
        return argv;
    }
}
