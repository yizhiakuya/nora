package com.nora.agent.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * question_required 事件的载荷(ask_user 澄清提问协议):
 * 问题原文 + 可选项 + 一次性 questionToken;用户作答经
 * {@code POST /api/chat/answers/{token}} 结算,答案作为工具结果回填给模型。
 *
 * <p>与审批(ApprovalRequestDto)同款「暂停轮次等待用户」机制,但语义不同:
 * 审批等的是批准/拒绝(布尔),这里等的是自由文本/选项回答(字符串)。
 *
 * @param options        可选项(点选即作答;null=纯自由输入)
 * @param timeoutSeconds 服务端等待上限(超时按未作答结算,轮次收场而非挂死)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QuestionRequestDto(
        String questionToken,
        String stepId,
        String question,
        List<String> options,
        Integer timeoutSeconds
) {
}
