package com.nora.agent.service;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class PtcProgramTest {
    @Test
    void discoversActualContractsAndEstimatesModeBudget() throws Exception {
        var json = new ObjectMapper();
        var specs = new ChatToolsSpec(json, ToolSmokeFixtures.annotated(json), null, null);
        var runtime = new PtcRuntime(json, "");
        var programs = new PtcProgramService(json, runtime, specs, null, null, null);
        specs.forMessages(List.of(), "ptc");
        programs.loadTools(List.of());
        programs.loadTools(List.of("manage_workspace"));
        programs.available();
        ExecutionMode.parse(null);
        ExecutionMode.parse("ptc");
    }
}
