package com.nora.datasource.api;

/**
 * Dubbo contract for datasource-service (per architecture-v2.md section 5.1).
 *
 * <p>Provider: services/datasource-service (JDBC connection management, schema
 * browsing, read-only SQL execution — {@code ConnectionPoolManager} and
 * {@code SqlGuard}). Consumers: agent-service ({@code NoraTools#executeSql}
 * ReAct tool) and automation-service (via the {@code sql.executed} event flow).
 *
 * <p>Implementations are registered to Nacos via Dubbo and injected on the
 * consumer side with {@code @DubboReference}.
 */
public interface DatasourceService {

    /**
     * Opens (or borrows from the pool) a connection by id and verifies it is alive,
     * measuring round-trip latency. Backs the {@code db_connection} test action
     * (architecture-v2.md section 7.2).
     *
     * @param connectionId id of the connection record to test
     * @return status with ok flag, human-readable message and measured latency
     */
    ConnectionStatus test(Long connectionId);

    /**
     * Returns the table/column structure of the connected database.
     *
     * @param connectionId id of the connection to introspect
     * @return snapshot of all tables and their columns
     */
    SchemaSnapshot schema(Long connectionId);

    /**
     * Executes a single read-only (SELECT/SHOW/EXPLAIN) statement against the
     * connection. The provider-side {@code SqlGuard} rejects any mutating
     * statement before execution; SQL must not be trusted from the caller.
     *
     * <p>Per the ACI design (architecture-v2.md section 4.8.3) the result is
     * truncated by the provider to a bounded row count with column types
     * attached, so the payload stays LLM-friendly.
     *
     * @param connectionId id of the connection to query on
     * @param sql          read-only SQL statement
     * @return result set as string rows plus column names and timing
     */
    QueryResult executeReadOnly(Long connectionId, String sql);
}
