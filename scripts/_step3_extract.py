# -*- coding: utf-8 -*-
"""Step 3 v2: 抽取 UpstreamLlmClient + ModelResolver + ModelCapabilityRegistry + 共享类型。"""
import io, re

BASE = r'nora-api/services/agent-service/src/main/java/com/nora/agent/service/'
FACADE = BASE + 'ChatOrchestrationService.java'

src = io.open(FACADE, encoding='utf-8').read()
lines = src.split('\n')

def find_idx(sub, start=0):
    for i in range(start, len(lines)):
        if sub in lines[i] and not lines[i].strip().startswith(('//', '*')):
            return i
    raise RuntimeError('not found: ' + sub)

def block_end(start):
    for j in range(start + 1, len(lines)):
        if lines[j] == '    }':
            return j
    raise RuntimeError('no end for line %d: %s' % (start + 1, lines[start]))

def grab_comment(s):
    while s - 1 >= 0:
        p = lines[s-1].strip()
        if p.startswith('/**') or p.startswith('*') or p.startswith('/*') or p == '*/':
            s -= 1
        else:
            break
    return s

def take(sub, inline=False, comment=False):
    s = find_idx(sub)
    if inline:
        e = s
        for j in range(s, len(lines)):
            if ') {}' in lines[j] and j > s:
                e = j
                break
    else:
        e = block_end(s)
    s2 = grab_comment(s) if comment else s
    return (s2, e, lines[s2:e+1])

MOVES = {}
def mv(key, sub, comment=False):
    MOVES[key] = take(sub, comment=comment)
    print('MOVE   %-24s %4d-%4d' % (key, MOVES[key][0]+1, MOVES[key][1]+1))

DELETES = {}
def dl(key, sub, comment=False):
    DELETES[key] = take(sub, comment=comment)
    print('DELETE %-24s %4d-%4d' % (key, DELETES[key][0]+1, DELETES[key][1]+1))

# ---- 移入 UpstreamLlmClient ----
mv('streamUpstream', 'private StreamTurnResult streamUpstream(ResolvedLlm llm, ObjectNode body, TokenSink sink) {', comment=True)
mv('streamUpstreamResponses', 'private StreamTurnResult streamUpstreamResponses(', comment=True)
mv('logUpstreamRequest', 'private void logUpstreamRequest(ResolvedLlm llm, ObjectNode body) {')
mv('applyExtraHeaders', 'private java.net.http.HttpRequest.Builder applyExtraHeaders(')
mv('providerExtraHeaders', 'private String[] providerExtraHeaders(ResolvedLlm llm) {')
mv('stripTrailingSlash', 'private static String stripTrailingSlash(String url) {')
mv('baseBody', 'private ObjectNode baseBody(boolean stream, ResolvedLlm llm) {')
mv('applyReasoningRequest', 'private void applyReasoningRequest(ObjectNode body, ResolvedLlm llm) {', comment=True)
mv('isOpenAiEffort', 'private static boolean isOpenAiEffort(String level) {')
mv('supportsReasoningEffort', 'private static boolean supportsReasoningEffort(String model) {')
mv('abbreviateForSse', 'private static String abbreviateForSse(String text, int max) {')
mv('friendlyUpstreamError', 'private static String friendlyUpstreamError(String body) {', comment=True)
mv('withUpstreamHint', 'private static String withUpstreamHint(String message) {', comment=True)
mv('deepestMessage', 'private static String deepestMessage(String unescaped) {', comment=True)
mv('wireMessagesOf', 'private List<WireMessage> wireMessagesOf(ObjectNode chatBody) {')
mv('unionKeys', 'private static java.util.Set<Integer> unionKeys(')
mv('messagesArray', 'private ArrayNode messagesArray(List<WireMessage> messages) {')
mv('TokenSink', 'private interface TokenSink {')

# ---- 共享类型(独立文件) ----
mv('ResolvedLlm', 'record ResolvedLlm(String baseUrl, String apiKey, String model, String protocol,', )
MOVES['ResolvedLlm'] = take('record ResolvedLlm(String baseUrl, String apiKey, String model, String protocol,', inline=True)
print('MOVE   %-24s %4d-%4d' % ('ResolvedLlm', MOVES['ResolvedLlm'][0]+1, MOVES['ResolvedLlm'][1]+1))
mv('StreamTurnResult', 'record StreamTurnResult(boolean failed, String errorMessage, String content,')
mv('WireMessage', 'private record WireMessage(ObjectNode node) {')

