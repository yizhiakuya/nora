# -*- coding: utf-8 -*-
"""Step 4 v2: 抽取 ChatContextAssembler（修正单行常量与文本块的边界处理）。"""
import io, re

BASE = r'nora-api/services/agent-service/src/main/java/com/nora/agent/service/'
FACADE = BASE + 'ChatOrchestrationService.java'

src = io.open(FACADE, encoding='utf-8').read()
lines = src.split('\n')

def find_idx(sub):
    for i, l in enumerate(lines):
        if sub in l and not l.strip().startswith(('//', '*')):
            return i
    raise RuntimeError('not found: ' + sub)

def grab_comment(s):
    while s - 1 >= 0:
        p = lines[s-1].strip()
        if p.startswith('/**') or p.startswith('*') or p.startswith('/*') or p == '*/':
            s -= 1
        else:
            break
    return s

def take_single(sub):
    """单行常量/字段:comment + 单行。"""
    s = find_idx(sub)
    assert ';' in lines[s], 'not single-line: %s' % lines[s]
    s2 = grab_comment(s)
    return (s2, s, lines[s2:s+1])

def take_textblock(sub):
    # Java 文本块:从签名到独立行 '""";'
    s = find_idx(sub)
    e = None
    for j in range(s, len(lines)):
        if lines[j].strip() == '""";':
            e = j
            break
    assert e, 'no textblock end: ' + sub
    s2 = grab_comment(s)
    return (s2, e, lines[s2:e+1])

def take_method(sub):
    s = find_idx(sub)
    for j in range(s + 1, len(lines)):
        if lines[j] == '    }':
            e = j
            break
    else:
        raise RuntimeError('no method end: ' + sub)
    s2 = grab_comment(s)
    return (s2, e, lines[s2:e+1])

MOVES = {}
def mv(key, fn, sub):
    MOVES[key] = fn(sub)
    print('MOVE   %-32s %4d-%4d' % (key, MOVES[key][0]+1, MOVES[key][1]+1))

# ---- 方法 ----
mv('buildMessages', take_method, 'private List<WireMessage> buildMessages(String userMessage,')
mv('appendHistoryMessage', take_method, 'private void appendHistoryMessage(List<WireMessage> messages,')
mv('rebuildableToolSteps', take_method, 'private static List<ChatStepDto> rebuildableToolSteps(List<ChatStepDto> steps) {')
mv('toolResultText', take_method, 'private static String toolResultText(ChatStepDto step) {')
mv('wireCostOf', take_method, 'private static int wireCostOf(ChatStoreService.StoredMessage msg) {')
mv('toolsOverheadTokens', take_method, 'int toolsOverheadTokens() {')
mv('requestOverheadTokens', take_method, 'private long requestOverheadTokens() {')
mv('compactForRound', take_method, 'private int compactForRound(List<WireMessage> messages, ContextBudget budget, boolean force,')
mv('recycleOldImages', take_method, 'private int recycleOldImages(List<WireMessage> messages, boolean force) {')
mv('compactToolContent', take_method, 'static String compactToolContent(String content) {')
mv('messageTokens', take_method, 'private static int messageTokens(WireMessage m) {')
mv('logCalibration', take_method, 'private void logCalibration(int promptEstimate, TokenUsage usage, ContextBudget budget) {')
mv('isContextOverflow', take_method, 'static boolean isContextOverflow(String errorMessage) {')
mv('systemPromptWith', take_method, 'private SystemPromptResult systemPromptWith(List<CitationDto> citations) {')
mv('SystemPromptResult', take_method, 'private record SystemPromptResult(String text,')
mv('textOfContent', take_method, 'private static String textOfContent(JsonNode content) {')
mv('lastToolResult', take_method, 'private String lastToolResult(List<WireMessage> messages) {')

# ---- 单行常量/字段 ----
mv('COMPACTION_TAIL_KEEP', take_single, 'private static final int COMPACTION_TAIL_KEEP = 6;')
mv('COMPACTION_KEEP_RECENT_IMAGE_ROUNDS', take_single, 'private static final int COMPACTION_KEEP_RECENT_IMAGE_ROUNDS = 2;')
mv('PER_REQUEST_OVERHEAD_TOKENS', take_single, 'static final int PER_REQUEST_OVERHEAD_TOKENS = 1_800;')
mv('toolsSpecTokensCache', take_single, 'private volatile int toolsSpecTokensCache = -1;')
mv('TOOLS_SPEC_CHARS_PER_TOKEN', take_single, 'private static final int TOOLS_SPEC_CHARS_PER_TOKEN = 7;')
mv('lastRecycledImages', take_single, 'private int lastRecycledImages;')
mv('estimateTokensFreed', take_single, 'private long estimateTokensFreed;')
mv('IMAGE_TOKEN_ESTIMATE', take_single, 'private static final int IMAGE_TOKEN_ESTIMATE = 1_000;')

