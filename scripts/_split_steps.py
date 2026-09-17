# -*- coding: utf-8 -*-
"""拆分 ChatOrchestrationService 收尾:工具步骤发射链 → ToolStepEmitter。"""
import io, re

BASE = r'nora-api/services/agent-service/src/main/java/com/nora/agent/service/'
SVC = BASE + 'ChatOrchestrationService.java'
NEW = BASE + 'ToolStepEmitter.java'
EXEC = BASE + 'ChatToolExecutor.java'

src = io.open(SVC, encoding='utf-8').read()
lines = src.split('\n')

def find_idx(sub, start=0, occ=0):
    found = 0
    for i in range(start, len(lines)):
        if sub in lines[i]:
            if found == occ:
                return i
            found += 1
    raise RuntimeError('not found: %s (occ %d)' % (sub, occ))

def block_end(start):
    depth = 0
    opened = False
    for j in range(start, len(lines)):
        depth += lines[j].count('{') - lines[j].count('}')
        if not opened and depth > 0:
            opened = True
        if opened and depth == 0:
            return j
    raise RuntimeError('unbalanced at %d' % (start + 1))

def up_comment(s):
    while s - 1 >= 0:
        p = lines[s-1].strip()
        if p.startswith('/**') or p.startswith('*') or p.startswith('/*') or p == '*/':
            s -= 1
        else:
            break
    return s

def method_block(sub, occ=0):
    s = find_idx(sub, occ=occ)
    s2 = up_comment(s)
    e = block_end(s)
    return (s2, e)

BLOCKS = {}
def add(key, s, e):
    BLOCKS[key] = (s, e, lines[s:e+1])
    print('%-24s %4d-%4d' % (key, s+1, e+1))

# A) LOOP 常量
s = find_idx('private static final int LOOP_WARN_THRESHOLD')
s2 = up_comment(s)
e = find_idx('private static final int LOOP_BLOCK_THRESHOLD')
add('loopConsts', s2, e)

# B) emitToolStep 链:注释 → 第三个重载方法结束
s = find_idx('private void emitToolStep(')
s2 = up_comment(s)
e_full = method_block('private void emitToolStep(', occ=2)[1]  # 第三个(完整版)
# 但 method_block(occ=2) 的 up_comment 会吞到上一个方法尾? 不会,只吞注释。
add('emitToolStepChain', s2, e_full)

# C) finishToolStep
s, e = method_block('private void finishToolStep(')
add('finishToolStep', s, e)

# D) backfillToolMessage 链(两个重载)
s = find_idx('private void backfillToolMessage(')
s2 = up_comment(s)
e = method_block('private void backfillToolMessage(', occ=1)[1]
add('backfillChain', s2, e)

# E) ParsedArgs + parseArgs + defaultTitle + normalizeArgs
s = find_idx('record ParsedArgs(ChatStepDto.StepInput input')
s2 = up_comment(s)
e = method_block('private String normalizeArgs(')[1]
add('parsedArgsChain', s2, e)

# F) buildApprovalRequest + parseArgsSafe
s = find_idx('private ApprovalRequestDto buildApprovalRequest(')
s2 = up_comment(s)
e = method_block('private JsonNode parseArgsSafe(')[1]
add('approvalChain', s2, e)

# G) countLines
s, e = method_block('private static Integer countLines(')
add('countLines', s, e)

# H) MAX_REPLAY_ARGS + withRawArgs + scrubArgsForLog
s = find_idx('private static final int MAX_REPLAY_ARGS_CHARS')
s2 = up_comment(s)
e = method_block('private String scrubArgsForLog(')[1]
add('scrubChain', s2, e)

# 重叠检查
allr = sorted([(v[0], v[1]) for v in BLOCKS.values()])
for a, b in zip(allr, allr[1:]):
    assert a[1] < b[0], 'overlap %s %s' % (a, b)
print('no overlaps, %d blocks' % len(allr))

def join(key):
    return '\n'.join(BLOCKS[key][2])

