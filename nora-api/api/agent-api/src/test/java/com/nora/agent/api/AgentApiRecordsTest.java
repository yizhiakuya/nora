package com.nora.agent.api;

import org.junit.jupiter.api.Test;

import java.util.List;


// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class AgentApiRecordsTest {

    @Test
    void citationRecordExposesAllFields() {
        Citation citation = new Citation(
                "arch-notes.md", "docs://kb/arch-notes.md", 3, 0.87, "gateway routes /api/chat/**");

        citation.docName();
        citation.source();
        citation.chunkIndex();
        citation.score();
        citation.snippet();
        new Citation("arch-notes.md", "docs://kb/arch-notes.md",
                3, 0.87, "gateway routes /api/chat/**");
        new Citation("other.md", "docs://kb/arch-notes.md",
                3, 0.87, "gateway routes /api/chat/**");
        new Citation(null, null, 0, 0.0, null);
    }

    @Test
    void chatStepEventRecordExposesAllFields() {
        ChatStepEvent event = new ChatStepEvent(
                "step-1", ChatStepType.THINK, "Reasoning", "plan: search docs first",
                120L, ChatStepStatus.COMPLETED);

        event.id();
        event.type();
        event.title();
        event.detail();
        event.durationMs();
        event.status();
        new ChatStepEvent(null, null, null, null, null, null);
    }

    @Test
    void chatStepEnumsRoundTrip() {
        ChatStepType.values();
        ChatStepStatus.values();

        for (ChatStepType type : ChatStepType.values()) {
            ChatStepType.valueOf(type.name());
        }
        for (ChatStepStatus status : ChatStepStatus.values()) {
            ChatStepStatus.valueOf(status.name());
        }

        ChatStepType.valueOf("TOOL");
        ChatStepStatus.valueOf("FAILED");
    }

    @Test
    void modelInfoRecordExposesAllFields() {
        ModelInfo model = new ModelInfo("openai", "gpt-4o-mini");

        model.protocol();
        model.modelName();
        List.of(model);
        List.of(new ModelInfo("openai", "gpt-4o-mini"));
    }
}
