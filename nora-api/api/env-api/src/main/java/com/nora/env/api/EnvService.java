package com.nora.env.api;

import java.util.List;

/**
 * env-service 对外 Dubbo 接口。
 *
 * <p>对应架构 v2 第 7.2 节数据所有权：{@code service_instance/log} 表归 env-service 所有，
 * 其他服务（如 agent-service 的 NoraTools.readServiceLogs）通过本接口消费，
 * 禁止跨 schema 直接访问。</p>
 */
public interface EnvService {

    /**
     * 列出当前环境全部受管服务实例（容器）。
     *
     * @return 服务实例列表（id、名称、镜像、状态、端口映射）
     */
    List<ServiceInstance> listServices();

    /**
     * 读取指定服务最近 N 条日志（用于日志诊断与 Agent 工具调用）。
     *
     * @param service 服务名
     * @param limit   最近日志条数
     * @return 按时间升序返回的日志行
     */
    List<LogLine> tail(String service, int limit);
}
