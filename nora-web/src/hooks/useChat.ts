import { useState, useCallback, useRef, useEffect, useMemo } from "react";
import { ChatMessage, applyChatStreamEvent, createChatStreamBuffer, reconcileChatHistory, splitChatMessages, type ChatStreamEvent, type ChatResponder, type TaskContextPayload } from "@/lib/api/chatApi";
import { AgentAPI, attachLiveTurnStream, cancelTurnOnBackend, fetchLiveTurn, fetchSessionMessages, truncateMessagesFrom, steerTurnOnBackend, fetchChatQueue, enqueueChatInput, withdrawChatInput, insertChatInput, resumeChatQueue } from "@/lib/api/agentApi";
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

const EMPTY_MESSAGES: ChatMessage[] = [];
const EMPTY_QUEUE: QueuedChatMessage[] = [];

interface UseChatOptions {
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
  sessionId: string;
}

export function useChat({ initialInput = "", initialRefs = [], autoSendInitial = false, responder = AgentAPI.sendMessage, sessionId }: UseChatOptions) {
  const [draft] = useState(() => useChatSessions.getState().sessions.find(session => session.id === sessionId)?.composerDraft);
  const messages = useChatSessions(state => state.messagesBySession[sessionId] ?? EMPTY_MESSAGES);
  const syncingSessions = useChatSessions(state => state.syncing);
  const setMessages = useCallback((update: ChatMessage[] | ((previous: ChatMessage[]) => ChatMessage[])) => {
    useChatSessions.getState().saveMessages(sessionId, update);
  }, [sessionId]);
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
  const [queueRevision, setQueueRevision] = useState(0);
  const queuedMessages = useChatSessions(state =>
    state.sessions.find(session => session.id === sessionId)?.queuedMessages ?? EMPTY_QUEUE);
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
  const pendingQueueRef = useRef(draft?.pendingInput);
  const composerRef = useRef({ input, refs, limitToRefs, pendingInput: draft?.pendingInput });
  useEffect(() => { composerRef.current = { input, refs, limitToRefs, pendingInput: pendingQueueRef.current }; }, [input, refs, limitToRefs]);
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
      // 服务端执行独立于 HTTP 连接；切页仅释放本地流，取消仍走 cancel 接口。
      abortRef.current?.abort();
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

  const updateMessage = useCallback((id: string, partial: Partial<ChatMessage>) => {
    if (!mountedRef.current) return; // 卸载后忽略流式更新
    setMessages((prev) => prev.map((msg) => (msg.id === id ? { ...msg, ...partial } : msg)));
  }, [setMessages]);
  const updateStream = useCallback((id: string, event: ChatStreamEvent) => {
    if (!mountedRef.current) return;
    setMessages(prev => prev.map(message => message.id === id ? { ...message, ...applyChatStreamEvent(message, event) } : message));
    // 只处理新交付；文本增量和历史快照不重复遍历、打开文件。
    if (event.type === "step" && sessionId === useChatSessions.getState().activeId) {
      const step = event.step;
      if ((step.status === "completed" || step.status === "partial") && step.result?.files?.length) {
        useFileViewer.getState().receiveFiles(id, step.id, step.result.files, step.result.focusTarget);
      }
    }
  }, [sessionId, setMessages]);

  /**
   * 进行中轮次恢复:刷新/切页回来时,后端那一轮还在跑(事件进 TurnStreamRegistry
   * 缓冲)。探测到 live 轮就接上事件流,重建一条流式中的 assistant 消息;
   * done 后用服务端历史收敛会话 store 中的消息。
   * 仅本会话无本地流式时执行(避免与挂载中的流双开)。
   */
  useEffect(() => {
    if (!sessionId) return;
    if (syncingSessions) return;
    if (isSending || abortRef.current) return; // 本地流式已开始，不能再用恢复占位覆盖
    if (recoverActiveRef.current) return; // 已接续中:接续流生命周期独立,不重复探测
    let cancelled = false;
    let probing = false;
    let retryTimer: ReturnType<typeof setTimeout>;

    const recover = async () => {
      if (cancelled || probing) return;
      clearTimeout(retryTimer);
      probing = true;
      const previous = useChatSessions.getState().messagesBySession[sessionId];
      let info;
      let history;
      try {
        info = await fetchLiveTurn(sessionId);
        history = await fetchSessionMessages(sessionId, true);
      } catch {
        if (!cancelled) retryTimer = setTimeout(() => { void recover(); }, 2000);
        return;
      } finally {
        probing = false;
      }
      if (cancelled || abortRef.current || useChatSessions.getState().messagesBySession[sessionId] !== previous) return;
      if (!info?.running) {
        setMessages(prev => reconcileChatHistory(prev, history));

        return;
      }
      const recoverId = `live-${sessionId}`;
      recoverActiveRef.current = true;
      stopRequestedRef.current = false;
      setMessages([
        ...history,
        {
          id: recoverId,
          role: "assistant",
          content: info.operation === "compact" ? info.content ?? "/compact" : "",
          sender: info.operation === "compact" ? "compaction" : undefined,
          timestamp: new Date(info.startedAtMs ?? Date.now()).toTimeString().slice(0, 5),
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

        }).catch(() => { /* 网络失败时保留接续中已显示的内容。 */ });
      };
      // 接续流句柄存 ref(而非 effect 局部变量):本 effect 会因 isSending 变化
      // 重跑,局部变量+cleanup detach 会让刚接上的流被自身重跑立刻拆掉。
      const buffer = createChatStreamBuffer(event => updateStream(recoverId, event));
      if (sessionId === useChatSessions.getState().activeId) useFileViewer.getState().beginRun(recoverId, sessionId);
      const release = () => {
        recoverActiveRef.current = false;
        recoverRef.current = null;
        setIsSending(false);
      };
      const detach = attachLiveTurnStream(sessionId, {
        onEvent: buffer.push,
        onDone: stopped => {
          release();
          // 取消轮保留本地 stopped 标记，其余终态用权威历史收敛。
          if (!stopped) reloadRecoveredHistory();
        },
        onError: error => {
          buffer.push({ type: "error", error });
          buffer.push({ type: "finish", failed: true });
          release();
        },
        onIdle: () => {
          buffer.push({ type: "finish" });
          reloadRecoveredHistory();
          release();
        },
      });
      recoverRef.current = { detach: () => { buffer.flush(); detach(); } };
    };
    const reconnect = () => { void recover(); };
    reconnect();
    window.addEventListener("online", reconnect);

    return () => {
      // 只标记取消,不断开接续流:本 effect 会因 isSending 变化重跑,
      // 若在此 detach,刚接上的流会被自身重跑立刻拆掉——终态永远收不到,
      // UI 卡「正在思考」且「停止生成」失效(实测 bug)。接续流的断开由
      // sessionId 切换/卸载的独立 effect 与终态回调负责。
      cancelled = true;
      clearTimeout(retryTimer);
      window.removeEventListener("online", reconnect);
    };
  }, [sessionId, isSending, processingQueuedId, queueRevision, updateStream, setMessages, syncingSessions]);

  /** 核心发送逻辑:复用于 sendMessage 与 regenerate */
  const runTurn = useCallback(
    async (content: string, assistantMsgId: string, context?: TaskContextPayload) => {
      stopRequestedRef.current = false;
      const controller = new AbortController();
      abortRef.current = controller;

      setIsSending(true);
      if (sessionId) useFileViewer.getState().beginRun(assistantMsgId, sessionId);
      const buffer = createChatStreamBuffer(event => updateStream(assistantMsgId, event));
      try {
        await responder(
          content,
          buffer.push,
          sessionId,
          model === "未配置" ? undefined : model,
          reasoningLevel,
          permissionMode,
          controller.signal,
          model === "未配置" ? undefined : effectiveProviderId ?? undefined,
          context
        );
        // responder 正常返回:用户停止时保留部分文本并标记 stopped
        if (controller.signal.aborted && mountedRef.current) {
          buffer.push({ type: "finish", stopped: true });
        }
      } catch (error) {
        if (!mountedRef.current) return;
        // 用户主动 abort 不算错误(部分文本保留)
        if (controller.signal.aborted) {
          buffer.push({ type: "finish", stopped: true });
          return;
        }
        buffer.push({ type: "error", error: error instanceof Error ? error : new Error(humanizeError(error).message) });
        buffer.push({ type: "finish", failed: true });
      } finally {
        buffer.flush();
        if (abortRef.current === controller) abortRef.current = null;
        if (mountedRef.current) {
          setIsSending(false);
        }
        // 轮次结束后兜底拉 AI 标题：title 事件是旁路任务，短轮次常在 done 之后
        // 才回来（emitter 已 complete），事件会丢。这里轮询补一次。
        if (sessionId) void useChatSessions.getState().awaitGeneratedTitle(sessionId);
        void useFileViewer.getState().refresh(true);
        // 轮次结束批量校验所有打开的标签:agent 本轮可能删/移了文件,
        // 失效标签即时标记,不等用户点刷新才发现
        void useFileViewer.getState().validateTabs();
      }
    },
    [responder, sessionId, model, reasoningLevel, permissionMode, updateStream, effectiveProviderId]
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

  const appendUserMessage = useCallback(async (content: string, context?: TaskContextPayload) => {
    const userMsg: ChatMessage = {
      id: randomId(),
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
    await runTurn(content, assistantMsgId, context);
  }, [runTurn, setMessages]);

  const queueRequest = useCallback((content: string, pendingRefs: ChatRef[], restrict: boolean, kind: "message" | "compact" = "message", id = randomId()): QueuedChatMessage => ({
    id, kind, content, refs: pendingRefs, limitToRefs: restrict,
    request: { content: kind === "compact" ? content : composeWithRefs(content, pendingRefs), model: model ?? undefined,
      providerId: effectiveProviderId ?? undefined, reasoningLevel, permissionMode,
      context: contextFromRefs(pendingRefs, restrict) },
  }), [composeWithRefs, contextFromRefs, model, effectiveProviderId, reasoningLevel, permissionMode]);

  const queueRunRef = useRef<string | null | undefined>(undefined);
  const syncQueue = useCallback(async () => {
    if (!sessionId) return;
    const view = await fetchChatQueue(sessionId);
    const current = useChatSessions.getState().sessions.find(item => item.id === sessionId);
    if (queueRunRef.current !== view.latestRunId || JSON.stringify(current?.queuedMessages ?? []) !== JSON.stringify(view.messages) || current?.queuePaused !== view.paused) {
      queueRunRef.current = view.latestRunId;
      updateQueue(() => view.messages, view.paused);
      if (mountedRef.current) setQueueRevision(value => value + 1);
    }
  }, [sessionId, updateQueue]);

  useEffect(() => {
    if (!sessionId || responder !== AgentAPI.sendMessage) return;
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const old = useChatSessions.getState().sessions.find(item => item.id === sessionId)?.queuedMessages ?? [];
        for (const message of old.filter(item => !item.request)) {
          const input = queueRequest(message.content, message.refs, message.limitToRefs, message.kind === "compact" ? "compact" : "message", message.id);
          if (message.insertTurnId) {
            const history = await fetchSessionMessages(sessionId, true);
            if (!history.some(item => item.id === `steer-${message.insertTurnId}-${message.id}`)) {
              const live = await fetchLiveTurn(sessionId);
              if (live.running && live.turnId === message.insertTurnId)
                await steerTurnOnBackend(sessionId, message.insertTurnId, message.id, input.request!.content, input.request!.context);
              else await enqueueChatInput(sessionId, input);
            }
          } else await enqueueChatInput(sessionId, input);
        }
        if (!cancelled) await syncQueue();
      } catch { /* 请求失败保留草稿和已缓存的排队记录。 */ }
      if (!cancelled) timer = setTimeout(poll, 1000);
    };
    void poll();
    return () => { cancelled = true; clearTimeout(timer); };
  }, [sessionId, responder, queueRequest, syncQueue]);

  const processQueuedMessage = useCallback(async (messageId: string) => {
    if (!sessionId || queueBusyRef.current) return;
    queueBusyRef.current = messageId;
    setProcessingQueuedId(messageId);
    try {
      const live = await fetchLiveTurn(sessionId);
      if (!live.running) await resumeChatQueue(sessionId);
      else {
        if (!live.turnId || live.operation === "compact" || stopRequestedRef.current) throw new Error("当前轮次不能插入，请等待排队发送");
        const message = queuedMessages.find(item => item.id === messageId);
        const turnId = message?.insertTurnId ?? live.turnId;
        updateQueue(queue => queue.map(item => item.id === messageId ? { ...item, insertTurnId: turnId } : item));
        await insertChatInput(sessionId, messageId, turnId);
      }
      await syncQueue();
    } catch (error) {
      if (mountedRef.current) toast.error(humanizeError(error).message);
    } finally { finishQueueOperation(messageId); }
  }, [sessionId, queuedMessages, updateQueue, syncQueue, finishQueueOperation]);

  const removeQueuedMessage = useCallback(async (id: string) => {
    if (!sessionId || queueBusyRef.current) return;
    queueBusyRef.current = id; setProcessingQueuedId(id);
    try { await withdrawChatInput(sessionId, id); await syncQueue(); }
    catch (error) { toast.error(humanizeError(error).message); }
    finally { finishQueueOperation(id); }
  }, [sessionId, syncQueue, finishQueueOperation]);

  const restoreQueuedMessage = useCallback(async (id: string) => {
    if (!sessionId || queueBusyRef.current) return;
    queueBusyRef.current = id; setProcessingQueuedId(id);
    try {
      const message = await withdrawChatInput(sessionId, id);
      const current = mountedRef.current ? composerRef.current
        : useChatSessions.getState().sessions.find(item => item.id === sessionId)?.composerDraft ?? composerRef.current;
      const restored = {
        ...current,
        input: [message.content, current.input].filter(value => value.trim()).join("\n\n"),
        refs: [...message.refs, ...current.refs.filter(ref => !message.refs.some(item => refKey(item) === refKey(ref)))],
        limitToRefs: message.limitToRefs || current.limitToRefs,
        pendingInput: current.pendingInput,
      };
      composerRef.current = restored;
      useChatSessions.getState().saveComposerDraft(sessionId, restored);
      if (mountedRef.current) {
        setInput(restored.input);
        setRefs(restored.refs);
        setLimitToRefs(restored.limitToRefs);
      }
      await syncQueue();
    } catch (error) { toast.error(humanizeError(error).message); }
    finally { finishQueueOperation(id); }
  }, [sessionId, syncQueue, finishQueueOperation]);

  const queueMessage = useCallback(async (content: string, pendingRefs: ChatRef[] = [], restrict = false, kind: "message" | "compact" = "message", clearDraft = false) => {
    if (!sessionId) throw new Error("排队需要当前会话");
    if (queueBusyRef.current) throw new Error("正在更新排队消息，请稍后重试");
    const previous = pendingQueueRef.current;
    const message = previous && previous.content === content && previous.kind === kind && previous.limitToRefs === restrict
      && JSON.stringify(previous.refs) === JSON.stringify(pendingRefs) ? previous : queueRequest(content, pendingRefs, restrict, kind);
    queueBusyRef.current = message.id;
    setProcessingQueuedId(message.id);
    try {
      pendingQueueRef.current = message;
      useChatSessions.getState().saveComposerDraft(sessionId, { ...composerRef.current, pendingInput: message });
      const view = await enqueueChatInput(sessionId, message);
      pendingQueueRef.current = undefined;
      const sameDraft = clearDraft && composerRef.current.input.trim() === content
        && (kind === "compact" || (JSON.stringify(composerRef.current.refs) === JSON.stringify(pendingRefs) && composerRef.current.limitToRefs === restrict));
      composerRef.current = sameDraft ? { ...composerRef.current, input: "", ...(kind === "message" ? { refs: [], limitToRefs: false } : {}), pendingInput: undefined }
        : { ...composerRef.current, pendingInput: undefined };
      useChatSessions.getState().saveComposerDraft(sessionId, composerRef.current);
      updateQueue(() => view.messages, view.paused);
      if (mountedRef.current) {
        if (sameDraft) {
          setInput("");
          if (kind === "message") { setRefs([]); setLimitToRefs(false); }
        }
        setQueueRevision(value => value + 1);
      }
    } finally { finishQueueOperation(message.id); }
  }, [sessionId, queueRequest, updateQueue, finishQueueOperation]);

  const queueCompaction = useCallback(async (content: string, clearDraft = false) => {
    if ((parseChatCommand(content)?.argument ?? "").length > 4000) throw new Error("focus exceeds 4000 characters");
    await queueMessage(content, [], false, "compact", clearDraft);
  }, [queueMessage]);

  const sendMessage = useCallback(async () => {
    const { input, refs, limitToRefs } = composerRef.current;
    if (!input.trim() && refs.length === 0) return;
    if (parseChatCommand(input)?.command.name === "compact") {
      try { await queueCompaction(input.trim(), true); }
      catch (error) { toast.error(humanizeError(error).message); }
      return;
    }
    const content = composeWithRefs(input.trim(), refs);
    if (content.length > 8000) { toast.error("message exceeds 8000 characters"); return; }
    if (isSending || abortRef.current || recoverActiveRef.current || queueBusyRef.current || queuedMessages.length > 0) {
      try {
        await queueMessage(input.trim(), refs, limitToRefs, "message", true);
      } catch (error) {
        toast.error(humanizeError(error).message);
      }
      return;
    }
    const context = contextFromRefs(refs);
    composerRef.current = { input: "", refs: [], limitToRefs: false, pendingInput: undefined };
    useChatSessions.getState().saveComposerDraft(sessionId, composerRef.current);
    setInput(""); setRefs([]); setLimitToRefs(false);
    await appendUserMessage(content, context);
  }, [sessionId, isSending, queuedMessages.length, composeWithRefs, contextFromRefs, appendUserMessage, queueMessage, queueCompaction]);

  // 任务卡发出的是用户的明确选择，保留聊天输入区尚未发送的草稿和引用。
  const sendTaskMessage = useCallback(async (content: string) => {
    if (isSending || abortRef.current || recoverActiveRef.current || queueBusyRef.current || queuedMessages.length > 0) {
      await queueMessage(content);

    } else await appendUserMessage(content);
  }, [isSending, queuedMessages.length, appendUserMessage, queueMessage]);

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
        await queueCompaction(conversation[idx].content || "/compact");
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
    [conversation, isSending, sessionId, appendUserMessage, updateMessage, queueCompaction, setMessages]
  );

  /** 取消受理后保持流连接，真实终态到达才解除发送占位。 */
  const stopGenerating = useCallback(async () => {
    if (stopRequestedRef.current || !isSending) return;
    stopRequestedRef.current = true;
    setIsStopping(true);


    if (!sessionId) { abortRef.current?.abort(); return; }
    try {
      const controller = abortRef.current;
      let accepted = await cancelTurnOnBackend(sessionId);
      // 取消可能先于 POST 到达服务端；只重试同一次尚未结束的发送。
      while (!accepted && controller && abortRef.current === controller && mountedRef.current) {
        await new Promise(resolve => setTimeout(resolve, 200));
        if (abortRef.current !== controller || !mountedRef.current) return;
        accepted = await cancelTurnOnBackend(sessionId);
      }
    } catch (error) {
      stopRequestedRef.current = false;
      if (mountedRef.current) setIsStopping(false);
      toast.error(humanizeError(error).message);
    }
  }, [sessionId, isSending]);

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
          { id: randomId(), role: "user", content: edited.trim(), timestamp: nowHm(), taskContext: conversation[idx].taskContext },
          { id: assistantMsgId, role: "assistant", content: "", timestamp: nowHm(), isTyping: true, startedAtMs: Date.now() },
        ]);
        await runTurn(edited.trim(), assistantMsgId, conversation[idx].taskContext);
      } finally {
        if (mountedRef.current) setIsSending(false);
      }
    },
    [conversation, isSending, sessionId, runTurn, setMessages]
  );

  const clear = useCallback(() => {
    setMessages([]);
  }, [setMessages]);

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