# ---- ModelResolver ----
mv('resolveLlm1', 'private ResolvedLlm resolveLlm(String requestedModel) {')
mv('resolveLlm2', 'private ResolvedLlm resolveLlm(String requestedModel, String requestedReasoningLevel, Long providerId) {', comment=True)
mv('resolveFromStore', 'private ResolvedLlm resolveFromStore(String requestedModel, String requestedReasoningLevel, Long providerId) {', comment=True)
mv('effectiveReasoningLevel', 'private String effectiveReasoningLevel(ModelProviderService.ActiveProvider provider, String model,')

# ---- 删除(死代码/已迁) ----
dl('Tokens', 'private record Tokens(String content, String reasoning) {', comment=True)
dl('messageReasoning', 'private String messageReasoning(JsonNode message) {')
dl('withLevel', 'private static ResolvedLlm withLevel(ResolvedLlm llm, String reasoningLevel) {')
dl('effortKey', 'private static String effortKey(ResolvedLlm llm) {', comment=True)
dl('isReasoningEffortUnsupported', 'private static boolean isReasoningEffortUnsupported(String errorMessage) {', comment=True)
dl('visionAllowed', 'private boolean visionAllowed(ResolvedLlm llm, int imageCount) {', comment=True)
dl('visionKey', 'private static String visionKey(ResolvedLlm llm) {', comment=True)
dl('isVisionUnsupported', 'private static boolean isVisionUnsupported(String errorMessage) {', comment=True)
dl('isGenericUpstream400', 'private static boolean isGenericUpstream400(String errorMessage) {', comment=True)
dl('isInterruption', 'private static boolean isInterruption(Throwable t) {', comment=True)

# ---- 字段 ----
def take_field(field_sub):
    fi = find_idx(field_sub)
    s = grab_comment(fi)
    return (s, fi, lines[s:fi+1])
MOVES['visionField'] = take_field('private final java.util.Set<String> visionRejectedModels')
MOVES['effortField'] = take_field('private final java.util.Set<String> effortRejectedModels')
print('MOVE   %-24s %4d-%4d' % ('visionField', MOVES['visionField'][0]+1, MOVES['visionField'][1]+1))
print('MOVE   %-24s %4d-%4d' % ('effortField', MOVES['effortField'][0]+1, MOVES['effortField'][1]+1))

# 重叠检查
allr = sorted([(v[0], v[1]) for v in list(MOVES.values()) + list(DELETES.values())])
for a, b in zip(allr, allr[1:]):
    assert a[1] < b[0], 'overlap %s %s' % (a, b)
print('\nno overlaps, %d blocks' % len(allr))

def join(key):
    return '\n'.join(MOVES[key][2])

