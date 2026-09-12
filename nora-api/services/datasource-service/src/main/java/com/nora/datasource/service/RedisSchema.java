package com.nora.datasource.service;

import com.nora.datasource.api.Column;
import com.nora.datasource.api.DbTable;
import com.nora.datasource.api.SchemaSnapshot;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.Limit;
import io.lettuce.core.Range;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders a Redis key space into the generic {@link SchemaSnapshot} shape so the
 * existing Schema browser UI can display it without a Redis-specific view:
 *
 * <ul>
 *   <li>each key becomes a "table" (grouped under its logical db, e.g. {@code DB0});</li>
 *   <li>the key's content becomes "columns": string→value, hash→fields, list→elements,
 *       set→members, zset→member+score, stream→first entries.</li>
 * </ul>
 *
 * <p>Bounded: at most {@link #MAX_KEYS} keys and {@link #MAX_ELEMENTS} entries per key,
 * keeping payloads browser- and LLM-friendly.
 */
public final class RedisSchema {

    static final int MAX_KEYS = 200;
    static final int MAX_ELEMENTS = 50;
    /** Value preview length per element (bytes of display text). */
    private static final int PREVIEW = 120;

    private RedisSchema() {
    }

    /**
     * Builds the key-space snapshot for one connection.
     *
     * @param commands sync commands from the caller's connection
     * @param dbIndex  logical database index (for the group label)
     */
    public static SchemaSnapshot snapshot(RedisCommands<String, String> commands, int dbIndex) {
        String group = "DB" + dbIndex;
        KeyScanCursor<String> cursor = commands.scan(ScanCursor.INITIAL, ScanArgs.Builder.limit(MAX_KEYS));
        List<DbTable> tables = new ArrayList<>();
        for (String key : cursor.getKeys()) {
            if (tables.size() >= MAX_KEYS) {
                break;
            }
            tables.add(keyTable(commands, group, key));
        }
        return new SchemaSnapshot(tables);
    }

    private static DbTable keyTable(RedisCommands<String, String> commands, String group, String key) {
        String type = safe(() -> commands.type(key), "unknown");
        long ttl = safe(() -> commands.ttl(key), -1L);
        List<Column> columns = switch (type) {
            case "string" -> List.of(col("value", "string", preview(safe(() -> commands.get(key), null))));
            case "hash" -> hashColumns(commands, key);
            case "list" -> listColumns(commands, key);
            case "set" -> setColumns(commands, key);
            case "zset" -> zsetColumns(commands, key);
            case "stream" -> streamColumns(commands, key);
            default -> List.of(col("(unreadable)", type, "无法读取的类型: " + type));
        };
        String comment = "类型 " + type + (ttl >= 0 ? " · TTL " + ttl + "s" : " · 永久") + " · " + columns.size() + " 项";
        return new DbTable(group, key, comment, columns);
    }

    private static List<Column> hashColumns(RedisCommands<String, String> commands, String key) {
        Map<String, String> map = safe(() -> commands.hgetall(key), Map.of());
        List<Column> cols = new ArrayList<>();
        for (Map.Entry<String, String> e : map.entrySet()) {
            if (cols.size() >= MAX_ELEMENTS) {
                break;
            }
            cols.add(col(e.getKey(), "hash-field", preview(e.getValue())));
        }
        return cols;
    }

    private static List<Column> listColumns(RedisCommands<String, String> commands, String key) {
        List<String> values = safe(() -> commands.lrange(key, 0, MAX_ELEMENTS - 1), List.of());
        List<Column> cols = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            cols.add(col(String.valueOf(i), "list-element", preview(values.get(i))));
        }
        return cols;
    }

    private static List<Column> setColumns(RedisCommands<String, String> commands, String key) {
        List<String> values = new ArrayList<>(safe(() -> commands.srandmember(key, (long) MAX_ELEMENTS), List.of()));
        List<Column> cols = new ArrayList<>();
        for (String v : values) {
            cols.add(col(preview(v), "set-member", ""));
        }
        return cols;
    }

    private static List<Column> zsetColumns(RedisCommands<String, String> commands, String key) {
        List<ScoredValue<String>> values = safe(() -> commands.zrangeWithScores(key, 0, MAX_ELEMENTS - 1), List.of());
        List<Column> cols = new ArrayList<>();
        for (ScoredValue<String> v : values) {
            cols.add(col(v.getValue(), "zset-member", "score=" + v.getScore()));
        }
        return cols;
    }

    private static List<Column> streamColumns(RedisCommands<String, String> commands, String key) {
        List<io.lettuce.core.StreamMessage<String, String>> messages =
                safe(() -> commands.xrange(key, Range.create("-", "+"), Limit.from(MAX_ELEMENTS)),
                        List.<io.lettuce.core.StreamMessage<String, String>>of());
        List<Column> cols = new ArrayList<>();
        for (io.lettuce.core.StreamMessage<String, String> m : messages) {
            cols.add(col(m.getId(), "stream-entry", preview(m.getBody().toString())));
        }
        return cols;
    }

    private static Column col(String name, String type, String comment) {
        return new Column(name, type, comment == null ? "" : comment, true, false, null);
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= PREVIEW ? oneLine : oneLine.substring(0, PREVIEW) + "…";
    }

    /** Runs a read and falls back to a default instead of failing the whole snapshot. */
    private static <T> T safe(java.util.function.Supplier<T> supplier, T fallback) {
        try {
            T value = supplier.get();
            return value == null ? fallback : value;
        } catch (Exception e) {
            return fallback;
        }
    }
}
