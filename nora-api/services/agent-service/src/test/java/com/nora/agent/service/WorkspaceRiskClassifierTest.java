package com.nora.agent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * manage_workspace 的风险分级(新语义:工作区=默认 cwd 而非硬沙箱,
 * 区外写需审批、区外删强制审批;区内记忆维护自动放行)。
 */
class WorkspaceRiskClassifierTest {

    @Test
    void readListAlwaysLow() {
        assertEquals(RiskClassifier.Risk.LOW,
                RiskClassifier.classify("manage_workspace", "{\"action\": \"read\", \"path\": \"D:/anywhere/x.txt\"}"));
        assertEquals(RiskClassifier.Risk.LOW,
                RiskClassifier.classify("manage_workspace", "{\"action\": \"list\", \"dir\": \"C:/Users\"}"));
    }

    @Test
    void writeInsideWorkspaceIsLow() {
        // 区内相对路径:LOW(记忆维护须即时落盘)
        assertEquals(RiskClassifier.Risk.LOW,
                RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"write\", \"path\": \"USER.md\", \"content\": \"x\"}"));
        assertEquals(RiskClassifier.Risk.LOW,
                RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"append\", \"path\": \"memory/2026-09-10.md\", \"content\": \"x\"}"));
    }

    @Test
    void writeOutsideWorkspaceIsHigh() {
        // 绝对路径:区外写 HIGH(ASSIST 询问)
        assertEquals(RiskClassifier.Risk.HIGH,
                RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"write\", \"path\": \"D:/projects/app/src/main.ts\", \"content\": \"x\"}"));
        assertEquals(RiskClassifier.Risk.HIGH,
                RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"append\", \"path\": \"/c/somewhere/file.md\", \"content\": \"x\"}"));
        // .. 上跳也算区外
        assertEquals(RiskClassifier.Risk.HIGH,
                RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"write\", \"path\": \"../outside.txt\", \"content\": \"x\"}"));
    }

    @Test
    void deleteOutsideWorkspaceIsCritical() {
        assertEquals(RiskClassifier.Risk.CRITICAL,
                RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"delete\", \"path\": \"D:/important/data.csv\"}"));
    }

    @Test
    void deleteInsideWorkspaceIsHigh() {
        assertEquals(RiskClassifier.Risk.HIGH,
                RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"delete\", \"path\": \"memory/old.md\"}"));
    }

    @Test
    void malformedArgsAreConservative() {
        assertEquals(RiskClassifier.Risk.HIGH,
                RiskClassifier.classify("manage_workspace", "not json"));
        assertEquals(RiskClassifier.Risk.HIGH,
                RiskClassifier.classify("manage_workspace", null));
    }
}