# ---- 文本块 ----
mv('SYSTEM_PROMPT', take_textblock, 'private static final String SYSTEM_PROMPT = """')

# 重叠检查
allr = sorted([(v[0], v[1]) for v in MOVES.values()])
for a, b in zip(allr, allr[1:]):
    assert a[1] < b[0], 'overlap %s %s' % (a, b)
print('\nno overlaps, %d blocks; total moved lines: %d' % (len(allr), sum(v[1]-v[0]+1 for v in MOVES.values())))

def join(key):
    return '\n'.join(MOVES[key][2])

# ============ ChatContextAssembler.java ============
parts = []
parts.append('package com.nora.agent.service;')
parts.append('')
parts.append('import com.fasterxml.jackson.databind.JsonNode;')
parts.append('import com.fasterxml.jackson.databind.ObjectMapper;')
parts.append('import com.fasterxml.jackson.databind.node.ArrayNode;')
parts.append('import com.fasterxml.jackson.databind.node.ObjectNode;')
parts.append('import com.nora.agent.dto.ChatStepDto;')
parts.append('import com.nora.agent.dto.CitationDto;')
parts.append('import org.slf4j.Logger;')
parts.append('import org.slf4j.LoggerFactory;')
parts.append('')
parts.append('import java.util.ArrayList;')
parts.append('import java.util.List;')
parts.append('')
parts.append('/**')
parts.append(' * 上下文装配器(2026-09-17 从 ChatOrchestrationService 拆出,复杂度审计 Step 4):')
parts.append(' * 系统提示装配(SYSTEM_PROMPT + 工作区/技能注入 + RAG 片段)、历史装配')
parts.append(' * (工具链 wire 重建)、轮内微压缩/旧图回收、token 估算与校准日志。')
parts.append(' * 纯机械平移,行为与拆分前逐行一致。')
parts.append(' */')
parts.append('class ChatContextAssembler {')
parts.append('')
parts.append('    private static final Logger log = LoggerFactory.getLogger(ChatContextAssembler.class);')
parts.append('')
parts.append('    private final ObjectMapper objectMapper;')
parts.append('    private final AgentWorkspaceService agentWorkspaceService;')
parts.append('    private final AgentSkillService agentSkillService;')
parts.append('    /** tools spec 提供者(toolsOverheadTokens 动态估算用)。 */')
parts.append('    private final ChatToolsSpec toolsSpecBuilder;')
parts.append('')
parts.append('    /** 最近一次 compactForRound 回收的旧图张数(可视化 step 用)。 */')
parts.append('    private int lastRecycledImages;')
parts.append('')
parts.append('    /** 最近一次 compactForRound 释放的 token 估算(可视化 step 用)。 */')
parts.append('    private long estimateTokensFreed;')
parts.append('')
parts.append('    ChatContextAssembler(ObjectMapper objectMapper,')
parts.append('                         AgentWorkspaceService agentWorkspaceService,')
parts.append('                         AgentSkillService agentSkillService,')
parts.append('                         ChatToolsSpec toolsSpecBuilder) {')
parts.append('        this.objectMapper = objectMapper;')
parts.append('        this.agentWorkspaceService = agentWorkspaceService;')
parts.append('        this.agentSkillService = agentSkillService;')
parts.append('        this.toolsSpecBuilder = toolsSpecBuilder;')
parts.append('    }')
parts.append('')
parts.append('    int lastRecycledImages() {')
parts.append('        return lastRecycledImages;')
parts.append('    }')
parts.append('')
parts.append('    long estimateTokensFreed() {')
parts.append('        return estimateTokensFreed;')
parts.append('    }')
parts.append('')

order = ['SYSTEM_PROMPT', 'buildMessages', 'appendHistoryMessage', 'rebuildableToolSteps',
         'toolResultText', 'wireCostOf', 'COMPACTION_TAIL_KEEP', 'COMPACTION_KEEP_RECENT_IMAGE_ROUNDS',
         'PER_REQUEST_OVERHEAD_TOKENS', 'toolsSpecTokensCache', 'toolsOverheadTokens',
         'TOOLS_SPEC_CHARS_PER_TOKEN', 'requestOverheadTokens', 'compactForRound', 'recycleOldImages',
         'compactToolContent', 'messageTokens', 'logCalibration', 'isContextOverflow',
         'IMAGE_TOKEN_ESTIMATE', 'systemPromptWith', 'SystemPromptResult', 'textOfContent', 'lastToolResult']
