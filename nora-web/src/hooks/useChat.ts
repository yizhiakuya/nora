import { useState, useCallback, useRef, useEffect } from "react";
import { create } from "zustand";
import { persist } from "zustand/middleware";
import { ChatMessage, type ChatResponder, type PermissionMode, type TaskContextPayload } from "@/lib/api/chatApi";
import { AgentAPI, attachLiveTurnStream, cancelTurnOnBackend, fetchAgentSettings, fetchLiveTurn, saveAgentSettings, truncateMessagesFrom, normalizeStep } from "@/lib/api/agentApi";
import { USE_BACKEND } from "@/lib/api/client";
import { useChatSessions } from "./useChatSessions";
import { useModelProviders, resolveDefaultProvider } from "./useModelProviders";
import { humanizeError } from "@/lib/errorMessages";
import { randomId } from "@/lib/utils";
import { nowHm } from "@/lib/format";
import { formatChatRefs, refKey, type ChatRef } from "@/lib/chatRefs";

/**
 * Agent 全局设置(权限模式 / 默认模型 / 思考等级覆写):后端 app_setting
 * 表(key=agent)为唯一真相,localStorage 只作缓存。跨会话、跨浏览器一致;
 * 后端模式启动时拉取,切换时写后端(乐观更新本地)。
 */
interface AgentSettingsState {
  permissionMode: PermissionMode;
  reasoningLevelOverride?: string | undefined;
  setPermissionMode: (mode: PermissionMode) => void;
  setReasoningLevelOverride: (level: string | undefined) => void;
  syncFromBackend: () => Promise<void>;
}

