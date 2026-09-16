# -*- coding: utf-8 -*-
"""Step 2 v2: 把工具执行相关块从 ChatOrchestrationService 机械抽取到 ChatToolExecutor + Texts。"""
import io, re

FACADE = r'nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatOrchestrationService.java'
EXEC   = r'nora-api/services/agent-service/src/main/java/com/nora/agent/service/ChatToolExecutor.java'
TEXTS  = r'nora-api/services/agent-service/src/main/java/com/nora/agent/service/Texts.java'

lines = io.open(FACADE, encoding='utf-8').read().split('\n')

def block_range(start):
    depth = 0
    opened = False
    for j in range(start, len(lines)):
        depth += lines[j].count('{') - lines[j].count('}')
        if not opened and depth > 0:
            opened = True
        if opened and depth == 0:
            return (start, j)
    raise RuntimeError('unbalanced at %d' % start)

def with_comment(s):
    j = s
    while j - 1 >= 0:
        prev = lines[j-1].strip()
        if prev.startswith('/**') or prev.startswith('*') or prev.startswith('/*') or prev == '*/':
            j -= 1
        else:
            break
    return j

def find(sig):
    for i, l in enumerate(lines):
        if sig in l:
            return i
    raise RuntimeError('not found: ' + sig)

def take(sig, grab_comment=True):
    s = find(sig)
    _, e = block_range(s)
    s2 = with_comment(s) if grab_comment else s
    return (s2, e, lines[s2:e+1])

MOVES = []
MOVES.append(take('private String importToWorkbenchFile(String args) {'))
MOVES.append(take('private byte[] downloadBounded(String url, long maxBytes) throws Exception {'))
MOVES.append(take('private String importFromUrl(JsonNode a, String path) {'))
MOVES.append(take('private static String inferFilename(String url) {'))
MOVES.append(take('record ToolOutcome(String content, String summary,'))
MOVES.append(take('private Long resolveManagedSourceId(String target) {'))
MOVES.append(take('private static String summarizeCreate(String content) {'))
MOVES.append(take('private ToolOutcome executeTool(String name, String args,'))
MOVES.append(take('private static String summarizeCommand(TerminalService.RunResult r) {'))
MOVES.append(take('static String guardSql(String sql) {'))
MOVES.append(take('static String guardService(String service) {'))
MOVES.append(take('private ToolOutcome bounded(String result, String summary) {'))
MOVES.append(take('private String summarizeRows(String tsv) {'))
cs = find('MAX_SUCCESS_CHARS = 30_000')
ce = find('FAILURE_TAIL_CHARS = 3_000')
MOVES.append((with_comment(cs), ce, lines[with_comment(cs):ce+1]))

DELETES = []
DELETES.append(take('private static long parseNumericId(String raw) {'))
DELETES.append(take('private static String abbreviateStatic(String text, int max) {'))
DELETES.append(take('private static String abbreviate(String text, int max) {', grab_comment=False))
DELETES.append(take('private static String firstNonNull(String a, String b) {', grab_comment=False))

for (s, e, txt) in MOVES:
    print('MOVE   %4d-%4d  %s' % (s+1, e+1, txt[0].strip()[:70]))
for (s, e, txt) in DELETES:
    print('DELETE %4d-%4d  %s' % (s+1, e+1, txt[0].strip()[:70]))

all_ranges = sorted([(s, e) for (s, e, _) in MOVES] + [(s, e) for (s, e, _) in DELETES])
for a, b in zip(all_ranges, all_ranges[1:]):
    assert a[1] < b[0], 'overlap: %s %s' % (a, b)

skip = set()
for (s, e) in all_ranges:
    skip.update(range(s, e+1))
facade_lines = [l for i, l in enumerate(lines) if i not in skip]

def dump(name):
    for (s, e, txt) in MOVES:
        if name in '\n'.join(txt):
            return '\n'.join(txt)
    raise RuntimeError('no block: ' + name)

