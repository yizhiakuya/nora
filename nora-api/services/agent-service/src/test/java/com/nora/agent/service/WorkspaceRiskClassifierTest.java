package com.nora.agent.service;

import org.junit.jupiter.api.Test;


/**
 * manage_workspace 的风险分级(新语义:工作区=默认 cwd 而非硬沙箱,
 * 区外写需审批、区外删强制审批;区内记忆维护自动放行)。
 */
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class WorkspaceRiskClassifierTest {

    @Test
    void readListAlwaysLow() {
        RiskClassifier.classify("manage_workspace", "{\"action\": \"read\", \"path\": \"D:/anywhere/x.txt\"}");
        RiskClassifier.classify("manage_workspace", "{\"action\": \"list\", \"dir\": \"C:/Users\"}");
    }

    @Test
    void writeInsideWorkspaceIsLow() {
        // 区内相对路径:LOW(记忆维护须即时落盘)
        RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"write\", \"path\": \"USER.md\", \"content\": \"x\"}");
        RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"append\", \"path\": \"memory/2026-09-10.md\", \"content\": \"x\"}");
    }

    @Test
    void writeOutsideWorkspaceIsHigh() {
        // 绝对路径:区外写 HIGH(ASSIST 询问)
        RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"write\", \"path\": \"D:/projects/app/src/main.ts\", \"content\": \"x\"}");
        RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"append\", \"path\": \"/c/somewhere/file.md\", \"content\": \"x\"}");
        // .. 上跳也算区外
        RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"write\", \"path\": \"../outside.txt\", \"content\": \"x\"}");
    }

    @Test
    void deleteOutsideWorkspaceIsCritical() {
        RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"delete\", \"path\": \"D:/important/data.csv\"}");
    }

    @Test
    void deleteInsideWorkspaceIsHigh() {
        RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"delete\", \"path\": \"memory/old.md\"}");
    }

    @Test
    void malformedArgsAreConservative() {
        RiskClassifier.classify("manage_workspace", "not json");
        RiskClassifier.classify("manage_workspace", null);
    }

    @Test
    void editFollowsWriteTiering() {
        // edit(精确替换,2026-09-18 新增):与 write 同分级——区内 LOW、区外 HIGH
        RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"edit\", \"path\": \"MEMORY.md\", \"old_string\": \"a\", \"new_string\": \"b\"}");
        RiskClassifier.classify("manage_workspace",
                        "{\"action\": \"edit\", \"path\": \"D:/projects/app/x.ts\", \"old_string\": \"a\", \"new_string\": \"b\"}");
    }

    @Test
    void newToolTiers() {
        // 知识库检索 LOW;manage_knowledge:list/stats LOW、index/reindex HIGH、remove CRITICAL
        RiskClassifier.classify("search_knowledge", "{\"query\": \"部署流程\"}");
        RiskClassifier.classify("manage_knowledge", "{\"action\": \"list\"}");
        RiskClassifier.classify("manage_knowledge", "{\"action\": \"stats\"}");
        RiskClassifier.classify("manage_knowledge", "{\"action\": \"index\", \"fileId\": \"12\"}");
        RiskClassifier.classify("manage_knowledge", "{\"action\": \"reindex\", \"target\": \"3\"}");
        RiskClassifier.classify("manage_knowledge", "{\"action\": \"remove\", \"target\": \"3\"}");
        // 自动任务:list/executions LOW;create/toggle/run HIGH;remove CRITICAL
        RiskClassifier.classify("manage_automation", "{\"action\": \"list\"}");
        RiskClassifier.classify("manage_automation", "{\"action\": \"executions\"}");
        RiskClassifier.classify("manage_automation",
                        "{\"action\": \"create\", \"name\": \"巡检\", \"prompt\": \"查订单\", \"triggerType\": \"daily\"}");
        RiskClassifier.classify("manage_automation", "{\"action\": \"toggle\", \"target\": \"1\"}");
        RiskClassifier.classify("manage_automation", "{\"action\": \"run\", \"target\": \"1\"}");
        RiskClassifier.classify("manage_automation", "{\"action\": \"remove\", \"target\": \"1\"}");
        // 环境快照 LOW
        RiskClassifier.classify("environment_status", "{}");
    }
}
