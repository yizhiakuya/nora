# -*- coding: utf-8 -*-
import io

p = r'D:\claude\Nora\nora-api\services\agent-service\src\main\java\com\nora\agent\service\ChatOrchestrationService.java'
s = io.open(p, encoding='utf-8').read()

def rep(old, new, cnt=1):
    global s
    assert s.count(old) == cnt, 'MATCH FAIL (%d): %s' % (s.count(old), old[:70])
    s = s.replace(old, new)

# 校准日志:轮次结束时,最后一次请求的估算 prompt vs 上游真实 inputTokens
# (真实值含 tools spec/协议封装,估算只含消息体——差值即固定开销系数)
rep('''                if (result.toolCalls.isEmpty()) {
                    // 回答(与推理)已随流逐 token 转发完毕
                    return CompletableFuture.completedFuture(new ChatTurn(result.content, citations, totalUsage, budget.window(), lastPromptEstimate[0], ttftMs[0] < 0 ? null : ttftMs[0]));''',
'''                if (result.toolCalls.isEmpty()) {
                    // 回答(与推理)已随流逐 token 转发完毕
                    logCalibration(lastPromptEstimate[0], totalUsage, budget);
                    return CompletableFuture.completedFuture(new ChatTurn(result.content, citations, totalUsage, budget.window(), lastPromptEstimate[0], ttftMs[0] < 0 ? null : ttftMs[0]));''')

rep('''            if (!answerText.isBlank()) {
                return CompletableFuture.completedFuture(new ChatTurn(answerText, citations, totalUsage, budget.window(), lastPromptEstimate[0], ttftMs[0] < 0 ? null : ttftMs[0]));
            }''',
'''            if (!answerText.isBlank()) {
                logCalibration(lastPromptEstimate[0], totalUsage, budget);
                return CompletableFuture.completedFuture(new ChatTurn(answerText, citations, totalUsage, budget.window(), lastPromptEstimate[0], ttftMs[0] < 0 ? null : ttftMs[0]));
            }''')

# 校准日志方法:放在 isContextOverflow 后面
rep('''    /** 上游上下文超限错误识别(各家中转措辞不一,宽松匹配)。 */
    static boolean isContextOverflow(String errorMessage) {''',
'''    /**
     * 估算校准日志:每轮结束把服务端 prompt 估算与上游真实 inputTokens
     * 对齐输出(真实值含 tools spec/协议封装 overhead,估算只含消息体)。
     * 比值持续偏离预期时调整 ContextBudget 估算系数——观测驱动的闭环。
     */
    private void logCalibration(int promptEstimate, TokenUsage usage, ContextBudget budget) {
        if (usage == null || usage.inputTokens() == null || promptEstimate <= 0) return;
        int real = usage.inputTokens();
        double ratio = (double) real / promptEstimate;
        // 只在明显偏离时告警(±35% 外),正常波动打 debug
        if (ratio < 0.65 || ratio > 1.35) {
            log.warn("context estimate calibration: promptEstimate={} realInput={} ratio={:.2f} window={} trigger={}",
                    promptEstimate, real, ratio, budget.window(), budget.triggerTokens());
        } else {
            log.debug("context estimate ok: promptEstimate={} realInput={} ratio={:.2f}",
                    promptEstimate, real, ratio);
        }
    }

    /** 上游上下文超限错误识别(各家中转措辞不一,宽松匹配)。 */
    static boolean isContextOverflow(String errorMessage) {''')

io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('calibration done')
