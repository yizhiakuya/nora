package com.nora.datasource.api;

/**
 * datasource-service 的 Dubbo 契约(architecture-v2.md 5.1 节)。
 *
 * <p>提供方:services/datasource-service(JDBC 连接管理、schema 浏览、只读 SQL
 * 执行——{@code ConnectionPoolManager} 与 {@code SqlGuard})。消费方:
 * agent-service({@code NoraTools#executeSql} ReAct 工具)与 automation-service
 * (经 {@code sql.executed} 事件流)。
 *
 * <p>实现经 Dubbo 注册到 Nacos,消费方以 {@code @DubboReference} 注入。
 */
public interface DatasourceService {

    /**
     * 按 id 打开(或从池借用)连接并验证存活,测量往返延迟。支撑
     * {@code db_connection} 测试动作(architecture-v2.md 7.2 节)。
     *
     * @param connectionId 要测试的连接记录 id
     * @return 带 ok 标志、人类可读消息与实测延迟的状态
     */
    ConnectionStatus test(Long connectionId);

    /**
     * 返回所连数据库的表/列结构。
     *
     * @param connectionId 要内省的连接 id
     * @return 全部表及其列的
     * 快照
     */
    SchemaSnapshot schema(Long connectionId);

    /**
     * 对连接执行单条只读(SELECT/SHOW/EXPLAIN)语句。提供方 {@code SqlGuard}
     * 在执行前拒绝任何变更语句;不得信任调用方的 SQL。
     *
     * <p>按 ACI 设计(architecture-v2.md 4.8.3 节),提供方把结果截到有界行数并
     * 附带列类型,载荷保持 LLM 友好。
     *
     * @param connectionId 要查询的连接 id
     * @param sql          只读 SQL 语句
     * @return 字符串行结果集 + 列名与耗时
     */
    QueryResult executeReadOnly(Long connectionId, String sql);
}