consts    = dump('MAX_SUCCESS_CHARS = 30_000')
exec_body = dump('executeTool(String name')
bounded   = dump('bounded(String result')
sumRows   = dump('summarizeRows(String tsv)')
sumCmd    = dump('summarizeCommand(TerminalService.RunResult')
gSql      = dump('guardSql(String sql)')
gSvc      = dump('guardService(String service)')
resSrc    = dump('resolveManagedSourceId(String target)')
sumCreate = dump('summarizeCreate(String content)')
impWork   = dump('importToWorkbenchFile(String args)')
dl        = dump('downloadBounded(String url')
impUrl    = dump('importFromUrl(JsonNode a')
inf       = dump('inferFilename(String url)')
outcome   = dump('record ToolOutcome(String content')

exec_body = exec_body.replace(
    'private ToolOutcome executeTool(String name, String args, ParsedArgs parsed,',
    'ToolOutcome executeTool(String name, String args, ChatOrchestrationService.ParsedArgs parsed,')
bounded = bounded.replace('private ToolOutcome bounded(', 'ToolOutcome bounded(')

# ---- facade 后处理 ----
facade = '\n'.join(facade_lines)
facade = facade.replace('MAX_FAILURE_CHARS', 'ChatToolExecutor.MAX_FAILURE_CHARS')
facade = facade.replace('ToolOutcome outcome = executeTool(',
                        'ChatToolExecutor.ToolOutcome outcome = toolExecutor.executeTool(')
facade = re.sub(r'(?<![\w.])abbreviateStatic\(', 'Texts.abbreviate(', facade)
facade = re.sub(r'(?<![\w.])abbreviate\(', 'Texts.abbreviate(', facade)
facade = re.sub(r'(?<![\w.])firstNonNull\(', 'Texts.firstNonNull(', facade)

# 字段
anchor = '    private final ChatToolsSpec toolsSpecBuilder;'
assert anchor in facade
facade = facade.replace(anchor, anchor + '\n'
    '    /** 工具执行器(从本类拆出,2026-09-17 复杂度审计 Step 2)。 */\n'
    '    private final ChatToolExecutor toolExecutor;')

# 构造器初始化
anchor2 = '        this.proxyProperties = proxyProperties != null ? proxyProperties : com.nora.common.http.ProxyProperties.disabled();'
assert anchor2 in facade
facade = facade.replace(anchor2, anchor2 + '\n'
    '        this.toolExecutor = new ChatToolExecutor(objectMapper, sqlToolClient, serviceLogClient, writeSqlClient,\n'
    '                containerControlClient, dataSourceManageClient, serviceManageClient, fileToolClient,\n'
    '                mcpServerService, terminalService, agentWorkspaceService, agentSkillService);')

io.open(FACADE, 'w', encoding='utf-8', newline='\n').write(facade)
print('\nfacade: %d -> %d lines' % (len(lines), len(facade_lines)))