# ============ ToolStepEmitter.java ============
out = []
out.append('package com.nora.agent.service;')
out.append('')
out.append('import com.fasterxml.jackson.databind.JsonNode;')
out.append('import com.fasterxml.jackson.databind.ObjectMapper;')
out.append('import com.fasterxml.jackson.databind.node.ArrayNode;')
out.append('import com.fasterxml.jackson.databind.node.ObjectNode;')
out.append('import com.nora.agent.dto.ApprovalRequestDto;')
out.append('import com.nora.agent.dto.ChatStepDto;')
out.append('import org.slf4j.Logger;')
out.append('import org.slf4j.LoggerFactory;')
out.append('')
out.append('import java.util.List;')
out.append('import java.util.Map;')
out.append('')
out.append('/**')
out.append(' * 工具步骤发射器(2026-09-17 从 ChatOrchestrationService 拆出,拆分方案收尾):')
out.append(' * emitToolStep 的完整生命周期(参数解析 → 循环熔断 → 无人值守闸 → 审批门 →')
out.append(' * 执行 → 终态步骤 + 结果回填)+ 审批请求构建 + args 脱敏/重放。')
out.append(' * 纯机械平移,行为与拆分前逐行一致。')
out.append(' */')
out.append('class ToolStepEmitter {')
out.append('')
out.append('    private static final Logger log = LoggerFactory.getLogger(ToolStepEmitter.class);')
out.append('')
out.append(join('loopConsts'))
out.append('')
out.append('    private final ObjectMapper objectMapper;')
out.append('    /** 审批门(可空=测试构造器不接,无审批直接执行)。 */')
out.append('    private final ApprovalService approvalService;')
out.append('    private final ChatToolExecutor toolExecutor;')
out.append('    private final ModelCapabilityRegistry capabilityRegistry;')
out.append('')
out.append('    ToolStepEmitter(ObjectMapper objectMapper, ApprovalService approvalService,')
out.append('                    ChatToolExecutor toolExecutor, ModelCapabilityRegistry capabilityRegistry) {')
out.append('        this.objectMapper = objectMapper;')
out.append('        this.approvalService = approvalService;')
out.append('        this.toolExecutor = toolExecutor;')
out.append('        this.capabilityRegistry = capabilityRegistry;')
out.append('    }')
out.append('')
out.append(join('emitToolStepChain'))
out.append('')
out.append(join('finishToolStep'))
out.append('')
out.append(join('backfillChain'))
out.append('')
out.append(join('parsedArgsChain'))
out.append('')
out.append(join('approvalChain'))
out.append('')
out.append(join('countLines'))
out.append('')
out.append(join('scrubChain'))
out.append('}')
out_text = '\n'.join(out)
# 可见性:emitToolStep 供 facade 调用(保留原可见性 private → 包可见由去掉 private 处理)
out_text = out_text.replace('private void emitToolStep(', 'void emitToolStep(')
io.open(NEW, 'w', encoding='utf-8', newline='\n').write(out_text)
print('written:', NEW, len(out_text.split('\n')), 'lines')

# ============ 重建 facade ============
skip = set()
for v in BLOCKS.values():
    skip.update(range(v[0], v[1] + 1))
kept = [l for i, l in enumerate(lines) if i not in skip]
facade = '\n'.join(kept)

# 调用点
facade = facade.replace('emitToolStep(toolStepId, name, args, callFingerprints, messages, callId,',
                        'stepEmitter.emitToolStep(toolStepId, name, args, callFingerprints, messages, callId,')
# 新字段 + 构造器(在 toolExecutor 与 capabilityRegistry 创建之后)
anchor = '        this.capabilityRegistry = new ModelCapabilityRegistry();'
assert anchor in facade
facade = facade.replace(anchor, anchor + '\n'
    '        this.stepEmitter = new ToolStepEmitter(objectMapper, approvalService, toolExecutor, capabilityRegistry);')
field_anchor = '    private final ModelCapabilityRegistry capabilityRegistry;'
assert field_anchor in facade
facade = facade.replace(field_anchor, field_anchor + '\n'
    '    /** 工具步骤发射器(从本类拆出,2026-09-17 拆分方案收尾)。 */\n'
    '    private final ToolStepEmitter stepEmitter;')

io.open(SVC, 'w', encoding='utf-8', newline='\n').write(facade)
print('facade: %d -> %d lines' % (len(lines), len(kept)))

# ============ ChatToolExecutor 签名 ============
exec_src = io.open(EXEC, encoding='utf-8').read()
exec_src = exec_src.replace('ToolOutcome executeTool(String name, String args, ChatOrchestrationService.ParsedArgs parsed,',
                            'ToolOutcome executeTool(String name, String args, ToolStepEmitter.ParsedArgs parsed,')
io.open(EXEC, 'w', encoding='utf-8', newline='\n').write(exec_src)
print('executor signature updated')
