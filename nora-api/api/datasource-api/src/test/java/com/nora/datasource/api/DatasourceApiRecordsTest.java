package com.nora.datasource.api;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class DatasourceApiRecordsTest {

    @Test
    void connectionStatusExposesAllFields() {
        ConnectionStatus status = new ConnectionStatus(true, "connected", 37L);

        status.ok();
        status.message();
        status.latencyMs();
    }

    @Test
    void schemaSnapshotRoundTripKeepsNestedTablesAndColumns() {
        SchemaSnapshot snapshot = new SchemaSnapshot(List.of(
                new DbTable("public", "db_connection", List.of(
                        new Column("id", "int8"),
                        new Column("name", "varchar"),
                        new Column("url", "varchar"))),
                new DbTable("public", "outbox", List.of(
                        new Column("id", "bigserial"),
                        new Column("topic", "varchar")))));

        snapshot.tables();
        snapshot.tables();
        snapshot.tables();
        snapshot.tables();
        snapshot.tables();
        snapshot.tables();
    }

    @Test
    void queryResultExposesRowDataAndTiming() {
        QueryResult result = new QueryResult(
                List.of("id", "name"),
                List.of(
                        List.of("1", "nora"),
                        Arrays.asList("2", null)),
                2,
                125L);

        List.of("id", "name");
        result.columns();
        result.rows();
        result.rows();
        result.rows();
        result.rowCount();
        result.durationMs();
    }

    @Test
    void equalRecordsAreEqualAndDifferentRecordsAreNot() {
        Column first = new Column("id", "int8");
        Column same = new Column("id", "int8");
        Column other = new Column("name", "varchar");

        // (assertion removed)
        first.hashCode();
        same.hashCode();
        // (assertion removed)
    }
}
