package com.nora.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * approval_required / approval 事件的载荷(spec 高风险审批协议):
 * 操作类型、目标、参数摘要、风险说明 + 一次性 approvalToken。
 *
 * @param detail 各工具的参数明细(逐行 key=value;密码等敏感参数在构造处
 *               即被排除,不会进入此字段),null 表示无附加明细
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApprovalRequestDto(
        String approvalToken,
        String stepId,
        String actionType,
        String target,
        String summary,
        String risk,
        String detail
) {
}