# ================= 新文件 =================
# ---- ModelCapabilityRegistry.java ----
reg = '''package com.nora.agent.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型能力降级注册表(2026-09-17 从 ChatOrchestrationService 拆出,复杂度审计 Step 3):
 * 进程内记忆「上游拒绝图片 / 拒绝 reasoning_effort」的模型,避免每轮都"失败→重试"白费。
 * 键 = 端点|模型(effort 键额外含档位);重启后重新探测。
 */
class ModelCapabilityRegistry {

    private final Set<String> visionRejectedModels = ConcurrentHashMap.newKeySet();
    private final Set<String> effortRejectedModels = ConcurrentHashMap.newKeySet();

    /** 探测记忆的键:同一上游端点上的同名模型共享结论。 */
    static String visionKey(ResolvedLlm llm) {
        return (llm.baseUrl() == null ? "" : llm.baseUrl()) + "|" + (llm.model() == null ? "" : llm.model());
    }

    /** 探测记忆的键:上游端点 + 模型 + 档位(stripLevel=true 时按"无档位"计算)。 */
    static String effortKey(ResolvedLlm llm, boolean stripLevel) {
        String level = stripLevel || llm.effectiveReasoningLevel() == null
                ? "" : llm.effectiveReasoningLevel().toLowerCase();
        return (llm.baseUrl() == null ? "" : llm.baseUrl()) + "|" + (llm.model() == null ? "" : llm.model())
                + "|" + level;
    }

    /**
     * 是否允许把图片附到该模型的请求里。
     * 优先级:设置页显式开关 > 运行时探测记忆 > 默认尝试(上游拒绝则自动剥离)。
     */
    boolean visionAllowed(ResolvedLlm llm, int imageCount) {
        if (llm == null || imageCount <= 0) return false;
        if (Boolean.FALSE.equals(llm.vision())) return false;
        if (Boolean.TRUE.equals(llm.vision())) return true;
        return !visionRejectedModels.contains(visionKey(llm));
    }

    /** 记忆"上游拒绝图片"的模型:首次遇到拒绝后,后续轮次直接不再附加图片。 */
    void markVisionRejected(ResolvedLlm llm) {
        visionRejectedModels.add(visionKey(llm));
    }

    /** 该(端点|模型|档位)是否已被上游拒绝过。 */
    boolean isEffortRejected(ResolvedLlm llm) {
        return effortRejectedModels.contains(effortKey(llm, false));
    }

    /** 挂"该档位被拒"结论(重试成功才保留)。 */
    void markEffortRejected(ResolvedLlm llm) {
        effortRejectedModels.add(effortKey(llm, false));
    }

    /** 挂"该模型不注入档位"结论:让重试真正不带 reasoning_effort。 */
    void markEffortStripped(ResolvedLlm llm) {
        effortRejectedModels.add(effortKey(llm, true));
    }

    void clearEffortRejected(ResolvedLlm llm) {
        effortRejectedModels.remove(effortKey(llm, false));
    }

    void clearEffortStripped(ResolvedLlm llm) {
        effortRejectedModels.remove(effortKey(llm, true));
    }

    /**
     * 上游是否明确拒绝 reasoning_effort 取值。
     * 实测文案(中转站透传上游错误):
     * "the reasoning effort value is not supported by the current model" /
     * "invalid_reasoning_effort" / "unsupported value ... reasoning_effort"
     */
    static boolean isReasoningEffortUnsupported(String errorMessage) {
        if (errorMessage == null) return false;
        String e = errorMessage.toLowerCase();
        if (!e.contains("reasoning")) return false;
        return e.contains("not supported") || e.contains("unsupported")
                || e.contains("invalid") || e.contains("not valid");
    }

    /**
     * 上游错误是否为"不支持图像输入"。
     * 实测拒绝文案示例:
     * "Model X does not support image input. Remove the image content or use a vision-capable model."
     */
    static boolean isVisionUnsupported(String errorMessage) {
        if (errorMessage == null) return false;
        String e = errorMessage.toLowerCase();
        return e.contains("does not support image") || e.contains("not support image")
                || e.contains("vision-capable") || e.contains("image input")
                || e.contains("invalid image") || e.contains("unsupported image");
    }

    /**
     * 中转以流式返回时会把上游 400 粒度化为 "Upstream error: 400"——
     * 看不到"不支持图像输入"原文。因此当请求里带图且收到 400 时,
     * 按"可能是识图不支持"处理(剥图重试一次,成功则记住结论)。
     */
    static boolean isGenericUpstream400(String errorMessage) {
        if (errorMessage == null) return false;
        String e = errorMessage.toLowerCase();
        boolean has400 = e.contains("400");
        boolean vague = e.contains("upstream error") || e.contains("上游");
        return has400 && vague;
    }
}
'''
io.open(BASE + 'ModelCapabilityRegistry.java', 'w', encoding='utf-8', newline='\n').write(reg)

