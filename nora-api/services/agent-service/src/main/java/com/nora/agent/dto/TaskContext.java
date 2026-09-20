package com.nora.agent.dto;

import java.util.List;

/**
 * 结构化任务上下文(M2-01,2026-09-20,按产品改造方案 §6.2)。
 *
 * <p>前端在消息请求里携带 {@code context}(可空):结构化引用列表 + 输出目标 +
 * 来源 + 数据选择。与正文尾部的旧引用行({@code [引用文件] …})并存:
 * **结构化字段优先,旧格式仅作兼容回退**;两者指向同一对象时去重。
 *
 * <p>约束:
 * <ul>
 *   <li>refs 由后端按 kind/id 解析,不信任 label(仅展示快照);</li>
 *   <li>缺失/已删除的对象不自动替换为同名对象,失败项如实上报;</li>
 *   <li>refs 表示"本次参考资料",不构成工具访问授权(权限仍由审批/工具层约束)。</li>
 * </ul>
 */
public record TaskContext(
        /** 契约版本;当前固定 1。 */
        Integer version,
        /** 结构化资源引用(可空/空列表 = 无)。 */
        List<ResourceRef> refs,
        /** 输出目标(可空):workspace 相对路径 / 文件中心文件夹。 */
        OutputTarget output,
        /** 来源(可空):从哪个入口发起(chat/query/log/file/automation)。 */
        Origin origin,
        /** 数据选择(可空):固定集合或已支持的相对时间范围。 */
        DataSelection dataSelection) {

    /** 一条资源引用:kind 决定解析方式;id 为所属服务的稳定 ID。 */
    public record ResourceRef(
            /** file | doc | workspace | datasource | service | mcp | skill */
            String kind,
            /** 所属服务稳定 ID;workspace 使用经过验证的相对路径。 */
            String id,
            /** 展示快照,不作为查询依据。 */
            String label) {
    }

    /** 输出目标。 */
    public record OutputTarget(
            /** workspace | fileFolder */
            String kind,
            String target) {
    }

    /** 来源入口。 */
    public record Origin(
            /** chat | query | log | file | automation */
            String kind,
            String id) {
    }

    /** 数据选择(首版仅支持已实现的来源与相对范围)。 */
    public record DataSelection(
            /** fixed | relativeTime */
            String mode,
            String sourceId,
            /** thisWeek | previousWeek | last7Days */
            String range,
            String timezone) {
    }
}