export const useAgentSettings = create<AgentSettingsState>()(
  persist(
    (set, get) => ({
      permissionMode: "assist",
      reasoningLevelOverride: undefined,
      setPermissionMode: (permissionMode) => {
        set({ permissionMode });
        if (USE_BACKEND) void saveAgentSettings({ permissionMode });
      },
      setReasoningLevelOverride: (reasoningLevelOverride) => {
        set({ reasoningLevelOverride });
        if (USE_BACKEND) void saveAgentSettings({ reasoningLevel: reasoningLevelOverride ?? null });
      },
      syncFromBackend: async () => {
        if (!USE_BACKEND) return;
        try {
          const s = await fetchAgentSettings();
          if (s.permissionMode === "ask" || s.permissionMode === "assist" || s.permissionMode === "full") {
            set({ permissionMode: s.permissionMode, reasoningLevelOverride: s.reasoningLevel ?? undefined });
          }
        } catch {
          /* 后端不可达:沿用本地缓存 */
        }
      },
    }),
    { name: "chat-agent-settings" }
  )
);

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
  const [messages, setMessages] = useState<ChatMessage[]>(initialMessages);
  const [input, setInput] = useState(initialInput);
  /** 待发送引用(附件/文件/知识库文档;2026-09-17):发送时序列化进消息尾部。
   *  initialRefs:跨页「交给助手」交接(M2-02)预填的引用集合。 */
  const [refs, setRefs] = useState<ChatRef[]>(initialRefs);
  /**
   * 限定检索范围(B4,2026-09-27):开启后本轮自动检索只从**引用的知识库
   * 文档**里召回(docIds 进 RAG 范围查询),不混入范围外内容。
   * 关闭(默认)= 旧行为:引用内容排前,其余资料仍参与检索。
   */
  const [limitToRefs, setLimitToRefs] = useState(false);
  const [isSending, setIsSending] = useState(false);
  const model = useModelProviders((s) => s.defaultModel);
  // 生效渠道:与后端 activeProvider(providerId, model) 同一回落顺序(显式 id → 按名)。
  // 返回基本类型避免每次渲染生成新引用导致无限重渲。
  const effectiveProviderId = useModelProviders(
    (s) => resolveDefaultProvider(s.providers, s.defaultModel, s.defaultProviderId)?.id ?? null
  );
  /** 对话框思考等级:全局覆写(持久);undefined = 跟随设置页该模型默认 */
  const [reasoningLevel, setReasoningLevel] = useState<string | undefined>(undefined);
  useEffect(() => {
    // 初始化:全局覆写优先;无覆写时回落设置页该模型默认等级
    const override = useAgentSettings.getState().reasoningLevelOverride;
    if (override) setReasoningLevel(override);
  }, []);
  /** 权限模式 + 其余全局设置:后端设置表为真相 */
  const permissionMode = useAgentSettings((s) => s.permissionMode);
  const setPermissionMode = useAgentSettings((s) => s.setPermissionMode);
  const scrollRef = useRef<HTMLDivElement>(null);
  const mountedRef = useRef(true);
  /** 进行中轮次的 AbortController;null = 空闲。「停止生成」按钮调用 abort() */
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
  /** 用户点过「停止生成」:本挂载周期内不再自动接续(停止后又被接上=停不掉)。 */
  const stopRequestedRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
      // 卸载(切会话/切页)只断开本地事件流:后端编排线程继续跑,事件进
      // TurnStreamRegistry 缓冲;回到本会话时由 live-turn 恢复逻辑回放接续。
      // 这里不再 abort——中断等于把进行中的轮次杀掉(旧实现的不可恢复问题)。
      // 真正的「停止生成」走 stopGenerating(显式 abort + 后端 cancel)。
      abortRef.current = null;
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

  // 会话持久化：消息变化即写回 store（未传 sessionId 时不持久化，测试/调试场景不受影响）
  // 瞬态字段(approval/isTyping/startedAtMs)不入缓存:审批卡是一次性交互态,
  // 缓存里的死卡 reload 后会重现(其 token 早已被消费/超时),点了只会 500
  // lastSavedRef 记下写回 store 的确切数组引用:saveMessages 存的就是它,
  // 收编 effect 比对引用即可识别「自己写回的回声」,斩断无限循环
  // (否则 map 出的新数组每次都是新引用 → store 变 → 收编 → 再写回 → 死循环白屏)
  const lastSavedRef = useRef<ChatMessage[] | null>(null);
  useEffect(() => {
    if (!sessionId) return;
    const persistable = messages.map(({ approval, isTyping, startedAtMs, ...rest }) => rest);
    lastSavedRef.current = persistable;
    useChatSessions.getState().saveMessages(sessionId, persistable);
  }, [messages, sessionId]);

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
    if (isSending) return; // 流式进行中不抢本地状态,结束后由写回自然收敛
    if (!sessionMessages) return;
    const current = useChatSessions.getState().sessions.find((x) => x.id === sessionId)?.messages;
    if (!current || current === lastSavedRef.current) return; // 自己写回的回声,忽略
    setMessages(current);
  }, [sessionMessages, isSending, sessionId]);

  const updateMessage = useCallback((id: string, partial: Partial<ChatMessage>) => {
    if (!mountedRef.current) return; // 卸载后忽略流式更新
    setMessages((prev) => prev.map((msg) => (msg.id === id ? { ...msg, ...partial } : msg)));
  }, []);

  /**
   * 进行中轮次恢复:刷新/切页回来时,后端那一轮还在跑(事件进 TurnStreamRegistry
   * 缓冲)。探测到 live 轮就接上事件流,重建一条流式中的 assistant 消息;
   * done 后由持久化写回自然收敛进会话 store。
   * 仅 USE_BACKEND 且本会话无本地流式时执行(避免与挂载中的流双开)。
   */
  useEffect(() => {
    if (!USE_BACKEND || !sessionId) return;
    if (isSending) return; // 本地正在流式:轮次是本组件自己发起的,无需恢复
    if (recoverActiveRef.current) return; // 已接续中:接续流生命周期独立,不重复探测
    if (stopRequestedRef.current) return; // 用户已停止:本挂载周期不再自动接续
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
      if (cancelled) return;
      let info;
      try {
        info = await fetchLiveTurn(sessionId);
      } catch {
        return; // 后端不可达:按无轮次处理
      }
      if (cancelled || !info?.running) return;
      // 双保险:store 里这条会话若已有流式中的消息(理论不可能,挂载即无),跳过
      const recoverId = `live-${sessionId}`;
      recoverActiveRef.current = true;
      setMessages((prev) => [
        ...prev.filter((m) => m.id !== recoverId),
        {
          id: recoverId,
          role: "assistant",
          content: "",
          timestamp: nowHm(),
          isTyping: true,
          startedAtMs: info.startedAtMs ?? Date.now(),
          steps: [],
        },
      ]);
      setIsSending(true);
      // 接续流句柄存 ref(而非 effect 局部变量):本 effect 会因 isSending 变化
      // 重跑,局部变量+cleanup detach 会让刚接上的流被自身重跑立刻拆掉。
      recoverRef.current = { detach: attachLiveTurnStream(sessionId, {
        onStep: (s) => {
          const normalized = normalizeStep(s, 0);
          setMessages((prev) => prev.map((m) => {
            if (m.id !== recoverId) return m;
            const steps = m.steps ?? [];
            const idx = steps.findIndex((x) => x.id === normalized.id);
            const next = idx >= 0
              ? steps.map((x, i) => (i === idx ? normalized : x))
              : [...steps, normalized];
            return { ...m, steps: next };
          }));
        },
        onDelta: (c) => {
          setMessages((prev) => prev.map((m) => (m.id === recoverId
            ? { ...m, content: m.content + c }
            : m)));
        },
        onReasoningDelta: (roundIndex, c) => {
          const id = `s-reasoning-${roundIndex ?? "final"}`;
          setMessages((prev) => prev.map((m) => {
            if (m.id !== recoverId) return m;
            const steps = m.steps ?? [];
            const idx = steps.findIndex((x) => x.id === id);
            const next = idx >= 0
              ? steps.map((x, i) => (i === idx ? { ...x, detail: (x.detail ?? "") + c, status: "running" as const } : x))
              : [...steps, {
                  id,
                  type: "think" as const,
                  title: "推理过程",
                  detail: c,
                  status: "running" as const,
                  roundIndex: roundIndex ?? undefined,
                }];
            return { ...m, steps: next };
          }));
        },
        onSources: (citations) => {
          setMessages((prev) => prev.map((m) => (m.id === recoverId ? { ...m, sources: citations } : m)));
        },
        onApproval: (approval) => {
          updateMessage(recoverId, { approval });
        },
        onDone: (p?: unknown) => {
          // 轮次结束:去 typing 态;服务端已落库,主动拉历史收敛终态
          // (直接 loadHistory 会被本会话本地写版本闸门丢弃——恢复消息刚写回
          // store;这里先移除恢复占位再拉,写回的是删除后的数组,闸门不触发)
          recoverActiveRef.current = false;
          recoverRef.current = null;
          const stopped = !!(p as { stopped?: boolean } | undefined)?.stopped;
          if (stopped) {
            // 取消轮终态(done.stopped):保留已流出的半截内容并标记「已停止」,
            // 不拉历史(服务端行无 stopped 语义,拉回来反而丢本地标记)
            updateMessage(recoverId, { isTyping: false, stopped: true });
          } else {
            updateMessage(recoverId, { isTyping: false });
            setMessages((prev) => prev.filter((m) => m.id !== recoverId));
            lastSavedRef.current = null; // 允许下一次 store→本地 收编
            void useChatSessions.getState().loadHistory(sessionId);
          }
          setIsSending(false);
        },
        onError: (msg) => {
          recoverActiveRef.current = false;
          recoverRef.current = null;
          const friendly = humanizeError(msg);
          updateMessage(recoverId, {
            error: friendly.message,
            errorHint: friendly.hint,
            errorKind: friendly.kind,
            errorRaw: friendly.raw,
            isTyping: false,
          });
          setIsSending(false);
        },
        onIdle: () => {
          // 无进行中轮次(竞态:探测到 live 但连接时已结束):移除占位,
          // 由 loadHistory 拉到已落库的最终消息
          recoverActiveRef.current = false;
          recoverRef.current = null;
          setMessages((prev) => prev.filter((m) => m.id !== recoverId));
          setIsSending(false);
        },
        onGap: () => {
          // 回放缺口(2026-09-20):中间事件已被服务端滚动窗口淘汰,已流出的
          // 增量内容不完整——清空占位内容/步骤,只保留 typing 态等 done 后
          // 从权威消息状态恢复(避免把残缺片段拼成一条"完整"回答)。
          setMessages((prev) => prev.map((m) => (m.id === recoverId
            ? { ...m, content: "", steps: [] }
            : m)));
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
  }, [sessionId, isSending, updateMessage]);

  /** 核心发送逻辑:复用于 sendMessage 与 regenerate */
  const runTurn = useCallback(
    async (content: string, assistantMsgId: string, context?: TaskContextPayload) => {
      stopRequestedRef.current = false; // 新一轮:解除「停止后不接续」守卫
      const controller = new AbortController();
      abortRef.current = controller;

      setIsSending(true);
      try {
        await responder(
          content,
          (partial) => updateMessage(assistantMsgId, partial),
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
          updateMessage(assistantMsgId, { isTyping: false, stopped: true });
        }
      } catch (error) {
        if (!mountedRef.current) return;
        // 用户主动 abort 不算错误(部分文本保留)
        if (controller.signal.aborted) {
          updateMessage(assistantMsgId, { isTyping: false, stopped: true });
          return;
        }
        // 错误人性化:结构化错误(ApiError)按分类映射,原始串进 details 折叠
        const friendly = humanizeError(error);
        updateMessage(assistantMsgId, {
          error: friendly.message,
          errorHint: friendly.hint,
          errorKind: friendly.kind,
          errorRaw: friendly.raw,
          isTyping: false,
        });
      } finally {
        if (abortRef.current === controller) abortRef.current = null;
        if (mountedRef.current) setIsSending(false);
        // 轮次结束后兜底拉 AI 标题：title 事件是旁路任务，短轮次常在 done 之后
        // 才回来（emitter 已 complete），事件会丢。这里轮询补一次。
        if (sessionId) void useChatSessions.getState().awaitGeneratedTitle(sessionId);
      }
    },
    [responder, sessionId, model, reasoningLevel, permissionMode, updateMessage, effectiveProviderId]
  );

  /** 引用增删(去重:同引用只保留一条;mcp 以工具全名为键) */
  const addRef = useCallback((ref: ChatRef) => {
    setRefs((prev) => (prev.some((r) => refKey(r) === refKey(ref)) ? prev : [...prev, ref]));
  }, []);
  const removeRef = useCallback((key: string) => {
    setRefs((prev) => prev.filter((r) => refKey(r) !== key));
  }, []);

  /** 拼引用后的实际发送内容:正文 + 引用块(尾部,持久化后历史仍可解析) */
  const composeWithRefs = useCallback(
    (content: string, pending: ChatRef[]) => {
      const block = formatChatRefs(pending);
      return block ? `${content}\n\n${block}` : content;
    },
    []
  );

  /** 待发送引用 → 结构化上下文(M2-01);无引用返回 undefined(请求不带该字段)。 */
  const contextFromRefs = useCallback((pending: ChatRef[]): TaskContextPayload | undefined => {
    // B4(2026-09-27):「限定检索」开启且有知识库文档引用时,附检索范围——
    // 后端据此只从这些文档召回(docIds 同时进入向量与关键词两路)。
    const scopedDocIds = limitToRefs
      ? pending.filter((r) => r.kind === "doc").map((r) => r.id)
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

  const sendMessage = useCallback(async () => {
    if ((!input.trim() && refs.length === 0) || isSending) return;

    const content = composeWithRefs(input.trim(), refs);
    const context = contextFromRefs(refs);
    const userMsg: ChatMessage = {
      id: randomId(),
      role: "user",
      content,
      timestamp: nowHm(),
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
      },
    ]);
    setInput("");
    setRefs([]);
    // B4:限定检索是一次性开关——随发送重置,下一轮默认回到「全库检索」
    setLimitToRefs(false);
    await runTurn(content, assistantMsgId, context);
  }, [input, refs, isSending, runTurn, composeWithRefs, contextFromRefs]);

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
   * 重试失败的轮次:用原用户消息发起新一轮,失败的助手消息就地清空复用
   * (研究结论:重试按钮上的原消息不丢,错误像对话的一部分而非系统崩溃)。
   */
  const retryMessage = useCallback(
    async (failedMsgId: string) => {
      if (isSending) return;
      const idx = messages.findIndex((m) => m.id === failedMsgId);
      if (idx <= 0) return;
      const userMsg = messages[idx - 1];
      if (userMsg?.role !== "user") return;

      const content = userMsg.content;
      const assistantMsgId = randomId();
      // 失败轮就地替换为新助手消息(保留占位),原 user 消息不动
      setMessages((prev) =>
        prev.map((m) =>
          m.id === failedMsgId
            ? { id: assistantMsgId, role: "assistant", content: "", timestamp: nowHm(), isTyping: true, startedAtMs: Date.now() }
            : m
        )
      );
      await runTurn(content, assistantMsgId);
    },
    [messages, isSending, runTurn]
  );

  /** 停止生成:前端断流 + 通知后端取消轮次(上游 LLM 调用一并中止,不白烧 token) */
  const stopGenerating = useCallback(() => {
    stopRequestedRef.current = true; // 本挂载周期不再自动接续(否则「停掉又被接上」)
    abortRef.current?.abort();
    if (USE_BACKEND && sessionId) {
      void cancelTurnOnBackend(sessionId);
    }
    // 接续流模式(切页返回恢复的轮次,无本地 abortRef):后端取消后
    // done(stopped) 会经接续流到达并收敛 UI;若接续流已死(手机冻结等),
    // 兜底就地收敛——否则「停止生成」点了没反应,UI 永久卡「正在思考」(实测 bug)。
    if (!abortRef.current && sessionId) {
      const recoverId = `live-${sessionId}`;
      window.setTimeout(() => {
        if (!mountedRef.current) return;
        if (abortRef.current) return; // 3s 内已发起新轮次:兜底让位,不抢状态
        recoverRef.current?.detach();
        recoverRef.current = null;
        recoverActiveRef.current = false;
        setMessages((prev) => prev.map((m) =>
          m.id === recoverId && m.isTyping ? { ...m, isTyping: false, stopped: true } : m
        ));
        setIsSending(false);
      }, 3000);
    }
  }, [sessionId]);

  /**
   * 编辑重发:回退到某条用户消息,内容替换为 edited 后重新发送。
   * 上下文一致性:先从本地删除该条及其后全部消息,再调后端截断接口
   * 删服务端同分支历史,然后以新内容正常发送——UI 与 LLM 上下文严格一致。
   * 后端截断失败不阻断(本地已删,下轮同步会收敛;旧历史只是多留一轮)。
   */
  const editAndResend = useCallback(
    async (userMsgId: string, edited: string) => {
      if (isSending || !edited.trim()) return;
      const idx = messages.findIndex((m) => m.id === userMsgId);
      if (idx < 0 || messages[idx].role !== "user") return;

      const kept = messages.slice(0, idx);
      setMessages(kept);
      if (USE_BACKEND && sessionId) {
        try {
          await truncateMessagesFrom(sessionId, idx);
        } catch {
          /* 截断失败:以本地为准继续,后端旧分支由下次写回/同步覆盖语义兜底 */
        }
      }

      const assistantMsgId = randomId();
      setMessages([
        ...kept,
        { id: randomId(), role: "user", content: edited.trim(), timestamp: nowHm() },
        { id: assistantMsgId, role: "assistant", content: "", timestamp: nowHm(), isTyping: true, startedAtMs: Date.now() },
      ]);
      await runTurn(edited.trim(), assistantMsgId);
    },
    [messages, isSending, sessionId, runTurn]
  );

  const clear = useCallback(() => {
    setMessages([]);
  }, []);

  return {
    messages,
    input,
    setInput,
    /** 待发送引用(📎附件/📄文件/@知识库) */
    refs,
    addRef,
    removeRef,
    /** 限定检索范围(B4):开启后本轮只从引用的知识文档召回 */
    limitToRefs,
    setLimitToRefs,
    isSending,
    sendMessage,
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
