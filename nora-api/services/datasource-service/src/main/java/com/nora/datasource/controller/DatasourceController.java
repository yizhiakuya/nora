package com.nora.datasource.controller;

import com.nora.common.response.ApiResponse;
import com.nora.datasource.api.ConnectionStatus;
import com.nora.datasource.api.QueryResult;
import com.nora.datasource.api.SchemaSnapshot;
import com.nora.datasource.service.DatasourceServiceImpl;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Datasource endpoints per the initiation doc REST contract:
 * connection CRUD, connectivity test, schema browse, guarded read-only
 * query, and query history.
 */
@RestController
@RequestMapping("/api/datasources")
public class DatasourceController {

    private final DatasourceServiceImpl service;

    public DatasourceController(DatasourceServiceImpl service) {
        this.service = service;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    /** Lists connections (passwords masked). */
    @GetMapping
    public ApiResponse<List<DatasourceServiceImpl.ConnectionView>> list() {
        return ApiResponse.ok(service.list());
    }

    /** Creates a connection record. */
    @PostMapping
    public ApiResponse<DatasourceServiceImpl.ConnectionView> create(@RequestBody CreateRequest request) {
        return ApiResponse.ok(service.create(request.name(), request.engine(), request.host(),
                request.port(), request.database(), request.username(), request.password()));
    }

    /** Deletes a connection (history cascades). */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        if (!service.delete(id)) {
            throw new com.nora.common.exception.BusinessException(404, "connection not found: " + id);
        }
        return ApiResponse.ok();
    }

    /** Tests connectivity with the stored credentials. */
    @PostMapping("/{id}/test")
    public ApiResponse<ConnectionStatus> test(@PathVariable long id) {
        return ApiResponse.ok(service.test(id));
    }

    /** Table/column structure of the connected database. */
    @GetMapping("/{id}/schema")
    public ApiResponse<SchemaSnapshot> schema(@PathVariable long id) {
        return ApiResponse.ok(service.schema(id));
    }

    /** Executes a guarded read-only statement. */
    @PostMapping("/{id}/query")
    public ApiResponse<QueryResult> query(@PathVariable long id, @RequestBody QueryRequest request) {
        return ApiResponse.ok(service.executeReadOnly(id, request.sql()));
    }

    /** Query history of a connection, newest first. */
    @GetMapping("/{id}/history")
    public ApiResponse<List<DatasourceServiceImpl.HistoryView>> history(
            @PathVariable long id,
            @RequestParam(value = "limit", required = false) Integer limit) {
        int bounded = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        return ApiResponse.ok(service.history(id, bounded));
    }

    /** POST /api/datasources body. */
    public record CreateRequest(
            String name, String engine, String host, Integer port,
            String database, String username, String password) {
    }

    /** POST /{id}/query body. */
    public record QueryRequest(String sql) {
    }
}
