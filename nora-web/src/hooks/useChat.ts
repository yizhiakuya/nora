import { useState, useCallback, useRef, useEffect, useMemo } from "react";
import { ChatMessage, applyChatStreamEvent, reconcileChatHistory, splitChatMessages, type ChatStreamEvent, type ChatResponder, type TaskContextPayload } from "@/lib/api/chatApi";
import { AgentAPI, attachLiveTurnStream, cancelTurnOnBackend, fetchLiveTurn, fetchSessionMessages, truncateMessagesFrom, normalizeStep, steerTurnOnBackend } from "@/lib/api/agentApi";
import { useAgentSettings } from "./useAgentSettings";
import { useChatSessions } from "./useChatSessions";
import { useModelProviders, resolveDefaultProvider } from "./useModelProviders";
import { humanizeError } from "@/lib/errorMessages";
import { ApiError } from "@/lib/api/client";
import { randomId } from "@/lib/utils";
import { nowHm } from "@/lib/format";
import { formatChatRefs, refKey, type ChatRef } from "@/lib/chatRefs";
import { useFileViewer } from "./useFileViewer";
import type { QueuedChatMessage } from "@/types";
import { toast } from "sonner";
import { useContextMeter } from "./useContextMeter";
import { parseChatCommand } from "@/lib/chatCommands";

const EMPTY_QUEUE: QueuedChatMessage[] = [];

function canContinueQueue(messages: ChatMessage[]): boolean {
  const last = [...messages].reverse().find(message => message.role === "assistant");
  return !last?.error && !last?.stopped && (!last?.turnMetrics?.status || last.turnMetrics.status === "completed");
}

interface UseChatOptions {
  initialMessages?: ChatMessage[];
  /** 初始输入框内容(如从数据源页「让 AI 帮我写 SQL」跳转预填);仅挂载时生效 */
  initialInput?: string;
  /** 初始引用(跨页「交给助手」交接:M2-02);仅挂载时生效 */
  initialRefs?: ChatRef[];
  /**
   * 挂载后自动发送 initialInput(2026-09-20 首页「开始新需求」)。
   * 仅当 initialInput 非空时生效,且只触发一次——发送后 URL 的 autosend
   * 参数由调用方移除,刷新不会重发。
   */
  autoSendInitial?: boolean;
/** 响应器：决定谁来回应用户消息（主对话 / 调试预览等场景） */
  responder?: ChatResponder;
  /** 传入时消息自动持久化到会话 store */
  sessionId?: string;
}

