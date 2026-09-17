package com.nora.datasource.controller;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.nora.common.response.ApiResponse;
import com.nora.datasource.api.ConnectionStatus;
import com.nora.datasource.api.QueryResult;
import com.nora.datasource.api.SchemaSnapshot;
import com.nora.datasource.service.DatasourceServiceImpl;

/**
 * 数据源端点(立项文档 REST 契约):连接 CRUD、连通测试、schema 浏览、
 * 受控只读查询与查询历史。
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

    /** 列出连接(密码脱敏)。 */
    @GetMapping
    public ApiResponse<List<DatasourceServiceImpl.ConnectionView>> list() {
        return ApiResponse.ok(service.list());
    }

    /** 创建连接记录。 */
    @PostMapping
    public ApiResponse<DatasourceServiceImpl.ConnectionView> create(@RequestBody CreateRequest request) {
        return ApiResponse.ok(service.create(request.name(), request.engine(), request.host(),
                request.port(), request.database(), request.username(), request.password()));
    }

    /** 删除连接(历史级联)。 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        if (!service.delete(id)) {
            throw new com.nora.common.exception.BusinessException(404, "connection not found: " + id);
        }
        return ApiResponse.ok();
    }

    /** 用已存凭证测试连通。 */
    @PostMapping("/{id}/test")
    public ApiResponse<ConnectionStatus> test(@PathVariable long id) {
        return ApiResponse.ok(service.test(id));
    }

    /** 所连数据库的表/列结构。 */
    @GetMapping("/{id}/schema")
    public ApiResponse<SchemaSnapshot> schema(@PathVariable long id) {
        return ApiResponse.ok(service.schema(id));
    }

    /** 执行受控只读语句。 */
    @PostMapping("/{id}/query")
    public ApiResponse<QueryResult> query(@PathVariable long id, @RequestBody QueryRequest request) {
        return ApiResponse.ok(service.executeReadOnly(id, request.sql()));
    }

    /**
     * 在 agent 审批流之后执行单条写语句(INSERT/UPDATE/DELETE/DDL)。受控:
     * 单条语句、仅写动词、经驱动等价 max-rows 限制变更行数(语句超时 30s)。
     * 仅在用户明确批准后调用。
     */
    @PostMapping("/{id}/execute")
    public ApiResponse<QueryResult> execute(@PathVariable long id, @RequestBody QueryRequest request) {
        return ApiResponse.ok(service.executeWrite(id, request.sql()));
    }

    /** 连接的查询历史,最新在前。 */
    @GetMapping("/{id}/history")
    public ApiResponse<List<DatasourceServiceImpl.HistoryView>> history(
            @PathVariable long id,
            @RequestParam(value = "limit", required = false) Integer limit) {
        int bounded = limit == null ? 50 : Math.min(Math.max(limit, 1), 200);
        return ApiResponse.ok(service.history(id, bounded));
    }

    /** POST /api/datasources 请求体。 */
    public record CreateRequest(
            String name, String engine, String host, Integer port,
            String database, String username, String password) {
    }

    /** POST /{id}/query 请求体。 */
    public record QueryRequest(String sql) {
    }
}
