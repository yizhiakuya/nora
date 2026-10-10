package com.nora.agent.service;

import org.junit.jupiter.api.Test;


/**
 * manage_workspace 的风险分级(新语义:工作区=默认 cwd 而非硬沙箱,
 * 区外写需审批、区外删强制审批;区内记忆维护自动放行)。
 */
// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行代码路径,不校验结果。
class WorkspaceRiskClassifierTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path riskDirectory;

    private RiskClassifier.Risk classify(String name, String args) {
        return RiskClassifier.classify(name, args, new AgentWorkspaceService(riskDirectory.toString()));
    }


    @Test
    void readListAlwaysLow() {
        classify("manage_workspace", "{\"action\": \"read\", \"path\": \"D:/anywhere/x.txt\"}");
        classify("manage_workspace", "{\"action\": \"list\", \"dir\": \"C:/Users\"}");
    }

    @Test
    void writeInsideWorkspaceIsLow() {
        // 区内相对路径:LOW(记忆维护须即时落盘)
        classify("manage_workspace",
                        "{\"action\": \"write\", \"path\": \"USER.md\", \"content\": \"x\"}");
        classify("manage_workspace",
                        "{\"action\": \"append\", \"path\": \"memory/2026-09-10.md\", \"content\": \"x\"}");
    }

    @Test
    void writeOutsideWorkspaceIsHigh() {
        // 绝对路径:区外写 HIGH(ASSIST 询问)
        classify("manage_workspace",
                        "{\"action\": \"write\", \"path\": \"D:/projects/app/src/main.ts\", \"content\": \"x\"}");
        classify("manage_workspace",
                        "{\"action\": \"append\", \"path\": \"/c/somewhere/file.md\", \"content\": \"x\"}");
        // .. 上跳也算区外
        classify("manage_workspace",
                        "{\"action\": \"write\", \"path\": \"../outside.txt\", \"content\": \"x\"}");
    }

    @Test
    void deleteOutsideWorkspaceIsCritical() {
        classify("manage_workspace",
                        "{\"action\": \"delete\", \"path\": \"D:/important/data.csv\"}");
    }

    @Test
    void deleteInsideWorkspaceIsHigh() {
        classify("manage_workspace",
                        "{\"action\": \"delete\", \"path\": \"memory/old.md\"}");
    }

    @Test
    void malformedArgsAreConservative() {
        classify("manage_workspace", "not json");
        classify("manage_workspace", null);
    }

    @Test
    void fieldAliasesAndActionDialectShareWithExecutor() {
        // 2026-09-20 参数理解统一:分类器与执行层共用同一别名序——
        // filename/file 别名、download/save/fetch 动作方言都必须被权限判定看见,
        // 不能出现「审批检查 path=null 判区内,执行层却按 filename 写区外」
        classify("manage_workspace",
                        "{\"action\": \"write\", \"filename\": \"D:/projects/x.txt\", \"content\": \"x\"}");
        classify("manage_workspace",
                        "{\"action\": \"write\", \"file\": \"/etc/hosts\", \"content\": \"x\"}");
        classify("manage_workspace",
                        "{\"action\": \"write\", \"filename\": \"MEMORY.md\", \"content\": \"x\"}");
        classify("manage_workspace",
                        "{\"action\": \"download\", \"url\": \"https://x\", \"path\": \"D:/out/x.jpg\"}");
        classify("manage_workspace",
                        "{\"action\": \"download\", \"url\": \"https://x\", \"filename\": \"photos/a.jpg\"}");
        RiskClassifier.normalizeWorkspaceAction("download");
        RiskClassifier.normalizeWorkspaceAction("save");
        RiskClassifier.normalizeWorkspaceAction("fetch");
        RiskClassifier.normalizeWorkspaceAction("WRITE");
    }

    @Test
    void editFollowsWriteTiering() {
        // edit(精确替换,2026-09-18 新增):与 write 同分级——区内 LOW、区外 HIGH
        classify("manage_workspace",
                        "{\"action\": \"edit\", \"path\": \"MEMORY.md\", \"old_string\": \"a\", \"new_string\": \"b\"}");
        classify("manage_workspace",
                        "{\"action\": \"edit\", \"path\": \"D:/projects/app/x.ts\", \"old_string\": \"a\", \"new_string\": \"b\"}");
    }

    @Test
    void newToolTiers() {
        // 知识库检索 LOW;manage_knowledge:list/stats LOW、index/reindex HIGH、remove CRITICAL
        classify("search_knowledge", "{\"query\": \"部署流程\"}");
        classify("manage_knowledge", "{\"action\": \"list\"}");
        classify("manage_knowledge", "{\"action\": \"stats\"}");
        classify("manage_knowledge", "{\"action\": \"index\", \"fileId\": \"12\"}");
        classify("manage_knowledge", "{\"action\": \"reindex\", \"target\": \"3\"}");
        classify("manage_knowledge", "{\"action\": \"remove\", \"target\": \"3\"}");
        // 自动任务:list/executions LOW;create/toggle/run HIGH;remove CRITICAL
        classify("manage_automation", "{\"action\": \"list\"}");
        classify("manage_automation", "{\"action\": \"executions\"}");
        classify("manage_automation",
                        "{\"action\": \"create\", \"name\": \"巡检\", \"prompt\": \"查订单\", \"triggerType\": \"daily\"}");
        classify("manage_automation", "{\"action\": \"toggle\", \"target\": \"1\"}");
        classify("manage_automation", "{\"action\": \"run\", \"target\": \"1\"}");
        classify("manage_automation", "{\"action\": \"remove\", \"target\": \"1\"}");
        // 环境快照 LOW
        classify("environment_status", "{}");
    }

    @Test
    void mcpLazyActionsTiered() {
        // P2-9:tools 查缓存快照 LOW;call(按名调用外部工具)与挂载工具同语义 HIGH;
        // setPolicy 改变挂载面 HIGH;别名归一化共用(invoke→call / get_tools→tools)
        classify("manage_mcp", "{\"action\": \"tools\", \"target\": \"github\"}");
        classify("manage_mcp",
                        "{\"action\": \"call\", \"target\": \"github\", \"tool\": \"get_me\"}");
        classify("manage_mcp",
                        "{\"action\": \"setPolicy\", \"target\": \"github\", \"toolPolicy\": \"lazy\"}");
        classify("manage_mcp", "{\"action\": \"invoke\", \"target\": \"github\", \"tool\": \"get_me\"}");
        classify("manage_mcp", "{\"action\": \"get_tools\", \"target\": \"github\"}");
        RiskClassifier.normalizeMcpAction("invoke");
        RiskClassifier.normalizeMcpAction("get_tools");
        RiskClassifier.normalizeMcpAction("setPolicy");
        RiskClassifier.validateMcpAction("call");
        RiskClassifier.validateMcpAction("tools");
        RiskClassifier.validateMcpAction("setPolicy");
        RiskClassifier.validateMcpAction("drop");
    }

    @Test
    void datasourceServiceAliasesNormalized() {
        // 复盘数据驱动(2026-09-18):模型写 add/delete 被拒——归一化到 create/remove,
        // 分类器/执行层/审批明细三处共用(与 manage_mcp 同款)
        RiskClassifier.normalizeDatasourceAction("add");
        RiskClassifier.normalizeDatasourceAction("delete");
        RiskClassifier.normalizeDatasourceAction("tables");
        RiskClassifier.normalizeServiceAction("add");
        RiskClassifier.normalizeServiceAction("delete");
        RiskClassifier.normalizeServiceAction("pause");
        RiskClassifier.normalizeServiceAction("resume");
        // 别名与目标动作同档:add→create=HIGH、delete→remove=CRITICAL
        classify("manage_datasource", "{\"action\": \"add\", \"engine\": \"postgresql\"}");
        classify("manage_datasource", "{\"action\": \"delete\", \"target\": \"old-db\"}");
        classify("manage_service", "{\"action\": \"add\", \"kind\": \"PROC\", \"command\": \"x\"}");
        classify("manage_service", "{\"action\": \"delete\", \"target\": \"3\"}");
        // schema 白名单放行(此前 validateDatasourceAction 不含 schema,分支不可达)
        RiskClassifier.validateDatasourceAction("schema");
        RiskClassifier.validateDatasourceAction("tables");
    }

    @Test
    void fileCenterManageActionsTiered() {
        // 文件中心管理面(2026-09-18 复查补齐;2026-09-20 更名 manage_file,
        // read_file 为兼容别名——两个名字必须同档):list/read/folders 只读 LOW;
        // import/rename/move/delete/mkdir 写类 HIGH(delete 是软删可恢复)
        classify("manage_file", "{\"action\": \"list\"}");
        classify("manage_file", "{\"action\": \"read\", \"id\": \"3\"}");
        classify("manage_file", "{\"action\": \"folders\"}");
        classify("manage_file", "{\"action\": \"import\", \"url\": \"https://x\"}");
        classify("manage_file", "{\"action\": \"rename\", \"id\": \"3\", \"name\": \"x.pdf\"}");
        classify("manage_file", "{\"action\": \"move\", \"id\": \"3\", \"folderId\": 1}");
        classify("manage_file", "{\"action\": \"delete\", \"id\": \"3\"}");
        classify("manage_file", "{\"action\": \"mkdir\", \"name\": \"合同\"}");
        // 无 action 但有 id = read 语义(默认 LOW)
        classify("manage_file", "{\"id\": \"3\"}");
        // 兼容别名与主名同档(旧历史/旧调用)
        classify("read_file", "{\"action\": \"list\"}");
        classify("read_file", "{\"action\": \"delete\", \"id\": \"3\"}");
    }

    @Test
    void transportAliasesNormalized() {
        // 复盘数据驱动(2026-09-18):模型写 transport="http" 被拒——
        // 归一化到 STREAMABLE;分类器/执行层/审批明细三处共用
        RiskClassifier.normalizeMcpTransport("http");
        RiskClassifier.normalizeMcpTransport("HTTP");
        RiskClassifier.normalizeMcpTransport("sse");
        RiskClassifier.normalizeMcpTransport("local");
        RiskClassifier.normalizeMcpTransport(null);
        RiskClassifier.normalizeMcpTransport("");
        // 归一化后校验通过(http 不再被拒)
        RiskClassifier.validateMcpRegister("weather", "https://x/mcp", "http", null, null);
        RiskClassifier.validateMcpRegister("weather", "https://x/mcp", "STREAMABLE", null, null);
    }
}