export function useChat({ initialMessages = [], initialInput = "", initialRefs = [], autoSendInitial = false, responder = AgentAPI.sendMessage, sessionId }: UseChatOptions = {}) {
  const [draft] = useState(() => useChatSessions.getState().sessions.find(session => session.id === sessionId)?.composerDraft);
  const [messages, setMessages] = useState<ChatMessage[]>(initialMessages);
  const conversation = useMemo(() => splitChatMessages(messages), [messages]);
  const [input, setInput] = useState(initialInput || draft?.input || "");
  /** 待发送引用(附件/文件/知识库文档;2026-09-17):发送时序列化进消息尾部。
   *  initialRefs:跨页「交给助手」交接(M2-02)预填的引用集合。 */
  const [refs, setRefs] = useState<ChatRef[]>(initialRefs.length ? initialRefs : draft?.refs ?? []);
  /**
   * 限定检索范围(B4,2026-09-27):开启后本轮自动检索只从**引用的知识库
   * 文档**里召回(docIds 进 RAG 范围查询),不混入范围外内容。
   * 关闭(默认)= 旧行为:引用内容排前,其余资料仍参与检索。
   */
  const [limitToRefs, setLimitToRefs] = useState(draft?.limitToRefs ?? false);
  const [isSending, setIsSending] = useState(false);
  const [isStopping, setIsStopping] = useState(false);
  const contextMeter = useContextMeter(messages, input, isSending);
  useEffect(() => { if (!isSending) setIsStopping(false); }, [isSending]);
  const [processingQueuedId, setProcessingQueuedId] = useState<string | null>(null);
  const queueBusyRef = useRef<string | null>(null);
  const [queueReady, setQueueReady] = useState(false);
  const queuedMessages = useChatSessions(state =>
    state.sessions.find(session => session.id === sessionId)?.queuedMessages ?? EMPTY_QUEUE);
  const queuePaused = useChatSessions(state => state.sessions.find(session => session.id === sessionId)?.queuePaused ?? false);
  const updateQueue = useCallback((update: (messages: QueuedChatMessage[]) => QueuedChatMessage[], paused?: boolean) => {
    if (sessionId) useChatSessions.getState().updateQueuedMessages(sessionId, update, paused);
  }, [sessionId]);
  const model = useModelProviders((s) => s.defaultModel);
  // 生效渠道:与后端 activeProvider(providerId, model) 同一回落顺序(显式 id → 按名)。
  // 返回基本类型避免每次渲染生成新引用导致无限重渲。
  const effectiveProviderId = useModelProviders(
    (s) => resolveDefaultProvider(s.providers, s.defaultModel, s.defaultProviderId)?.id ?? null
  );
  /** 对话框思考等级:全局覆写(持久);undefined = 跟随设置页该模型默认 */
  const reasoningLevel = useAgentSettings(s => s.reasoningLevelOverride);
  const setReasoningLevel = useAgentSettings(s => s.setReasoningLevelOverride);
  /** 权限模式 + 其余全局设置:后端设置表为真相 */
  const permissionMode = useAgentSettings((s) => s.permissionMode);
  const setPermissionMode = useAgentSettings((s) => s.setPermissionMode);
  const scrollRef = useRef<HTMLDivElement>(null);
  const mountedRef = useRef(true);
  const finishQueueOperation = useCallback((id: string) => {
    if (queueBusyRef.current !== id) return;
    queueBusyRef.current = null;
    if (mountedRef.current) setProcessingQueuedId(null);
  }, []);
  /** 本地流连接句柄；后端取消与连接断开分开处理。 */
  const abortRef = useRef<AbortController | null>(null);
  /**
   * 接续流(切页返回恢复进行中轮次)的句柄。生命周期独立于恢复 effect 的重跑:
   * 放 effect cleanup 里会被本 effect 自身的 setIsSending(true) 重跑连带拆掉
   * ——接上即断,终态永远收不到,UI 卡死「正在思考」且「停止生成」失效。
   * 只在卸载/切会话时统一断开(见下方独立 effect)。
   */
  const recoverRef = useRef<{ detach: () => void } | null>(null);
  /** 接续流是否已接上(守卫重跑:接续中不重复探测、不重挂)。 */
  const recoverActiveRef = useRef(false);
  /** 停止请求进行中：阻止重复取消与插入补充要求。 */
  const stopRequestedRef = useRef(false);
  const composerRef = useRef({ input, refs, limitToRefs });
  useEffect(() => { composerRef.current = { input, refs, limitToRefs }; }, [input, refs, limitToRefs]);
  useEffect(() => {
    if (!sessionId) return;
    const timer = setTimeout(() => useChatSessions.getState().saveComposerDraft(sessionId, composerRef.current), 500);
    return () => clearTimeout(timer);
  }, [sessionId, input, refs, limitToRefs]);
  useEffect(() => {
    const save = () => { if (sessionId) useChatSessions.getState().saveComposerDraft(sessionId, composerRef.current); };
    window.addEventListener("pagehide", save);
    return () => { window.removeEventListener("pagehide", save); save(); };
  }, [sessionId]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      // 卸载(切会话/切页)只断开本地事件流:后端编排线程继续跑,事件进
      // TurnStreamRegistry 缓冲;回到本会话时由 live-turn 恢复逻辑回放接续。
      // 这里不再 abort——中断等于把进行中的轮次杀掉(旧实现的不可恢复问题)。
      // 真正的「停止生成」走 stopGenerating，等待服务端收尾。
    };
  }, []);

  // 卸载/切会话:断开接续流。服务端轮次不受影响(事件继续进缓冲),
  // 回到本会话时由恢复逻辑重新探测接续。
  useEffect(() => {
    return () => {
      recoverRef.current?.detach();
      recoverRef.current = null;
    };
  }, [sessionId]);

  const scrollToBottom = () => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages]);

  // 流式界面即时更新；离线缓存每 500ms 写一次，终态、切页与退出立即写回。
  const lastSavedRef = useRef<ChatMessage[] | null>(null);
  const pendingCacheRef = useRef<ChatMessage[] | null>(null);
  const cacheTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const flushCache = useCallback(() => {
    if (cacheTimerRef.current) clearTimeout(cacheTimerRef.current);
    cacheTimerRef.current = null;
    if (!sessionId || !pendingCacheRef.current) return;
    const persistable = pendingCacheRef.current.map(({ approval, question, isTyping, startedAtMs, ...rest }) => rest);
    pendingCacheRef.current = null;
    lastSavedRef.current = persistable;
    useChatSessions.getState().saveMessages(sessionId, persistable);
  }, [sessionId]);
  useEffect(() => {
    if (!sessionId) return;
    pendingCacheRef.current = messages;
    if (!messages.some(message => message.isTyping)) flushCache();
    else if (!cacheTimerRef.current) cacheTimerRef.current = setTimeout(flushCache, 500);
  }, [messages, sessionId, flushCache]);
  useEffect(() => {
    window.addEventListener("pagehide", flushCache);
    return () => { window.removeEventListener("pagehide", flushCache); flushCache(); };
  }, [flushCache]);

  // 历史加载收编:loadHistory 晚于挂载完成时,useState 快照不会跟进 store 更新
  // (刷新/切会话后对话区永远空态的根因)。监听 store 中本会话消息:引用变化
  // 且非本 hook 刚写回的数组(回声)、且当前不在流式中 → 以后端历史为准整表替换。
  // 回声判定必须读 store 现值(getState)而非闭包 sessionMessages:回合收尾的
  // 同一 commit 里,persist effect(先声明)已把终态写入 store 并更新 lastSavedRef,
  // 而闭包 sessionMessages 还是渲染时的中间快照——拿旧引用比对会误判为「外部
  // 更新」,把停止前的快照反灌回来(stopped 丢失、推理步骤卡 running、「已停止」
  // 与 meta 行消失)。Zustand set 同步生效,effect 按声明顺序执行,getState 必能
  // 看到 persist 刚写入的数组。晚到的落后 loadHistory 快照在 store 层已被
  // 新鲜度闸门(useChatSessions)丢弃,不会到这里。
  const sessionMessages = useChatSessions((s) =>
    sessionId ? s.sessions.find((x) => x.id === sessionId)?.messages : undefined
  );
  useEffect(() => {
    if (isSending || abortRef.current || recoverActiveRef.current) return; // 同一提交中的自动发送也不能被历史覆盖
    if (!sessionMessages) return;
    const current = useChatSessions.getState().sessions.find((x) => x.id === sessionId)?.messages;
    if (!current || current === lastSavedRef.current) return; // 自己写回的回声,忽略
    setMessages(prev => reconcileChatHistory(prev, current));
  }, [sessionMessages, isSending, sessionId]);

  const updateMessage = useCallback((id: string, partial: Partial<ChatMessage>) => {
    if (!mountedRef.current) return; // 卸载后忽略流式更新
    setMessages((prev) => prev.map((msg) => (msg.id === id ? { ...msg, ...partial } : msg)));
  }, []);
  const updateStream = useCallback((id: string, event: ChatStreamEvent) => {
    if (!mountedRef.current) return;
    setMessages(prev => prev.map(message => message.id === id ? { ...message, ...applyChatStreamEvent(message, event) } : message));
  }, []);

  /**
   * 进行中轮次恢复:刷新/切页回来时,后端那一轮还在跑(事件进 TurnStreamRegistry
   * 缓冲)。探测到 live 轮就接上事件流,重建一条流式中的 assistant 消息;
   * done 后由持久化写回自然收敛进会话 store。
   * 仅本会话无本地流式时执行(避免与挂载中的流双开)。
   */
  useEffect(() => {
    if (!sessionId) return;
    if (isSending || abortRef.current) return; // 本地流式已开始，不能再用恢复占位覆盖
    if (recoverActiveRef.current) return; // 已接续中:接续流生命周期独立,不重复探测
    let cancelled = false;

    (async () => {
      // 串行等 loadHistory 先落地(激活会话时异步拉取):恢复占位消息写进
      // 本地 messages 后,loadHistory 的全量替换会把它冲掉,后续 delta 全部
      // 落空——固定 sleep 是赌运气,这里直接等它真正完成(300ms 兜底防它
      // 因守卫提前 return)
      await Promise.race([
        useChatSessions.getState().loadHistory(sessionId),
        new Promise((r) => setTimeout(r, 300)),
      ]);
      if (cancelled || abortRef.current) return;
      let info;
      try {
        info = await fetchLiveTurn(sessionId);
      } catch {
        return; // 后端不可达:按无轮次处理
      }
      if (cancelled || abortRef.current) return;
      if (!info?.running) {
        // 轮次可能刚好在历史加载与 live 探测之间结束，不能留下运行中的旧缓存。
        try {
          const history = await fetchSessionMessages(sessionId, true);
          if (!cancelled && !abortRef.current) {
            setMessages(prev => reconcileChatHistory(prev, history));
            setQueueReady(canContinueQueue(history));
          }
        } catch { /* 网络失败时保留当前历史。 */ }
        return;
      }
      // 双保险:store 里这条会话若已有流式中的消息(理论不可能,挂载即无),跳过
      const recoverId = `live-${sessionId}`;
      recoverActiveRef.current = true;
      setMessages((prev) => [
        ...prev.filter((m) => m.id !== recoverId),
        {
          id: recoverId,
          role: "assistant",
          content: info.operation === "compact" ? info.content ?? "/compact" : "",
          sender: info.operation === "compact" ? "compaction" : undefined,
          timestamp: nowHm(),
          isTyping: true,
          startedAtMs: info.startedAtMs ?? Date.now(),
          steps: [],
        },
      ]);
      setIsSending(true);
      const reloadRecoveredHistory = () => {
        // 接续结束必须绕过运行中缓存；保留已重建的消息，直到权威历史真正返回。
        void fetchSessionMessages(sessionId, true).then(history => {
          if (!mountedRef.current) return;
          setMessages(prev => [...prev].reverse().find(message => message.role === "assistant")?.id === recoverId
            ? reconcileChatHistory(prev, history) : prev);
          setQueueReady(canContinueQueue(history));
        }).catch(() => { /* 网络失败时保留接续中已显示的内容。 */ });
      };
      // 接续流句柄存 ref(而非 effect 局部变量):本 effect 会因 isSending 变化
      // 重跑,局部变量+cleanup detach 会让刚接上的流被自身重跑立刻拆掉。
      recoverRef.current = { detach: attachLiveTurnStream(sessionId, {
        onStep: (s) => {
          updateStream(recoverId, { type: "step", step: normalizeStep(s, 0) });
        },
        onDelta: (c, roundIndex) => {
          updateStream(recoverId, { type: "delta", content: c, roundIndex });
        },
        onReasoningDelta: (roundIndex, c) => {
          updateStream(recoverId, { type: "reasoning_delta", content: c, roundIndex });
        },
        onSources: (citations) => {
          updateStream(recoverId, { type: "sources", sources: citations });
        },
        onContextUsage: (contextUsage) => {
          updateStream(recoverId, { type: "context_usage", contextUsage });
        },
        onApproval: (approval) => {
          updateStream(recoverId, { type: "approval_required", approval });
        },
        onQuestion: (question) => {
          updateStream(recoverId, { type: "question_required", question });
        },
        onDone: (p) => {
          // 轮次结束后保留接续内容，再用已落库历史收敛终态。
          recoverActiveRef.current = false;
          recoverRef.current = null;
          const stopped = !!p?.stopped;
          updateStream(recoverId, { type: "finish", stopped, turnMetrics: p ? {
            durationMs: p.durationMs, usage: p.usage ?? null, promptTokens: p.promptTokens,
            contextWindow: p.contextWindow, ttftMs: p.ttftMs, status: p.status,
            stopReason: p.stopReason, hadToolErrors: p.hadToolErrors,
          } : undefined });
          // 取消轮保留已流出的内容和本地 stopped 标记，服务端历史没有此标记。
          if (!stopped) reloadRecoveredHistory();
          setQueueReady(!stopped && (!p?.status || p.status === "completed"));
          updateQueue(queue => queue, stopped || (!!p?.status && p.status !== "completed"));
          setIsSending(false);
        },
        onError: (msg) => {
          recoverActiveRef.current = false;
          recoverRef.current = null;
          const friendly = humanizeError(msg);
          updateStream(recoverId, { type: "finish", failed: true });
          updateMessage(recoverId, {
            error: friendly.message,
            errorRaw: friendly.raw,
            isTyping: false,
          });
          setQueueReady(false);
          updateQueue(queue => queue, true);
          setIsSending(false);
        },
        onIdle: () => {
          // 探测到 live 但连接时已结束：强制拉到最新落库消息。
          recoverActiveRef.current = false;
          recoverRef.current = null;
          updateStream(recoverId, { type: "finish" });
          reloadRecoveredHistory();
          setIsSending(false);
        },
        onGap: () => {
          // 回放缺口(2026-09-20):中间事件已被服务端滚动窗口淘汰,已流出的
          // 增量内容不完整——清空占位内容/步骤,只保留 typing 态等 done 后
          // 从权威消息状态恢复(避免把残缺片段拼成一条"完整"回答)。
          updateStream(recoverId, { type: "gap" });
        },
      }) };
    })();

    return () => {
      // 只标记取消,不断开接续流:本 effect 会因 isSending 变化重跑,
      // 若在此 detach,刚接上的流会被自身重跑立刻拆掉——终态永远收不到,
      // UI 卡「正在思考」且「停止生成」失效(实测 bug)。接续流的断开由
      // sessionId 切换/卸载的独立 effect 与终态回调负责。
      cancelled = true;
    };
  }, [sessionId, isSending, processingQueuedId, updateMessage, updateStream, updateQueue]);

  /** 核心发送逻辑:复用于 sendMessage 与 regenerate */
  const runTurn = useCallback(
    async (content: string, assistantMsgId: string, context?: TaskContextPayload, onAccepted?: () => void, compactId?: string) => {
      stopRequestedRef.current = false;
      const controller = new AbortController();
      abortRef.current = controller;
      setQueueReady(false);
      updateQueue(queue => queue, false);
      setIsSending(true);
      let accepted = false;
      let completed = false;
      let terminalStatus: string | undefined;
      if (sessionId) useFileViewer.getState().beginRun(assistantMsgId, sessionId);
      try {
        await (compactId ? AgentAPI.compact(compactId) : responder)(
          content,
          (partial) => {
            if (!accepted) { accepted = true; onAccepted?.(); }
            if (partial.turnMetrics?.status) terminalStatus = partial.turnMetrics.status;
            updateMessage(assistantMsgId, partial);
            if (mountedRef.current && sessionId === useChatSessions.getState().activeId) {
              for (const step of partial.steps ?? []) {
                if ((step.status === "completed" || step.status === "partial") && step.result?.files?.length) {
                  useFileViewer.getState().receiveFiles(assistantMsgId, step.id, step.result.files, step.result.focusTarget);
                }
              }
            }
          },
          sessionId,
          model === "未配置" ? undefined : model,
          reasoningLevel,
          permissionMode,
          controller.signal,
          model === "未配置" ? undefined : effectiveProviderId ?? undefined,
          context
        );
        completed = !controller.signal.aborted && (!terminalStatus || terminalStatus === "completed");
        if (completed && !accepted) onAccepted?.();
        // responder 正常返回:用户停止时保留部分文本并标记 stopped
        if (controller.signal.aborted && mountedRef.current) {
          updateStream(assistantMsgId, { type: "finish", stopped: true });
        }
      } catch (error) {
        if (!mountedRef.current) return;
        // 用户主动 abort 不算错误(部分文本保留)
        if (controller.signal.aborted) {
          updateStream(assistantMsgId, { type: "finish", stopped: true });
          return;
        }
        // 直接展示原始错误。
        const friendly = humanizeError(error);
        updateStream(assistantMsgId, { type: "finish", failed: true });
        updateMessage(assistantMsgId, {
          error: friendly.message,
          errorRaw: friendly.raw,
          isTyping: false,
        });
      } finally {
        if (abortRef.current === controller) abortRef.current = null;
        if (compactId && sessionId) {
          try {
            const history = await fetchSessionMessages(sessionId, true);
            if (mountedRef.current && history.some(message => message.id === assistantMsgId)) {
              setMessages(prev => reconcileChatHistory(prev, history));
            }
          } catch { /* 保留已经收到的事件，切回会话时重新同步。 */ }
        }
        if (mountedRef.current) {
          setQueueReady(completed);
          updateQueue(queue => queue, !completed);
          setIsSending(false);
        }
        // 轮次结束后兜底拉 AI 标题：title 事件是旁路任务，短轮次常在 done 之后
        // 才回来（emitter 已 complete），事件会丢。这里轮询补一次。
        if (sessionId && !compactId) void useChatSessions.getState().awaitGeneratedTitle(sessionId);
        void useFileViewer.getState().refresh(true);
        // 轮次结束批量校验所有打开的标签:agent 本轮可能删/移了文件,
        // 失效标签即时标记,不等用户点刷新才发现
        void useFileViewer.getState().validateTabs();
      }
    },
    [responder, sessionId, model, reasoningLevel, permissionMode, updateMessage, updateStream, effectiveProviderId, updateQueue]
  );

  /** 引用增删(去重:同引用只保留一条;mcp 以工具全名为键) */
  const addRef = useCallback((ref: ChatRef) => {
    setRefs((prev) => (prev.some((r) => refKey(r) === refKey(ref)) ? prev : [...prev, ref]));
  }, []);
  const removeRef = useCallback((key: string) => {
    setRefs((prev) => prev.filter((r) => refKey(r) !== key));
  }, []);
  const pendingReference = useFileViewer(state => state.pendingReference);
  useEffect(() => {
    if (!sessionId || pendingReference?.sessionId !== sessionId) return;
    addRef(pendingReference.ref);
    useFileViewer.setState({ pendingReference: null });
  }, [pendingReference, sessionId, addRef]);

  /** 拼引用后的实际发送内容:正文 + 引用块(尾部,持久化后历史仍可解析) */
  const composeWithRefs = useCallback(
    (content: string, pending: ChatRef[]) => {
      const block = formatChatRefs(pending);
      return block ? `${content}\n\n${block}` : content;
    },
    []
  );

  /** 待发送引用 → 结构化上下文(M2-01);无引用返回 undefined(请求不带该字段)。 */
  const contextFromRefs = useCallback((pending: ChatRef[], restrict = limitToRefs): TaskContextPayload | undefined => {
    // B4(2026-09-27):「限定检索」开启且有知识库文档引用时,附检索范围——
    // 后端据此只从这些文档召回(docIds 同时进入向量与关键词两路)。
    const scopedDocIds = restrict
      ? pending.filter((r) => r.kind === "doc").map((r) => Number(r.id))
      : [];
    if (pending.length === 0 && scopedDocIds.length === 0) return undefined;
    return {
      version: 1,
      refs: pending.map((r) => ({ kind: r.kind, id: String(r.id), label: r.name })),
      ...(scopedDocIds.length > 0
        ? { retrievalScope: { docIds: scopedDocIds } }
        : {}),
    };
  }, [limitToRefs]);

  const appendUserMessage = useCallback(async (content: string, context?: TaskContextPayload, queuedId?: string) => {
    const userMsg: ChatMessage = {
      id: queuedId ?? randomId(),
      role: "user",
      content,
      timestamp: nowHm(),
      taskContext: context,
    };
    const assistantMsgId = randomId();

    setMessages((prev) => [
      ...prev,
      userMsg,
      {
        id: assistantMsgId,
        role: "assistant",
        content: "",
        timestamp: nowHm(),
        isTyping: true,
        startedAtMs: Date.now(),
        taskContext: context,
      },
    ]);
    await runTurn(content, assistantMsgId, context, queuedId ? () => {
      updateQueue(queue => queue.filter(message => message.id !== queuedId));
      finishQueueOperation(queuedId);
    } : undefined);
  }, [runTurn, updateQueue, finishQueueOperation]);

  const processQueuedMessage = useCallback(async (messageId: string) => {
    if (!sessionId || queueBusyRef.current) return;
    const message = useChatSessions.getState().sessions.find(session => session.id === sessionId)
      ?.queuedMessages?.find(item => item.id === messageId);
    if (!message) return;
    queueBusyRef.current = messageId;
    setProcessingQueuedId(messageId);
    setQueueReady(false);
    try {
      if (message.kind === "compact") {
        if (isSending || abortRef.current || recoverActiveRef.current) return;
        const queue = useChatSessions.getState().sessions.find(session => session.id === sessionId)?.queuedMessages;
        if (queue?.[0]?.id !== message.id) return;
        const live = await fetchLiveTurn(sessionId);
        if (live.running || !mountedRef.current) return;
        const assistantId = `compact-${message.id}`;
        setMessages(previous => [...previous.filter(item => item.id !== assistantId), {
          id: assistantId, role: "assistant", sender: "compaction", content: message.content,
          timestamp: nowHm(), isTyping: true, startedAtMs: Date.now(), steps: [],
        }]);
        await runTurn(parseChatCommand(message.content)?.argument ?? "", assistantId, undefined, () => {
          updateQueue(queue => queue.filter(item => item.id !== messageId));
          finishQueueOperation(messageId);
        }, message.id);
        return;
      }
      const content = composeWithRefs(message.content, message.refs);
      if (!message.insertTurnId && !isSending && !abortRef.current && !recoverActiveRef.current) {
        const live = await fetchLiveTurn(sessionId);
        if (live.running || !mountedRef.current) return;
        await appendUserMessage(content, contextFromRefs(message.refs, message.limitToRefs), messageId);
        return;
      }
      let turnId = message.insertTurnId;
      if (!turnId) {
        const live = await fetchLiveTurn(sessionId);
        if (!live.running || !live.turnId || stopRequestedRef.current) throw new Error("The turn has finished");
        if (live.operation === "compact") throw new Error("压缩期间请等待排队消息自动发送");
        turnId = live.turnId;
        updateQueue(queue => queue.map(item => item.id === messageId ? { ...item, insertTurnId: turnId } : item));
      }
      try {
        await steerTurnOnBackend(sessionId, turnId, messageId, content);
      } catch (error) {
        // 明确拒绝可以编辑；丢失响应只能使用同一 ID 确认，不能假装撤回成功。
        if (error instanceof ApiError && error.code >= 400 && error.code < 500 && error.code !== 408) updateQueue(queue => queue.map(item => item.id === messageId
          ? { ...item, insertTurnId: undefined } : item));
        throw error;
      }
      updateQueue(queue => queue.filter(item => item.id !== messageId));
      if (!mountedRef.current) return;
      const id = `steer-${turnId}-${messageId}`;
      setMessages((prev) => {
        if (prev.some(m => m.id === id)) return prev;
        const user: ChatMessage = { id, role: "user", sender: "steering", content, timestamp: nowHm() };
        return [...prev, user];
      });
    } catch (error) {
      if (mountedRef.current) toast.error(humanizeError(error).message);
    } finally {
      finishQueueOperation(messageId);
    }
  }, [sessionId, isSending, composeWithRefs, contextFromRefs, appendUserMessage, updateQueue, finishQueueOperation, runTurn]);

  useEffect(() => {
    if (!queueReady || queuePaused || isSending || abortRef.current || recoverActiveRef.current || queueBusyRef.current) return;
    const next = queuedMessages[0];
    if (next && !next.insertTurnId) void processQueuedMessage(next.id);
  }, [queueReady, queuePaused, isSending, queuedMessages, processingQueuedId, processQueuedMessage]);

  const removeQueuedMessage = useCallback((id: string) => {
    updateQueue(queue => queue.filter(message => message.id !== id || !!message.insertTurnId || processingQueuedId === id));
  }, [updateQueue, processingQueuedId]);

  const restoreQueuedMessage = useCallback((id: string) => {
    const message = queuedMessages.find(item => item.id === id);
    if (!message || message.insertTurnId || processingQueuedId === id) return;
    updateQueue(queue => queue.filter(item => item.id !== id));
    setInput(current => [message.content, current].filter(value => value.trim()).join("\n\n"));
    setRefs(current => [...message.refs, ...current.filter(ref => !message.refs.some(item => refKey(item) === refKey(ref)))]);
    setLimitToRefs(current => message.limitToRefs || current);
  }, [queuedMessages, processingQueuedId, updateQueue]);

  const queueMessage = useCallback((content: string, pendingRefs: ChatRef[] = [], restrict = false) => {
    if (!sessionId) throw new Error("Message queue requires a sessionId");
    updateQueue(queue => [...queue, { id: randomId(), content, refs: pendingRefs, limitToRefs: restrict }]);
  }, [sessionId, updateQueue]);

  const queueCompaction = useCallback((content: string) => {
    const argument = parseChatCommand(content)?.argument ?? "";
    if (!sessionId) throw new Error("压缩需要当前会话");
    if (argument.length > 4000) throw new Error("focus exceeds 4000 characters");
    updateQueue(queue => [...queue, { id: randomId(), kind: "compact", content, refs: [], limitToRefs: false }], false);
    if (!isSending && !abortRef.current && !recoverActiveRef.current) setQueueReady(true);
  }, [sessionId, isSending, updateQueue]);

  const sendMessage = useCallback(async () => {
    if (!input.trim() && refs.length === 0) return;
    if (parseChatCommand(input)?.command.name === "compact") {
      try { queueCompaction(input.trim()); setInput(""); }
      catch (error) { toast.error(humanizeError(error).message); }
      return;
    }
    const content = composeWithRefs(input.trim(), refs);
    if (content.length > 8000) { toast.error("message exceeds 8000 characters"); return; }
    if (isSending || abortRef.current || recoverActiveRef.current || queueBusyRef.current || queuedMessages.length > 0) {
      try {
        queueMessage(input.trim(), refs, limitToRefs);
        setInput(current => current === input ? "" : current);
        setRefs(current => current === refs ? [] : current);
        setLimitToRefs(false);
        if (!isSending && !abortRef.current && !recoverActiveRef.current) {
          updateQueue(queue => queue, false);
          setQueueReady(true);
        }
      } catch (error) {
        toast.error(humanizeError(error).message);
      }
      return;
    }
    const context = contextFromRefs(refs);
    setInput(""); setRefs([]); setLimitToRefs(false);
    await appendUserMessage(content, context);
  }, [input, refs, limitToRefs, isSending, queuedMessages.length, composeWithRefs, contextFromRefs, appendUserMessage, queueMessage, updateQueue, queueCompaction]);

  // 任务卡发出的是用户的明确选择，保留聊天输入区尚未发送的草稿和引用。
  const sendTaskMessage = useCallback(async (content: string) => {
    if (isSending || abortRef.current || recoverActiveRef.current || queuedMessages.length > 0) {
      queueMessage(content);
      if (!isSending && !abortRef.current && !recoverActiveRef.current) {
        updateQueue(queue => queue, false);
        setQueueReady(true);
      }
    } else await appendUserMessage(content);
  }, [isSending, queuedMessages.length, appendUserMessage, queueMessage, updateQueue]);

  // 自动发送(2026-09-20 首页「开始新需求」):挂载后把 initialInput 直接发出。
  // 三重防重:①ref 守卫(StrictMode 双挂载不重发);②发送即把 URL 的
  // autosend/prompt 清掉——组件因切会话重挂载时读实时 URL 已无 autosend,不再发;
  // ③发送后输入框与引用清空,即使有漏网重挂载也发不出去(空输入守卫)。
  const autoSentRef = useRef(false);
  useEffect(() => {
    if (!autoSendInitial || autoSentRef.current) return;
    const stillArmed = (() => {
      try {
        return new URLSearchParams(window.location.search).get("autosend") === "1";
      } catch {
        return false;
      }
    })();
    if (!stillArmed) return;
    if (!initialInput.trim() && initialRefs.length === 0) return;
    autoSentRef.current = true;
    void sendMessage();
    try {
      const url = new URL(window.location.href);
      url.searchParams.delete("autosend");
      url.searchParams.delete("prompt");
      window.history.replaceState({}, "", url.toString());
    } catch {
      /* URL 处理失败不影响发送 */
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 仅挂载时触发一次
  }, []);

  /**
   * 重试追加新轮，保留失败轮，与服务端的追加历史一致。
   */
  const retryMessage = useCallback(
    async (failedMsgId: string) => {
      if (isSending) return;
      const idx = conversation.findIndex((m) => m.id === failedMsgId);
      if (idx >= 0 && conversation[idx].sender === "compaction") {
        queueCompaction(conversation[idx].content || "/compact");
        return;
      }
      if (idx <= 0) return;
      const userMsg = conversation[idx - 1];
      if (userMsg?.role !== "user") return;
      const message = conversation[idx];
      const content = message.turnMetrics?.status === "interrupted"
        ? "继续刚才因服务重启中断的任务，沿用原要求和补充要求，保留已完成结果。先用只读操作核实结果未知的调用，不要自动重复执行；然后继续完成剩余工作。"
        : userMsg.content;

      setIsSending(true);
      try {
        if (sessionId) {
          const history = await fetchSessionMessages(sessionId, true);
          if (!mountedRef.current) return;
          setMessages(prev => reconcileChatHistory(prev, history));
        }
        await appendUserMessage(content, message.taskContext ?? userMsg.taskContext);
      } catch (failure) {
        const friendly = humanizeError(failure);
        updateMessage(failedMsgId, { error: friendly.message, errorRaw: friendly.raw });
      } finally {
        if (mountedRef.current) setIsSending(false);
      }
    },
    [conversation, isSending, sessionId, appendUserMessage, updateMessage, queueCompaction]
  );

  /** 取消受理后保持流连接，真实终态到达才解除发送占位。 */
  const stopGenerating = useCallback(async () => {
    if (stopRequestedRef.current || !isSending) return;
    stopRequestedRef.current = true;
    setIsStopping(true);
    updateQueue(queue => queue, true);
    setQueueReady(false);
    if (!sessionId) { abortRef.current?.abort(); return; }
    try {
      await cancelTurnOnBackend(sessionId);
    } catch (error) {
      stopRequestedRef.current = false;
      if (mountedRef.current) setIsStopping(false);
      toast.error(humanizeError(error).message);
    }
  }, [sessionId, isSending, updateQueue]);

  /**
   * 编辑重发:回退到某条用户消息,内容替换为 edited 后重新发送。
   * 服务端成功截断后才替换本地分支，失败保留历史和用户编辑稿。
   */
  const editAndResend = useCallback(
    async (userMsgId: string, edited: string) => {
      if (isSending || !edited.trim()) return;
      const idx = conversation.findIndex((m) => m.id === userMsgId);
      if (idx < 0 || conversation[idx].role !== "user") return;

      setIsSending(true);
      try {
        if (sessionId) {
          const history = await fetchSessionMessages(sessionId, true);
          const aligned = history.length === conversation.length && conversation.every((message, index) =>
            message.role === history[index].role && (message.role !== "user" || message.content === history[index].content));
          if (!aligned) throw new ApiError({ code: 409, category: "CONFLICT", message: "会话历史已更新",
            hint: "会话历史已更新，请刷新后再编辑。当前编辑内容已保留。" });
          await truncateMessagesFrom(sessionId, idx);
        }
        if (!mountedRef.current) return;
        const assistantMsgId = randomId();
        setMessages([
          ...conversation.slice(0, idx),
          { id: randomId(), role: "user", content: edited.trim(), timestamp: nowHm() },
          { id: assistantMsgId, role: "assistant", content: "", timestamp: nowHm(), isTyping: true, startedAtMs: Date.now() },
        ]);
        await runTurn(edited.trim(), assistantMsgId);
      } finally {
        if (mountedRef.current) setIsSending(false);
      }
    },
    [conversation, isSending, sessionId, runTurn]
  );

  const clear = useCallback(() => {
    setMessages([]);
    updateQueue(() => []);
  }, [updateQueue]);

  return {
    messages: conversation,
    input,
    setInput: (value: string) => { composerRef.current = { ...composerRef.current, input: value }; setInput(value); },
    /** 待发送引用(📎附件/📄文件/@知识库) */
    refs,
    addRef,
    removeRef,
    /** 限定检索范围(B4):开启后本轮只从引用的知识文档召回 */
    limitToRefs,
    setLimitToRefs,
    isSending,
    isCompacting: isSending && messages.at(-1)?.sender === "compaction",
    isStopping,
    contextMeter,
    queuedMessages,
    processingQueuedId,
    insertQueuedMessage: processQueuedMessage,
    removeQueuedMessage,
    restoreQueuedMessage,
    sendMessage,
    sendTaskMessage,
    scrollRef,
    clear,
    reasoningLevel,
    setReasoningLevel,
    permissionMode,
    setPermissionMode,
    stopGenerating,
    retryMessage,
    editAndResend,
  };
}
