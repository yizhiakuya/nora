package com.nora.datasource.service;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.nora.common.exception.BusinessException;

/**
 * Redis 命令的只读守卫(对标键值引擎的 {@link SqlGuard})。
 * 只接受非变更命令的白名单;其余(SET/DEL/FLUSHALL/EVAL/…)在到达服务端前
 * 拒绝。命令为单行、空白分隔(刻意不支持带引号的 RESP 参数——控制台是
 * 只读窥探工具)。
 */
public final class RedisGuard {

    /** 控制台允许的只读命令(小写、首 token)。 */
    private static final Set<String> ALLOWED = Set.of(
            // 键空间 / 元数据
            "keys", "scan", "type", "ttl", "pttl", "exists", "dbsize", "randomkey", "object",
            // 字符串
            "get", "mget", "strlen", "getrange", "substr",
            // 哈希
            "hget", "hmget", "hgetall", "hkeys", "hvals", "hlen", "hexists", "hscan", "hrandfield",
            // 列表
            "lrange", "llen", "lindex", "lpos",
            // 集合
            "smembers", "scard", "sismember", "srandmember", "sscan", "smismember",
            // 有序集合
            "zrange", "zrevrange", "zrangebyscore", "zrevrangebyscore", "zrangebylex", "zcard",
            "zscore", "zmscore", "zrank", "zrevrank", "zcount", "zscan", "zrandmember",
            // 流 / 位图 / HyperLogLog(只读形式)
            "xrange", "xrevrange", "xlen", "xinfo", "getbit", "bitcount", "bitpos", "pfcount",
            // 服务信息
            "info", "time", "memory", "command", "lastsave", "lolwut");

    /** 显式拒绝清单,纵深防御(即使白名单混入笔误也挡住)。 */
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
     * 校验命令行是单条只读 Redis 命令。
     *
     * @param command 原始命令文本,如 {@code GET user:1} 或 {@code KEYS session:*}
     * @return 解析后的 argv(首元素小写命令,其余原样参数)
     * @throws BusinessException 拒绝时 400
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
