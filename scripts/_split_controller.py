# -*- coding: utf-8 -*-
"""拆分 AgentController:抽取 ChatTurnRunner(轮次执行引擎 + SSE 发送 + 载荷 record)。"""
import io

CTRL = r'nora-api/services/agent-service/src/main/java/com/nora/agent/controller/AgentController.java'
RUNNER = r'nora-api/services/agent-service/src/main/java/com/nora/agent/controller/ChatTurnRunner.java'

src = io.open(CTRL, encoding='utf-8').read()
lines = src.split('\n')

def find_idx(sub, start=0):
    for i in range(start, len(lines)):
        if sub in lines[i]:
            return i
    raise RuntimeError('not found: ' + sub)

def block_end(start, terminator='    }'):
    for j in range(start + 1, len(lines)):
        if lines[j] == terminator:
            return j
    raise RuntimeError('no end for line %d' % (start + 1))

# ---- 定位各块(用内容断言边界) ----
# 1. titleExecutor 字段(含注释块)
t_field = find_idx('private final ExecutorService titleExecutor')
t_field_start = t_field
while t_field_start - 1 >= 0 and (lines[t_field_start-1].strip().startswith('*')
        or lines[t_field_start-1].strip().startswith('/**')
        or lines[t_field_start-1].strip() == '*/'):
    t_field_start -= 1
assert lines[t_field_start].strip().startswith('/**'), repr(lines[t_field_start])
assert 'titleExecutor = Executors' in lines[t_field], repr(lines[t_field])
print('titleExecutor field: %d-%d' % (t_field_start+1, t_field+1))

# 2. runChatTurn
rc = find_idx('private void runChatTurn(')
rc_end = block_end(rc)
assert lines[rc_end] == '    }'
assert 'turnStreams.finish(sessionId, liveTurn);' in '\n'.join(lines[rc:rc_end+1])
print('runChatTurn: %d-%d' % (rc+1, rc_end+1))

# 3. scheduleTitleGeneration(含注释)
st = find_idx('private void scheduleTitleGeneration(')
st_start = st
while st_start - 1 >= 0 and (lines[st_start-1].strip().startswith('*')
        or lines[st_start-1].strip().startswith('/**')
        or lines[st_start-1].strip() == '*/'):
    st_start -= 1
st_end = block_end(st)
assert 'titleExecutor.execute' in '\n'.join(lines[st:st_end+1])
print('scheduleTitleGeneration: %d-%d' % (st_start+1, st_end+1))

# 4. TitlePayload(含注释)
tp = find_idx('public record TitlePayload(')
tp_end = block_end(tp, '    }')
tp_start = tp - 1  # 上一行是注释
assert lines[tp_start].strip().startswith('/**')
print('TitlePayload: %d-%d' % (tp_start+1, tp_end+1))

# 5. send
sd = find_idx('private void send(SseEmitter emitter, String event, Object payload) {')
sd_end = block_end(sd)
print('send: %d-%d' % (sd+1, sd_end+1))

# 6. abbreviate
ab = find_idx('private static String abbreviate(String s, int max) {')
ab_end = block_end(ab)
print('abbreviate: %d-%d' % (ab+1, ab_end+1))

# 7. toJson
tj = find_idx('private String toJson(Object payload) {')
tj_end = block_end(tj)
print('toJson: %d-%d' % (tj+1, tj_end+1))

# 8. DeltaPayload(注释+record)
dp = find_idx('public record DeltaPayload(String content) {')
dp_end = block_end(dp)
dp_start = dp - 1
assert lines[dp_start].strip().startswith('/**')
print('DeltaPayload: %d-%d' % (dp_start+1, dp_end+1))

# 9. ReasoningDeltaPayload
rp = find_idx('public record ReasoningDeltaPayload(')
rp_end = block_end(rp)
rp_start = rp - 1
assert lines[rp_start].strip().startswith('/**')
print('ReasoningDeltaPayload: %d-%d' % (rp_start+1, rp_end+1))

# 10. DonePayload(大注释)
dop = find_idx('public record DonePayload(String messageId,')
dop_end = block_end(dop)
dop_start = dop
while dop_start - 1 >= 0 and (lines[dop_start-1].strip().startswith('*')
        or lines[dop_start-1].strip().startswith('/**')
        or lines[dop_start-1].strip() == '*/'):
    dop_start -= 1
assert lines[dop_start].strip().startswith('/**')
print('DonePayload: %d-%d' % (dop_start+1, dop_end+1))

# 11. ErrorPayload(大注释)
ep = find_idx('public record ErrorPayload(String message,')
ep_end = block_end(ep)
ep_start = ep
while ep_start - 1 >= 0 and (lines[ep_start-1].strip().startswith('*')
        or lines[ep_start-1].strip().startswith('/**')
        or lines[ep_start-1].strip() == '*/'):
    ep_start -= 1
assert lines[ep_start].strip().startswith('/**')
print('ErrorPayload: %d-%d' % (ep_start+1, ep_end+1))

# ---- 组装 ChatTurnRunner.java ----
def seg(s, e):
    return '\n'.join(lines[s:e+1])