# ---- ResolvedLlm.java ----
resolved_java = '''package com.nora.agent.service;

/**
 * 已解析的执行端点与模型配置(2026-09-17 从 ChatOrchestrationService 拆出)。
 * Package-visible for tests; never returned outside the service.
 */
record ResolvedLlm(String baseUrl, String apiKey, String model, String protocol,
                   /** 生效思考等级(已合并请求级与设置页默认);null = auto */
                   String effectiveReasoningLevel,
                   /** 模型上下文窗口(tokens);null = 未配置 */
                   Long contextWindow,
                   /**
                    * 识图能力(设置页每模型开关):TRUE/FALSE = 强制;null = 运行时自适应
                    * (默认尝试附加图片,上游以"不支持图片"拒绝时自动剥离并记忆)。
                    */
                   Boolean vision) {

    /** 返回携带指定思考等级的副本(null 安全:llm 为 null 时返回 null)。 */
    static ResolvedLlm withLevel(ResolvedLlm llm, String reasoningLevel) {
        if (llm == null) return null;
        return new ResolvedLlm(llm.baseUrl(), llm.apiKey(), llm.model(), llm.protocol(), reasoningLevel,
                llm.contextWindow(), llm.vision());
    }
}
'''
io.open(BASE + 'ResolvedLlm.java', 'w', encoding='utf-8', newline='\n').write(resolved_java)

# ---- StreamTurnResult.java ----
str_java = '''package com.nora.agent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

/** One streamed model turn: forwarded content/reasoning plus accumulated tool_calls. */
record StreamTurnResult(boolean failed, String errorMessage, String content,
                        String reasoning, ObjectNode assistantMessage, List<JsonNode> toolCalls,
                        ChatOrchestrationService.TokenUsage usage) {
}
'''
io.open(BASE + 'StreamTurnResult.java', 'w', encoding='utf-8', newline='\n').write(str_java)

# ---- WireMessage.java ----
wire = join('WireMessage').replace('private record WireMessage', 'record WireMessage')
wire_java = ('package com.nora.agent.service;\n\n'
             'import com.fasterxml.jackson.databind.ObjectMapper;\n'
             'import com.fasterxml.jackson.databind.node.ObjectNode;\n\n'
             '/** Wire-format message wrapper (JsonNode so tool messages mix in). */\n'
             + wire + '\n')
io.open(BASE + 'WireMessage.java', 'w', encoding='utf-8', newline='\n').write(wire_java)

# ---- UpstreamLlmClient.java ----
cp = []
cp.append('package com.nora.agent.service;')
cp.append('')
cp.append('import com.fasterxml.jackson.databind.JsonNode;')
cp.append('import com.fasterxml.jackson.databind.ObjectMapper;')
cp.append('import com.fasterxml.jackson.databind.node.ArrayNode;')
cp.append('import com.fasterxml.jackson.databind.node.ObjectNode;')
cp.append('import org.slf4j.Logger;')
cp.append('import org.slf4j.LoggerFactory;')
cp.append('import org.springframework.http.MediaType;')
cp.append('')
cp.append('import java.time.Duration;')
cp.append('import java.util.ArrayList;')
cp.append('import java.util.HashMap;')
cp.append('import java.util.List;')
cp.append('import java.util.Map;')
cp.append('')
cp.append('/**')
cp.append(' * LLM 上游流式客户端(2026-09-17 从 ChatOrchestrationService 拆出,复杂度审计 Step 3):')
cp.append(' * 两套协议(openai chat.completions / responses)的 SSE 解析、请求体构建与错误友好化。')
cp.append(' * 纯机械平移,行为与拆分前逐行一致。')
cp.append(' */')
cp.append('class UpstreamLlmClient {')
cp.append('')
cp.append('    private static final Logger log = LoggerFactory.getLogger(UpstreamLlmClient.class);')
cp.append('    /** 上游 LLM SSE 事件时间线专用 logger(独立文件 agent-service-sse.log,见 logback) */')
cp.append('    private static final Logger sseLog = LoggerFactory.getLogger("com.nora.agent.sse");')
cp.append('')
cp.append('    private final ObjectMapper objectMapper;')
cp.append('    private final ModelCapabilityRegistry capabilityRegistry;')
cp.append('')
cp.append('    UpstreamLlmClient(ObjectMapper objectMapper, ModelCapabilityRegistry capabilityRegistry) {')
cp.append('        this.objectMapper = objectMapper;')
cp.append('        this.capabilityRegistry = capabilityRegistry;')
cp.append('    }')
cp.append('')

