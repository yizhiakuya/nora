package com.nora.datasource.service;

import com.nora.common.exception.BusinessException;
import com.nora.datasource.api.QueryResult;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.KeyValue;
import io.lettuce.core.Limit;
import io.lettuce.core.MapScanCursor;
import io.lettuce.core.Range;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ValueScanCursor;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Executes guarded read-only Redis commands and renders their replies into the
 * generic {@link QueryResult} shape so the existing console UI can display them
 * without a Redis-specific protocol.
 *
 * <p>Reply rendering: flat replies (GET/MGET/HGETALL/…) become a two-column
 * {@code key | value} table; {@code SCAN}/{@code KEYS} list keys. The scan family
 * is bounded by {@link #MAX_KEYS} to keep payloads LLM/browser friendly.
 */
public final class RedisExecutor {

    /** Max keys/entries rendered for a single command (bounded payloads). */
    static final int MAX_KEYS = 200;

    private RedisExecutor() {
    }

    /**
     * Runs one read-only command on an open connection.
     *
     * @param commands sync command interface from the caller's connection
     * @param argv     validated argv from {@link RedisGuard#requireReadOnly(String)}
     */
    public static QueryResult execute(RedisCommands<String, String> commands, List<String> argv) {
        String verb = argv.get(0).toLowerCase(java.util.Locale.ROOT);
        String key = argv.size() > 1 ? argv.get(1) : null;
        long start = System.currentTimeMillis();
        try {
            return switch (verb) {
                case "keys" -> keysResult(commands, argv);
                case "scan" -> scanResult(commands, argv);
                case "type" -> single("type", key, commands.type(key));
                case "ttl" -> single("ttl", key, commands.ttl(key));
                case "pttl" -> single("pttl", key, commands.pttl(key));
                case "dbsize" -> single("dbsize", "(db)", commands.dbsize());
                case "get" -> single("get", key, commands.get(key));
                case "mget" -> mgetResult(commands, argv);
                case "strlen" -> single("strlen", key, commands.strlen(key));
                case "getrange", "substr" -> single("getrange", key,
                        commands.getrange(key, longArg(argv, 2, 0), longArg(argv, 3, -1)));
                case "hget" -> single("hget", key + " " + argv.get(2), commands.hget(key, argv.get(2)));
                case "hgetall" -> hashResult(commands.hgetall(key));
                case "hkeys" -> listResult("field", commands.hkeys(key));
                case "hvals" -> listResult("value", commands.hvals(key));
                case "hlen" -> single("hlen", key, commands.hlen(key));
                case "hexists" -> single("hexists", key + " " + argv.get(2), commands.hexists(key, argv.get(2)));
                case "hscan" -> hashResult(commands.hscan(key, scanArgs(argv)).getMap());
                case "hrandfield" -> listResult("field", commands.hrandfield(key, (long) MAX_KEYS));
                case "lrange" -> listResult("value",
                        commands.lrange(key, longArg(argv, 2, 0), longArg(argv, 3, -1)));
                case "llen" -> single("llen", key, commands.llen(key));
                case "lindex" -> single("lindex", key, commands.lindex(key, longArg(argv, 2, 0)));
                case "lpos" -> single("lpos", key + " " + argv.get(2), commands.lpos(key, argv.get(2)));
                case "smembers" -> listResult("member", new ArrayList<>(commands.smembers(key)));
                case "scard" -> single("scard", key, commands.scard(key));
                case "sismember" -> single("sismember", key + " " + argv.get(2), commands.sismember(key, argv.get(2)));
                case "smismember" -> membersResult(commands, argv);
                case "srandmember" -> listResult("member", commands.srandmember(key, (long) MAX_KEYS));
                case "sscan" -> valueScanResult(commands.sscan(key, scanArgs(argv)));
                case "zrange", "zrevrange" -> zsetResult(verb.equals("zrevrange")
                        ? commands.zrevrangeWithScores(key, longArg(argv, 2, 0), longArg(argv, 3, -1))
                        : commands.zrangeWithScores(key, longArg(argv, 2, 0), longArg(argv, 3, -1)));
                case "zrangebyscore", "zrevrangebyscore" -> zsetResult(verb.equals("zrevrangebyscore")
                        ? commands.zrevrangebyscoreWithScores(key, doubleArg(argv, 2, "-inf"), doubleArg(argv, 3, "+inf"))
                        : commands.zrangebyscoreWithScores(key, doubleArg(argv, 2, "-inf"), doubleArg(argv, 3, "+inf")));
                case "zrangebylex" -> listResult("member",
                        commands.zrangebylex(key, rangeArg(argv, 2, "-"), rangeArg(argv, 3, "+")));
                case "zcard" -> single("zcard", key, commands.zcard(key));
                case "zscore" -> single("zscore", key + " " + argv.get(2), commands.zscore(key, argv.get(2)));
                case "zrank" -> single("zrank", key + " " + argv.get(2), commands.zrank(key, argv.get(2)));
                case "zrevrank" -> single("zrevrank", key + " " + argv.get(2), commands.zrevrank(key, argv.get(2)));
                case "zcount" -> single("zcount", key, commands.zcount(key, rangeArg(argv, 2, "-inf"), rangeArg(argv, 3, "+inf")));
                case "zscan" -> zscanResult(commands, argv);
                case "zrandmember" -> zsetResult(commands.zrandmemberWithScores(key, (long) MAX_KEYS));
                case "xlen" -> single("xlen", key, commands.xlen(key));
                case "xrange" -> xrangeResult(commands, argv, false);
                case "xrevrange" -> xrangeResult(commands, argv, true);
                case "xinfo" -> xinfoResult(commands, argv);
                case "getbit" -> single("getbit", key, commands.getbit(key, longArg(argv, 2, 0)));
                case "bitcount" -> single("bitcount", key, commands.bitcount(key));
                case "bitpos" -> single("bitpos", key, commands.bitpos(key, longArg(argv, 2, 0) != 0));
                case "pfcount" -> single("pfcount", key, commands.pfcount(key));
                case "info" -> infoResult(commands);
                case "time" -> listResult("value", commands.time());
                case "dbsize-raw" -> single("dbsize", "(db)", commands.dbsize());
                default -> throw new BusinessException(400, "命令未实现: " + verb);
            };
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "Redis 命令执行失败: " + shorten(e.getMessage()));
        }
    }

    private static QueryResult keysResult(RedisCommands<String, String> commands, List<String> argv) {
        String pattern = argv.size() > 1 ? argv.get(1) : "*";
        List<String> keys = new ArrayList<>();
        for (String k : commands.keys(pattern)) {
            keys.add(k);
            if (keys.size() >= MAX_KEYS) {
                break;
            }
        }
        boolean truncated = keys.size() >= MAX_KEYS;
        List<List<String>> rows = new ArrayList<>(keys.size());
        for (String k : keys) {
            rows.add(List.of(k));
        }
        return new QueryResult(List.of("key"), rows, rows.size(), 0, truncated);
    }

    private static QueryResult scanResult(RedisCommands<String, String> commands, List<String> argv) {
        ScanArgs args = scanArgs(argv);
        KeyScanCursor<String> cursor = commands.scan(ScanCursor.INITIAL, args.limit(MAX_KEYS));
        List<List<String>> rows = new ArrayList<>();
        for (String k : cursor.getKeys()) {
            rows.add(List.of(k));
        }
        return new QueryResult(List.of("key"), rows, rows.size(), 0, !cursor.isFinished());
    }

    private static QueryResult mgetResult(RedisCommands<String, String> commands, List<String> argv) {
        List<String> keys = argv.subList(1, argv.size());
        List<KeyValue<String, String>> values = commands.mget(keys.toArray(new String[0]));
        List<List<String>> rows = new ArrayList<>();
        for (KeyValue<String, String> kv : values) {
            rows.add(Arrays.asList(kv.getKey(), kv.getValue()));
        }
        return new QueryResult(List.of("key", "value"), rows, rows.size(), 0, false);
    }

    private static QueryResult membersResult(RedisCommands<String, String> commands, List<String> argv) {
        List<String> members = argv.subList(2, argv.size());
        List<Boolean> flags = commands.smismember(argv.get(1), members.toArray(new String[0]));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < members.size(); i++) {
            rows.add(List.of(members.get(i), String.valueOf(flags.get(i))));
        }
        return new QueryResult(List.of("member", "exists"), rows, rows.size(), 0, false);
    }

    private static QueryResult hashResult(Map<String, String> map) {
        List<List<String>> rows = new ArrayList<>();
        int i = 0;
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (i++ >= MAX_KEYS) {
                break;
            }
            rows.add(List.of(e.getKey(), e.getValue()));
        }
        return new QueryResult(List.of("field", "value"), rows, rows.size(), 0, map.size() > MAX_KEYS);
    }

    private static QueryResult valueScanResult(ValueScanCursor<String> cursor) {
        List<List<String>> rows = new ArrayList<>();
        for (String v : cursor.getValues()) {
            rows.add(List.of(v));
        }
        return new QueryResult(List.of("member"), rows, rows.size(), 0, !cursor.isFinished());
    }

    private static QueryResult zscanResult(RedisCommands<String, String> commands, List<String> argv) {
        io.lettuce.core.ScoredValueScanCursor<String> cursor = commands.zscan(argv.get(1), scanArgs(argv));
        List<List<String>> rows = new ArrayList<>();
        for (ScoredValue<String> v : cursor.getValues()) {
            rows.add(List.of(v.getValue(), String.valueOf(v.getScore())));
        }
        return new QueryResult(List.of("member", "score"), rows, rows.size(), 0, !cursor.isFinished());
    }

    private static QueryResult zsetResult(List<ScoredValue<String>> values) {
        List<List<String>> rows = new ArrayList<>();
        for (ScoredValue<String> v : values) {
            if (rows.size() >= MAX_KEYS) {
                break;
            }
            rows.add(List.of(v.getValue(), String.valueOf(v.getScore())));
        }
        return new QueryResult(List.of("member", "score"), rows, rows.size(), 0, values.size() > MAX_KEYS);
    }

    private static QueryResult xrangeResult(RedisCommands<String, String> commands, List<String> argv, boolean reverse) {
        String start = argv.size() > 2 ? argv.get(2) : "-";
        String end = argv.size() > 3 ? argv.get(3) : "+";
        var messages = reverse
                ? commands.xrevrange(argv.get(1), Range.create(end, start), Limit.from(MAX_KEYS))
                : commands.xrange(argv.get(1), Range.create(start, end), Limit.from(MAX_KEYS));
        List<List<String>> rows = new ArrayList<>();
        for (var m : messages) {
            if (rows.size() >= MAX_KEYS) {
                break;
            }
            rows.add(List.of(m.getId(), m.getBody().toString()));
        }
        return new QueryResult(List.of("id", "body"), rows, rows.size(), 0, messages.size() > MAX_KEYS);
    }

    private static QueryResult xinfoResult(RedisCommands<String, String> commands, List<String> argv) {
        String sub = argv.size() > 1 ? argv.get(1) : "stream";
        if (argv.size() > 2 && sub.equalsIgnoreCase("stream")) {
            Map<String, Object> info = toMap(commands.xinfoStream(argv.get(2)));
            List<List<String>> rows = new ArrayList<>();
            for (Map.Entry<String, Object> e : info.entrySet()) {
                rows.add(List.of(e.getKey(), String.valueOf(e.getValue())));
            }
            return new QueryResult(List.of("field", "value"), rows, rows.size(), 0, false);
        }
        if (sub.equalsIgnoreCase("groups") && argv.size() > 2) {
            return listResult("group", commands.xinfoGroups(argv.get(2)).stream().map(String::valueOf).toList());
        }
        if (sub.equalsIgnoreCase("consumers") && argv.size() > 3) {
            return listResult("consumer", commands.xinfoConsumers(argv.get(2), argv.get(3)).stream().map(String::valueOf).toList());
        }
        throw new BusinessException(400, "XINFO 用法: XINFO STREAM key | XINFO GROUPS key | XINFO CONSUMERS key group");
    }

    /** Converts the flat key/value list returned by XINFO STREAM into a map. */
    private static Map<String, Object> toMap(List<Object> flat) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < flat.size(); i += 2) {
            map.put(String.valueOf(flat.get(i)), flat.get(i + 1));
        }
        return map;
    }

    private static QueryResult infoResult(RedisCommands<String, String> commands) {
        String info = commands.info();
        List<List<String>> rows = new ArrayList<>();
        for (String line : info.split("\r?\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            int idx = line.indexOf(':');
            if (idx > 0) {
                rows.add(List.of(line.substring(0, idx), line.substring(idx + 1)));
            }
        }
        return new QueryResult(List.of("field", "value"), rows, rows.size(), 0, false);
    }

    private static QueryResult single(String ignoredColumn, String key, Object value) {
        String rendered = value == null ? null : String.valueOf(value);
        List<List<String>> rows = List.of(Arrays.asList(key, rendered));
        return new QueryResult(List.of("key", "value"), rows, 1, 0, false);
    }

    /** Removes the noisy leading blank of LIST results before rendering. */
    private static List<String> nonNull(List<String> values) {
        List<String> out = new ArrayList<>(values.size());
        for (String v : values) {
            out.add(v == null ? "" : v);
        }
        return out;
    }

    private static QueryResult listResult(String column, List<String> values) {
        List<List<String>> rows = new ArrayList<>();
        for (String v : values) {
            if (rows.size() >= MAX_KEYS) {
                break;
            }
            rows.add(Arrays.asList(v));
        }
        return new QueryResult(List.of(column), rows, rows.size(), 0, values.size() > MAX_KEYS);
    }

    private static ScanArgs scanArgs(List<String> argv) {
        ScanArgs args = new ScanArgs();
        if (argv.size() > 2) {
            args.match(argv.get(2));
        }
        args.limit(MAX_KEYS);
        return args;
    }

    private static long longArg(List<String> argv, int idx, long dflt) {
        if (argv.size() <= idx) {
            return dflt;
        }
        try {
            return Long.parseLong(argv.get(idx));
        } catch (NumberFormatException e) {
            return dflt;
        }
    }

    private static double doubleArg(List<String> argv, int idx, String dflt) {
        if (argv.size() <= idx) {
            return dflt.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        try {
            return Double.parseDouble(argv.get(idx));
        } catch (NumberFormatException e) {
            return dflt.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
    }

    private static String rangeArg(List<String> argv, int idx, String dflt) {
        return argv.size() > idx ? argv.get(idx) : dflt;
    }

    private static String shorten(String message) {
        if (message == null) {
            return "unknown error";
        }
        return message.length() <= 300 ? message : message.substring(0, 300) + "…";
    }
}