runner = []
runner.append('package com.nora.agent.controller;')
runner.append('')
runner.append('import com.fasterxml.jackson.databind.ObjectMapper;')
runner.append('import com.nora.agent.dto.ApprovalRequestDto;')
runner.append('import com.nora.agent.dto.ChatStepDto;')
runner.append('import com.nora.agent.dto.CitationDto;')
runner.append('import com.nora.agent.service.ChatOrchestrationService;')
runner.append('import com.nora.agent.service.ChatStoreService;')
runner.append('import com.nora.agent.service.PermissionMode;')
runner.append('import com.nora.agent.service.TurnStreamRegistry;')
runner.append('import com.nora.common.logging.TraceContext;')
runner.append('import org.slf4j.Logger;')
runner.append('import org.slf4j.LoggerFactory;')
runner.append('import org.springframework.http.MediaType;')
runner.append('import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;')
runner.append('')
runner.append('import java.io.IOException;')
runner.append('import java.util.List;')
runner.append('import java.util.UUID;')
runner.append('import java.util.concurrent.ExecutorService;')
runner.append('import java.util.concurrent.Executors;')
runner.append('')
runner.append('/**')
runner.append(' * 一轮对话的执行引擎(2026-09-17 从 AgentController 拆出,复杂度审计建议 #2):')
runner.append(' * 编排调用 + 事件双写(emitter 直发 + TurnStreamRegistry 缓冲)+ 步骤/推理')
runner.append(' * 落库 + 取消/失败收尾 + 异步标题生成 + SSE 发送辅助与载荷类型。')
runner.append(' * 纯机械平移,行为与拆分前逐行一致。')
runner.append(' */')
runner.append('class ChatTurnRunner {')
runner.append('')
runner.append('    private static final Logger log = LoggerFactory.getLogger(ChatTurnRunner.class);')
runner.append('')
runner.append('    private final ChatOrchestrationService orchestrationService;')
runner.append('    private final ChatStoreService chatStoreService;')
runner.append('    private final TurnStreamRegistry turnStreams;')
runner.append('    private final ObjectMapper objectMapper;')
runner.append('')
runner.append('    /**')
runner.append('     * 会话标题生成专用线程池。')
runner.append('     *')
runner.append('     * <p>独立于对话轮次执行线程：起标题是「锦上添花」的旁路任务，不能与')
runner.append('     * 对话轮次争抢，也不能被「停止生成」（中断对话线程上的 Future）连带取消')
runner.append('     * ——用户停掉回答后，标题仍应正常生成。')
runner.append('     */')
runner.append('    private final ExecutorService titleExecutor = Executors.newCachedThreadPool();')
runner.append('')
runner.append('    ChatTurnRunner(ChatOrchestrationService orchestrationService,')
runner.append('                   ChatStoreService chatStoreService,')
runner.append('                   TurnStreamRegistry turnStreams,')
runner.append('                   ObjectMapper objectMapper) {')
runner.append('        this.orchestrationService = orchestrationService;')
runner.append('        this.chatStoreService = chatStoreService;')
runner.append('        this.turnStreams = turnStreams;')
runner.append('        this.objectMapper = objectMapper;')
runner.append('    }')
runner.append('')
runner.append(seg(rc, rc_end))
runner.append('')
runner.append(seg(st_start, st_end))
runner.append('')
runner.append(seg(tp_start, tp_end))
runner.append('')
runner.append(seg(sd, sd_end))
runner.append('')
runner.append(seg(ab, ab_end))
runner.append('')
runner.append(seg(tj, tj_end))
runner.append('')
runner.append(seg(dp_start, dp_end))
runner.append('')
runner.append(seg(rp_start, rp_end))
runner.append('')
runner.append(seg(dop_start, dop_end))
runner.append('')
runner.append(seg(ep_start, ep_end))
runner.append('')
runner.append('}')
runner_text = '\n'.join(runner)
# runChatTurn 可见性:包内可见(controller 调用)
runner_text = runner_text.replace('private void runChatTurn(', 'void runChatTurn(')
io.open(RUNNER, 'w', encoding='utf-8', newline='\n').write(runner_text)
print('\nwritten:', RUNNER, '|', len(runner_text.split('\n')), 'lines')

# ---- 重建 AgentController ----
skip = set()
for (s, e) in [(t_field_start, t_field), (rc, rc_end), (st_start, st_end), (tp_start, tp_end),
               (sd, sd_end), (ab, ab_end), (tj, tj_end), (dp_start, dp_end), (rp_start, rp_end),
               (dop_start, dop_end), (ep_start, ep_end)]:
    skip.update(range(s, e+1))
kept = [l for i, l in enumerate(lines) if i not in skip]
ctrl = '\n'.join(kept)

# 调用点 + 字段 + 构造器
ctrl = ctrl.replace('runChatTurn(sessionId, request.content().trim(),',
                    'turnRunner.runChatTurn(sessionId, request.content().trim(),')
anchor = '    private final TurnStreamRegistry turnStreams;'
assert anchor in ctrl
ctrl = ctrl.replace(anchor, anchor + '\n'
    '    /** 轮次执行引擎(从本类拆出,2026-09-17)。 */\n'
    '    private final ChatTurnRunner turnRunner;')
anchor2 = '        this.turnStreams = turnStreams;'
assert anchor2 in ctrl
ctrl = ctrl.replace(anchor2, anchor2 + '\n'
    '        this.turnRunner = new ChatTurnRunner(orchestrationService, chatStoreService, turnStreams, objectMapper);')
io.open(CTRL, 'w', encoding='utf-8', newline='\n').write(ctrl)
print('controller: %d -> %d lines' % (len(lines), len(kept)))
