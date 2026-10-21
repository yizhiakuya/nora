package com.nora.agent.service;

import org.junit.jupiter.api.Test;

import com.nora.agent.dto.ApprovalRequestDto;

class PendingApprovalTest {
    @Test
    void overviewUsesUnresolvedTickets() {
        var service = new ApprovalService();
        var registered = service.registerWithFuture("overview", "step", new ApprovalRequestDto(
                null, null, "write", "sample.md", "Write sample", "HIGH", "sample"));
        service.pendingActions();
        service.resolve("another-session", registered.ticket().approvalToken(), true);
        service.pendingActions();
        service.resolve("overview", registered.ticket().approvalToken(), false);
        service.pendingActions();
        service.awaitFuture(registered);
    }
}
