package com.nora.datasource.api;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DatasourceApiRecordsTest {

    @Test
    void connectionStatusExposesAllFields() {
        ConnectionStatus status = new ConnectionStatus(true, "connected", 37L);

        assertEquals(true, status.ok());
        assertEquals("connected", status.message());
        assertEquals(37L, status.latencyMs());
    }

    @Test
    void schemaSnapshotRoundTripKeepsNestedTablesAndColumns() {
        SchemaSnapshot snapshot = new SchemaSnapshot(List.of(
                new DbTable("db_connection", List.of(
                        new Column("id", "int8"),
                        new Column("name", "varchar"),
                        new Column("url", "varchar"))),
                new DbTable("outbox", List.of(
                        new Column("id", "bigserial"),
                        new Column("topic", "varchar")))));

        assertEquals(2, snapshot.tables().size());
        assertEquals("db_connection", snapshot.tables().get(0).name());
        assertEquals(3, snapshot.tables().get(0).columns().size());
        assertEquals("id", snapshot.tables().get(0).columns().get(0).name());
        assertEquals("int8", snapshot.tables().get(0).columns().get(0).type());
        assertEquals("topic", snapshot.tables().get(1).columns().get(1).name());
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

        assertEquals(List.of("id", "name"), result.columns());
        assertEquals(2, result.rows().size());
        assertEquals("nora", result.rows().get(0).get(1));
        assertEquals(null, result.rows().get(1).get(1));
        assertEquals(2, result.rowCount());
        assertEquals(125L, result.durationMs());
    }

    @Test
    void equalRecordsAreEqualAndDifferentRecordsAreNot() {
        Column first = new Column("id", "int8");
        Column same = new Column("id", "int8");
        Column other = new Column("name", "varchar");

        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, other);
    }
}