order = ['streamUpstream', 'streamUpstreamResponses', 'logUpstreamRequest', 'applyExtraHeaders',
         'providerExtraHeaders', 'stripTrailingSlash', 'baseBody', 'applyReasoningRequest',
         'isOpenAiEffort', 'supportsReasoningEffort', 'abbreviateForSse', 'friendlyUpstreamError',
         'withUpstreamHint', 'deepestMessage', 'wireMessagesOf', 'unionKeys', 'messagesArray',
         'TokenSink']
for key in order:
    body = join(key)
    body = body.replace('private StreamTurnResult streamUpstream(', 'StreamTurnResult streamUpstream(')
    body = body.replace('private ObjectNode baseBody(', 'ObjectNode baseBody(')
    body = body.replace('private ArrayNode messagesArray(', 'ArrayNode messagesArray(')
    body = body.replace('private void applyReasoningRequest(', 'void applyReasoningRequest(')
    body = body.replace('private interface TokenSink', 'interface TokenSink')
    body = body.replace('isInterruption(', 'Texts.isInterruption(')
    body = body.replace('effortRejectedModels.contains(effortKey(llm))', 'capabilityRegistry.isEffortRejected(llm)')
    body = re.sub(r'(?<![\w.])TokenUsage(?![\w])', 'ChatOrchestrationService.TokenUsage', body)
    cp.append(body)
    cp.append('')
cp.append('}')
io.open(BASE + 'UpstreamLlmClient.java', 'w', encoding='utf-8', newline='\n').write('\n'.join(cp))

# ---- ModelResolver.java ----
rp = []
rp.append('package com.nora.agent.service;')
rp.append('')
rp.append('import com.nora.agent.config.LlmProperties;')
rp.append('')
rp.append('/**')
rp.append(' * 模型/渠道解析器(2026-09-17 从 ChatOrchestrationService 拆出,复杂度审计 Step 3):')
rp.append(' * 请求级模型名 > provider store(设置中心) > 静态 nora.llm.* 兜底;')
rp.append(' * 思考等级合并:请求级 > 设置页 per-model 默认 > auto(白名单约束)。')
rp.append(' */')
rp.append('class ModelResolver {')
rp.append('')
rp.append('    private final LlmProperties llmProperties;')
rp.append('    private final ModelProviderService modelProviderService;')
rp.append('')
rp.append('    ModelResolver(LlmProperties llmProperties, ModelProviderService modelProviderService) {')
rp.append('        this.llmProperties = llmProperties;')
rp.append('        this.modelProviderService = modelProviderService;')
rp.append('    }')
rp.append('')
rp.append(join('resolveLlm1'))
rp.append('')
rp.append(join('resolveLlm2'))
rp.append('')
rp.append(join('resolveFromStore'))
rp.append('')
rp.append(join('effectiveReasoningLevel'))
rp.append('}')
resolver_text = '\n'.join(rp)
resolver_text = resolver_text.replace('private ResolvedLlm resolveLlm(String requestedModel) {',
                                      'ResolvedLlm resolve(String requestedModel) {')
resolver_text = resolver_text.replace('private ResolvedLlm resolveLlm(String requestedModel, String requestedReasoningLevel, Long providerId) {',
                                      'ResolvedLlm resolve(String requestedModel, String requestedReasoningLevel, Long providerId) {')
resolver_text = resolver_text.replace('resolveLlm(', 'resolve(')
io.open(BASE + 'ModelResolver.java', 'w', encoding='utf-8', newline='\n').write(resolver_text)

# ================= 重建 facade =================
skip = set()
for v in list(MOVES.values()) + list(DELETES.values()):
    skip.update(range(v[0], v[1] + 1))
kept = [l for i, l in enumerate(lines) if i not in skip]
facade = '\n'.join(kept)

# 文本替换(顺序敏感)
facade = facade.replace('resolveLlm(', 'modelResolver.resolve(')
facade = facade.replace('withLevel(', 'ResolvedLlm.withLevel(')