for key in order:
    body = join(key)
    body = body.replace('private List<WireMessage> buildMessages(', 'List<WireMessage> buildMessages(')
    body = body.replace('private void appendHistoryMessage(', 'void appendHistoryMessage(')
    body = body.replace('private static int wireCostOf(', 'static int wireCostOf(')
    body = body.replace('private int compactForRound(', 'int compactForRound(')
    body = body.replace('private int recycleOldImages(', 'int recycleOldImages(')
    body = body.replace('private long requestOverheadTokens() {', 'long requestOverheadTokens() {')
    body = body.replace('private SystemPromptResult systemPromptWith(', 'SystemPromptResult systemPromptWith(')
    body = body.replace('private record SystemPromptResult(', 'record SystemPromptResult(')
    body = body.replace('private static int messageTokens(', 'static int messageTokens(')
    body = body.replace('private void logCalibration(', 'void logCalibration(')
    body = body.replace('private String lastToolResult(', 'String lastToolResult(')
    body = body.replace('private static String textOfContent(', 'static String textOfContent(')
    body = body.replace('objectMapper.writeValueAsString(toolsSpec())', 'objectMapper.writeValueAsString(toolsSpecBuilder.build())')
    body = re.sub(r'(?<![\w.])TokenUsage(?![\w])', 'ChatOrchestrationService.TokenUsage', body)
    parts.append(body)
    parts.append('')

parts.append('}')
io.open(BASE + 'ChatContextAssembler.java', 'w', encoding='utf-8', newline='\n').write('\n'.join(parts))
print('written: ChatContextAssembler.java')

# ============ 重建 facade ============
skip = set()
for v in MOVES.values():
    skip.update(range(v[0], v[1] + 1))
kept = [l for i, l in enumerate(lines) if i not in skip]
facade = '\n'.join(kept)

facade = facade.replace('List<WireMessage> messages = buildMessages(userMessage', 'List<WireMessage> messages = contextAssembler.buildMessages(userMessage')
facade = facade.replace('compactForRound(messages', 'contextAssembler.compactForRound(messages')
facade = facade.replace('long overhead = requestOverheadTokens();', 'long overhead = contextAssembler.requestOverheadTokens();')
facade = facade.replace('long answerOverhead = requestOverheadTokens();', 'long answerOverhead = contextAssembler.requestOverheadTokens();')
facade = facade.replace('ChatOrchestrationService::messageTokens', 'ChatContextAssembler::messageTokens')
facade = facade.replace('logCalibration(lastPromptEstimate', 'contextAssembler.logCalibration(lastPromptEstimate')
facade = facade.replace('isContextOverflow(', 'ChatContextAssembler.isContextOverflow(')
facade = facade.replace('lastToolResult(messages)', 'contextAssembler.lastToolResult(messages)')
facade = facade.replace('SystemPromptResult[] promptOut', 'ChatContextAssembler.SystemPromptResult[] promptOut')

# lastRecycledImages / estimateTokensFreed 字段访问
facade = facade.replace('compactedCount > 0 || lastRecycledImages > 0', 'compactedCount > 0 || contextAssembler.lastRecycledImages() > 0')
facade = facade.replace('if (lastRecycledImages > 0)', 'if (contextAssembler.lastRecycledImages() > 0)')
facade = facade.replace('what.append("已回收 ").append(lastRecycledImages)', 'what.append("已回收 ").append(contextAssembler.lastRecycledImages())')
facade = facade.replace('",释放约 " + estimateTokensFreed + " tokens 预算"', '",释放约 " + contextAssembler.estimateTokensFreed() + " tokens 预算"')

# 新字段 + 构造器
anchor = '    private final ModelResolver modelResolver;'
assert anchor in facade
facade = facade.replace(anchor, anchor + '\n'
    '    /** 上下文装配器(从本类拆出,2026-09-17 复杂度审计 Step 4)。 */\n'
    '    private final ChatContextAssembler contextAssembler;')

anchor2 = '        this.modelResolver = new ModelResolver(llmProperties, modelProviderService);'
assert anchor2 in facade
facade = facade.replace(anchor2, anchor2 + '\n'
    '        this.contextAssembler = new ChatContextAssembler(objectMapper, agentWorkspaceService,\n'
    '                agentSkillService, toolsSpecBuilder);')

io.open(FACADE, 'w', encoding='utf-8', newline='\n').write(facade)
print('facade: %d -> %d lines' % (len(lines), len(kept)))