# ---- ChatToolExecutor.java ----
parts = []
parts.append('package com.nora.agent.service;')
parts.append('')
parts.append('import com.fasterxml.jackson.databind.JsonNode;')
parts.append('import com.fasterxml.jackson.databind.ObjectMapper;')
parts.append('')
parts.append('import java.time.Duration;')
parts.append('')
parts.append('/**')
parts.append(' * 工具执行器(从 ChatOrchestrationService 拆出,2026-09-17 复杂度审计 Step 2):')
parts.append(' * executeTool 的 11 路分发 + 结果裁剪(bounded)+ 守卫(guardSql/guardService)')
parts.append(' * + URL 导入(importFromUrl/importToWorkbenchFile/downloadBounded)。')
parts.append(' * 纯机械平移,行为与拆分前逐行一致。')
parts.append(' */')
parts.append('class ChatToolExecutor {')
parts.append('')
parts.append('    private final ObjectMapper objectMapper;')
parts.append('    private final SqlToolClient sqlToolClient;')
parts.append('    private final ServiceLogClient serviceLogClient;')
parts.append('    private final WriteSqlClient writeSqlClient;')
parts.append('    private final ContainerControlClient containerControlClient;')
parts.append('    private final DataSourceManageClient dataSourceManageClient;')
parts.append('    private final ServiceManageClient serviceManageClient;')
parts.append('    private final FileToolClient fileToolClient;')
parts.append('    private final McpServerService mcpServerService;')
parts.append('    private final TerminalService terminalService;')
parts.append('    private final AgentWorkspaceService agentWorkspaceService;')
parts.append('    private final AgentSkillService agentSkillService;')
parts.append('')
parts.append('    ChatToolExecutor(ObjectMapper objectMapper,')
parts.append('                     SqlToolClient sqlToolClient,')
parts.append('                     ServiceLogClient serviceLogClient,')
parts.append('                     WriteSqlClient writeSqlClient,')
parts.append('                     ContainerControlClient containerControlClient,')
parts.append('                     DataSourceManageClient dataSourceManageClient,')
parts.append('                     ServiceManageClient serviceManageClient,')
parts.append('                     FileToolClient fileToolClient,')
parts.append('                     McpServerService mcpServerService,')
parts.append('                     TerminalService terminalService,')
parts.append('                     AgentWorkspaceService agentWorkspaceService,')
parts.append('                     AgentSkillService agentSkillService) {')
parts.append('        this.objectMapper = objectMapper;')
parts.append('        this.sqlToolClient = sqlToolClient;')
parts.append('        this.serviceLogClient = serviceLogClient;')
parts.append('        this.writeSqlClient = writeSqlClient;')
parts.append('        this.containerControlClient = containerControlClient;')
parts.append('        this.dataSourceManageClient = dataSourceManageClient;')
parts.append('        this.serviceManageClient = serviceManageClient;')
parts.append('        this.fileToolClient = fileToolClient;')
parts.append('        this.mcpServerService = mcpServerService;')
parts.append('        this.terminalService = terminalService;')
parts.append('        this.agentWorkspaceService = agentWorkspaceService;')
parts.append('        this.agentSkillService = agentSkillService;')
parts.append('    }')
parts.append('')
parts.append(consts)
parts.append('')
parts.append(exec_body)
parts.append('')
parts.append(sumCmd)
parts.append('')
parts.append(gSql)
parts.append('')
parts.append(gSvc)
parts.append('')
parts.append(bounded)
parts.append('')
parts.append(sumRows)
parts.append('')
parts.append(resSrc)
parts.append('')
parts.append(sumCreate)
parts.append('')
parts.append(impWork)
parts.append('')
parts.append(dl)
parts.append('')
parts.append(impUrl)
parts.append('')
parts.append(inf)
parts.append('')
parts.append(outcome)
parts.append('}')

exec_src = '\n'.join(parts)
exec_src = re.sub(r'(?<![\w.])abbreviateStatic\(', 'Texts.abbreviate(', exec_src)
exec_src = re.sub(r'(?<![\w.])abbreviate\(', 'Texts.abbreviate(', exec_src)
exec_src = re.sub(r'(?<![\w.])firstNonNull\(', 'Texts.firstNonNull(', exec_src)
io.open(EXEC, 'w', encoding='utf-8', newline='\n').write(exec_src)
print('written:', EXEC, len(exec_src.split('\n')), 'lines')

# ---- Texts.java ----
texts_src = '''package com.nora.agent.service;

/**
 * 跨组件共享的小文本工具(2026-09-17 拆分自 ChatOrchestrationService)。
 */
final class Texts {

    private Texts() {
    }

    /** 截断到 max 字符,超长加省略号;null 视作空串。 */
    static String abbreviate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    static String firstNonNull(String a, String b) {
        return a != null ? a : b;
    }
}
'''
io.open(TEXTS, 'w', encoding='utf-8', newline='\n').write(texts_src)
print('written:', TEXTS)
