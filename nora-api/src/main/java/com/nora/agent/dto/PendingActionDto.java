package com.nora.agent.dto;

/** 概览仅导航到原会话；实际回答与审批仍校验原票据。 */
public record PendingActionDto(String kind, String sessionId, String stepId, String summary) {}
