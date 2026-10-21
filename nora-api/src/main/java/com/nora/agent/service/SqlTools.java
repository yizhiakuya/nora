package com.nora.agent.service;

import java.util.ArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;


/** 数据库工具共用领域执行入口与唯一目标解析规则。 */
@Service
public class SqlTools {

    private static final Logger log = LoggerFactory.getLogger(SqlTools.class);

    private final com.nora.datasource.service.DatasourceServiceImpl datasource;

    public SqlTools(com.nora.datasource.service.DatasourceServiceImpl datasource) {
        this.datasource = datasource;
    }

    public String executeSql(String sql) {
        return executeSqlDetailed(sql).content();
    }

    @dev.langchain4j.agent.tool.Tool(name = "execute_sql", value = "在已连接的数据源执行 SQL 或 Redis 命令，支持查询、更新和结构修改。"
            + "SQL 可包含多条语句，返回最后一组结果；查询最多展示 200 行。Redis 使用原生命令，如 GET key / SET key value / HGETALL key。"
            + "修改会真实生效，调用前确认目标数据源和用户意图；失败且结果未知时先核对，避免重复写入。")
    public SqlOutcome executeSqlDetailed(
            @dev.langchain4j.agent.tool.P(name = "sql", value = "SQL 文本，或目标为 Redis 时的原生命令") String sql,
            @dev.langchain4j.agent.tool.P(name = "datasource", required = false,
                    value = "目标连接名或 id，以 manage_datasource action=list 返回值为准；仅有一个连接时可省略") String datasourceId) {
        if (sql == null || sql.isBlank()) return new SqlOutcome("ERROR: 缺少 sql 参数", null, false, false);
        Long connectionId = resolveConnectionId(datasourceId);
        if (connectionId == null) {
            return new SqlOutcome("ERROR: 未找到唯一的目标数据源，请用 manage_datasource action=list 确认名称或 id",
                    null, false, false);
        }
        try {
            var result = datasource.execute(connectionId, sql);
            return new SqlOutcome(result.toTsv(), result.rowCount() + " rows, " + result.durationMs() + "ms",
                    result.truncated(), false);
        } catch (com.nora.common.exception.BusinessException e) {
            return e.getCode() < 500 ? new SqlOutcome("ERROR: " + hint(e.getMessage()), null, false, false)
                    : unknown(e.getMessage());
        } catch (org.springframework.dao.DataAccessException e) {
            log.warn("database execution outcome unknown: {}", e.getMessage());
            return unknown(e.getMessage());
        }
    }

    public SqlOutcome executeSqlDetailed(String sql) {
        return executeSqlDetailed(sql, null);
    }

    /** 显式目标必须精确匹配；仅有一个连接时才允许省略。 */
    public Long resolveConnectionId(String datasourceId) {
        var connections = datasource.list();
        if (datasourceId == null || datasourceId.isBlank()) {
            return connections.size() == 1 ? connections.getFirst().id() : null;
        }
        String target = datasourceId.trim();
        var names = new ArrayList<Long>();
        for (var row : connections) {
            if (target.equals(Long.toString(row.id()))) return row.id();
            if (target.equalsIgnoreCase(row.name())) names.add(row.id());
        }
        return names.size() == 1 ? names.getFirst() : null;
    }

    private static SqlOutcome unknown(String message) {
        return new SqlOutcome("ERROR: 数据库命令结果未知: " + message
                + "。请求可能已生效；先查询核对，不能直接重发。", null, false, true);
    }

    public record SqlOutcome(String content, String summary, boolean truncated, boolean outcomeUnknown) { }

    /**
     * 为常见 DB 错误补充正确的替代语法,让模型下一轮自我纠正
     * (harness 原则:错误即教学)。
     */
    private String hint(String message) {
        if (message != null && message.contains("unrecognized configuration parameter")) {
            // MySQL 的 SHOW TABLES / USE db 等在 PostgreSQL 上不存在
            return message + "（提示:目标数据库是 PostgreSQL,不支持 MySQL 的 SHOW 语法。"
                    + "查表用 SELECT tablename FROM pg_tables WHERE schemaname = 'public';"
                    + "查库用 SELECT datname FROM pg_database;查列用 SELECT column_name, data_type "
                    + "FROM information_schema.columns WHERE table_name = '表名'）";
        }
        if (message != null && message.contains("does not exist") && message.contains("relation")) {
            return message + "（提示:表不存在或不在默认 schema。"
                    + "先用 SELECT tablename FROM pg_tables 确认表名,跨 schema 时用 schema.table 限定）";
        }
        return message;
    }

}
