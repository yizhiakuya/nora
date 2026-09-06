package com.nora.agent.dto;

/**
 * approval_required / approval 事件的载荷(spec 高风险审批协议):
 * 操作类型、目标、参数摘要、风险说明 + 一次性 approvalToken。
 */
public record ApprovalRequestDto(
        String approvalToken,
        String stepId,
        String actionType,
        String target,
        String summary,
        String risk
) {
}