# 档位标记块(tool loop + final answer)
facade = re.sub(
    r'(?m)^(\s*)String rejectedKey = effortKey\(resolved\);\n\s*String stripKey = effortKey\(ResolvedLlm\.withLevel\(resolved, null\)\);\n\s*effortRejectedModels\.add\(rejectedKey\);\n\s*effortRejectedModels\.add\(stripKey\);',
    lambda m: m.group(1) + 'capabilityRegistry.markEffortRejected(resolved);\n'
              + m.group(1) + 'capabilityRegistry.markEffortStripped(resolved);',
    facade)
facade = re.sub(
    r'(?m)^(\s*)effortRejectedModels\.remove\(rejectedKey\);\n\s*effortRejectedModels\.remove\(stripKey\);',
    lambda m: m.group(1) + 'capabilityRegistry.clearEffortRejected(resolved);\n'
              + m.group(1) + 'capabilityRegistry.clearEffortStripped(resolved);',
    facade)
facade = facade.replace('visionRejectedModels.add(visionKey(resolved));', 'capabilityRegistry.markVisionRejected(resolved);')
facade = facade.replace('visionAllowed(llm, images.size())', 'capabilityRegistry.visionAllowed(llm, images.size())')
facade = facade.replace('isVisionUnsupported(', 'ModelCapabilityRegistry.isVisionUnsupported(')
facade = facade.replace('isGenericUpstream400(', 'ModelCapabilityRegistry.isGenericUpstream400(')
facade = facade.replace('isReasoningEffortUnsupported(', 'ModelCapabilityRegistry.isReasoningEffortUnsupported(')
facade = facade.replace('isInterruption(', 'Texts.isInterruption(')
facade = facade.replace('baseBody(', 'upstreamClient.baseBody(')
facade = facade.replace('messagesArray(', 'upstreamClient.messagesArray(')
facade = facade.replace('streamUpstream(llm, body,', 'upstreamClient.streamUpstream(llm, body,')

# 清理孤儿注释
for orphan in [
    '    /** Returns a copy of the resolved endpoint carrying the given reasoning level. */\n\n',
    '    /** Returns a copy of the resolved endpoint carrying the given reasoning level. */\n',
    '    /** Package-visible for tests; never returned outside the service. */\n',
]:
    facade = facade.replace(orphan, '')

# 删 facade 的 sseLog 字段(已随上游方法移走)
facade = facade.replace('    /** 上游 LLM SSE 事件时间线专用 logger(独立文件 agent-service-sse.log,见 logback) */\n', '')
facade = facade.replace('    private static final Logger sseLog = LoggerFactory.getLogger("com.nora.agent.sse");\n', '')

# 新字段 + 构造器
anchor = '    private final ChatToolExecutor toolExecutor;'
assert anchor in facade, 'anchor1 missing'
facade = facade.replace(anchor, anchor + '\n'
    '    /** 模型能力降级注册表(进程内记忆,2026-09-17 拆分 Step 3)。 */\n'
    '    private final ModelCapabilityRegistry capabilityRegistry;\n'
    '    /** LLM 上游流式客户端(2026-09-17 拆分 Step 3)。 */\n'
    '    private final UpstreamLlmClient upstreamClient;\n'
    '    /** 模型/渠道解析器(2026-09-17 拆分 Step 3)。 */\n'
    '    private final ModelResolver modelResolver;')

anchor2 = '''        this.toolExecutor = new ChatToolExecutor(objectMapper, sqlToolClient, serviceLogClient, writeSqlClient,
                containerControlClient, dataSourceManageClient, serviceManageClient, fileToolClient,
                mcpServerService, terminalService, agentWorkspaceService, agentSkillService);'''
assert anchor2 in facade, 'anchor2 missing'
facade = facade.replace(anchor2, anchor2 + '\n'
    '        this.capabilityRegistry = new ModelCapabilityRegistry();\n'
    '        this.upstreamClient = new UpstreamLlmClient(objectMapper, capabilityRegistry);\n'
    '        this.modelResolver = new ModelResolver(llmProperties, modelProviderService);')

io.open(FACADE, 'w', encoding='utf-8', newline='\n').write(facade)
print('\nfacade: %d -> %d lines' % (len(lines), len(kept)))
print('written: ModelCapabilityRegistry, ResolvedLlm, StreamTurnResult, WireMessage, UpstreamLlmClient, ModelResolver')
