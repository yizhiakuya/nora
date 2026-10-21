package com.nora.agent.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.nora.datasource.service.DatasourceServiceImpl;

/** 数据源工具只负责模型可读的展示；校验、凭据与执行由领域服务统一处理。 */
@Service
public class DataSourceTools {
    private static final Logger log = LoggerFactory.getLogger(DataSourceTools.class);
    private final DatasourceServiceImpl datasource;
    private final SqlTools sql;

    public DataSourceTools(DatasourceServiceImpl datasource, SqlTools sql) {
        this.datasource = datasource;
        this.sql = sql;
    }

    public String create(String name, String engine, String host, Integer port,
                         String database, String username, String password) {
        try {
            var row = datasource.create(name, engine == null ? null : engine.trim().toLowerCase(java.util.Locale.ROOT),
                    host, port, database, username, password == null ? "" : password);
            return "已创建数据源 id=" + row.id() + " name=" + row.name() + " engine=" + row.engine()
                    + " host=" + row.host() + ":" + row.port() + " database=" + row.database() + "\n" + test(row.id());
        } catch (RuntimeException e) { return "ERROR: " + e.getMessage(); }
    }

    public String test(long id) {
        try {
            var status = datasource.test(id);
            return "连接测试: " + (status.ok() ? "成功" : "失败") + " — " + status.message()
                    + (status.latencyMs() == null ? "" : " (" + status.latencyMs() + "ms)");
        } catch (RuntimeException e) { return "ERROR: " + e.getMessage(); }
    }

    public String remove(long id) {
        return datasource.delete(id) ? "已删除数据源 id=" + id + "(其查询历史一并删除)" : "ERROR: 数据源不存在: " + id;
    }

    public String nameSummary() {
        try {
            var rows = datasource.list();
            if (rows.isEmpty()) return null;
            return rows.stream().map(row -> "- " + row.name() + "(" + row.engine() + ", " + row.database() + ")")
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (RuntimeException e) {
            log.debug("datasource summary unavailable: {}", e.getMessage());
            return null;
        }
    }

    public String list() {
        var rows = datasource.list();
        if (rows.isEmpty()) return "(尚无数据源)";
        return rows.stream().map(row -> "id=" + row.id() + " | " + row.name() + " | " + row.engine()
                + " | " + row.host() + ":" + row.port() + "/" + row.database() + " | status=" + row.status()
                + " | user=" + row.username()).collect(java.util.stream.Collectors.joining("\n"));
    }

    public String schema(String target) {
        Long id = sql.resolveConnectionId(target);
        if (id == null) return "ERROR: 未找到唯一的目标数据源。可用连接:\n" + list();
        var tables = datasource.schema(id).tables();
        if (tables.isEmpty()) return "(该连接没有可见的表)";
        StringBuilder out = new StringBuilder();
        for (var table : tables) {
            if (table.schema() != null && !table.schema().isBlank()) out.append(table.schema()).append('.');
            out.append(table.name());
            if (table.comment() != null && !table.comment().isBlank()) out.append(" -- ").append(table.comment());
            out.append('(');
            out.append(table.columns().stream().map(column -> column.name() + " " + column.type()
                    + (column.primaryKey() ? " PK" : "")
                    + (column.comment() == null || column.comment().isBlank() ? "" : " -- " + column.comment()))
                    .collect(java.util.stream.Collectors.joining(", "))).append(")\n");
            if (out.length() > 20_000) { out.append("…[schema 过大已截断，请用 SQL 查询剩余结构]\n"); break; }
        }
        return out.toString().stripTrailing();
    }
}
