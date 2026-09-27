'use client';

import { useNavigate } from "react-router-dom";
import { Header } from "@/components/layout/Header";
import { Sparkles, Trash2, Copy, Check } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useChatSessions } from "@/hooks/useChatSessions";
import { ChatConversation } from "@/components/chat/ChatConversation";
import { useModelProviders } from "@/hooks/useModelProviders";
import { deleteSessionOnBackend } from "@/lib/api/agentApi";
import { relativeTime } from "@/lib/relativeTime";
import { useAgentOnline } from "@/hooks/useBackendHealth";
import { toast } from "sonner";
import { useEffect, useState } from "react";

export default function ChatPage() {
  const navigate = useNavigate();
  const sessions = useChatSessions((s) => s.sessions);
  const createSession = useChatSessions((s) => s.createSession);
  const activeId = useChatSessions((s) => s.activeId);
  const deleteSession = useChatSessions((s) => s.deleteSession);
  const undoDeleteSession = useChatSessions((s) => s.undoDeleteSession);
  const defaultModel = useModelProviders((s) => s.defaultModel);
  const syncProviders = useModelProviders((s) => s.syncFromBackend);
  const [copied, setCopied] = useState(false);
  // B7(2026-09-27):agent 独立探针——离线只提示本区域,不全屏替换
  const agentOnline = useAgentOnline();
  // 跨页跳转预填(如数据源页「让 AI 帮我写 SQL」带 ?prompt=...):只读一次,避免后续重挂载重复填入
  const [prefillPrompt] = useState(() => new URLSearchParams(window.location.search).get("prompt") ?? "");
  // 自动发送(2026-09-20 首页「开始新需求」):autosend=1 时预填后直接发出,
  // 发送即从 URL 移除该参数——刷新页面不会重复发送。
  const [autoSend] = useState(() => new URLSearchParams(window.location.search).get("autosend") === "1");
  // 跨页「交给助手」交接(M2-02):?refs=<JSON> 携带结构化引用(文件/文档等),
  // 预填为引用 chip(用户可增删后再发送)。格式:[{kind,id,name}]。
  const [prefillRefs] = useState<import("@/lib/chatRefs").ChatRef[]>(() => {
    const raw = new URLSearchParams(window.location.search).get("refs");
    if (!raw) return [];
    try {
      const parsed = JSON.parse(raw) as Array<{ kind?: string; id?: number | string; name?: string }>;
      if (!Array.isArray(parsed)) return [];
      return parsed
        .filter((r) => r && typeof r.kind === "string" && r.id != null && typeof r.name === "string")
        .filter((r) => ["file", "doc", "skill", "mcp", "datasource"].includes(r.kind as string))
        .map((r) => ({ kind: r.kind as "file" | "doc" | "skill" | "mcp" | "datasource", id: Number(r.id), name: r.name as string }));
    } catch {
      return [];
    }
  });
  // 指定会话(M1-02):助手首页「开始新需求」新建会话后带 ?session=<id> 进入,
  // 直接落到该会话而不是"最近一个活跃会话"。挂载时只应用一次。
  const [targetSessionId] = useState(() => new URLSearchParams(window.location.search).get("session") ?? "");
  // B6(2026-09-27):跨页「交给助手」带 ?new=1 时**新建会话**——
  // 从资料页发起 = 一个新处理任务,不再默认接着当前活跃会话工作。
  const [wantNewSession] = useState(() => new URLSearchParams(window.location.search).get("new") === "1");
  const setActiveSession = useChatSessions((s) => s.setActive);

  useEffect(() => {
    if (targetSessionId) {
      setActiveSession(targetSessionId);
    } else if (wantNewSession) {
      // 新建空会话并激活(prompt/refs 由 prefill 机制填入输入区,不自动发送)
      const newId = createSession();
      setActiveSession(newId);
      // 用后即清 URL 的 new 参数:切会话重挂载不再重复建会话
      try {
        const url = new URL(window.location.href);
        url.searchParams.delete("new");
        window.history.replaceState({}, "", url.toString());
      } catch { /* URL 处理失败不影响 */ }
    }
  }, [targetSessionId, wantNewSession, setActiveSession, createSession]);

  useEffect(() => {
    void syncProviders().catch(() => undefined);
  }, [syncProviders]);

  const active = sessions.find((s) => s.id === activeId) ?? sessions[0];

  /** 会话删除 + undo(与侧栏一致;5 秒内可撤销,窗口后才删后端) */
  const handleDeleteActive = () => {
    if (!active) return;
    const snapshot = sessions;
    const index = snapshot.findIndex((s) => s.id === active.id);
    const removed = active;
    const title = active.title;
    deleteSession(active.id);
    toast.success(`已删除「${title.slice(0, 16)}${title.length > 16 ? "…" : ""}」`, {
      description: "5 秒内可撤销",
      action: {
        label: "撤销",
        onClick: () => undoDeleteSession(removed, index),
      },
      duration: 5000,
    });
    window.setTimeout(() => {
      const stillDeleted = !useChatSessions.getState().sessions.some((s) => s.id === removed.id);
      if (stillDeleted) void deleteSessionOnBackend(removed.id).catch(() => undefined);
    }, 5300);
  };

  const copySessionId = async () => {
    if (!active) return;
    try {
      await navigator.clipboard.writeText(active.id);
      setCopied(true);
      toast.success(`会话 ID 已复制：${active.id}`);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      toast.error("复制失败,请手动复制:" + active.id);
    }
  };

  return (
    <>
      <Header
        breadcrumbs={[
          { label: "工作台", href: "/", isCurrent: false },
          { label: "对话", isCurrent: false },
          { label: active?.title ?? "新对话", isCurrent: true }
        ]}
        actions={
          <div className="flex items-center gap-3 text-muted-foreground text-sm">
            {/* 会话 ID:点击复制,便于查库排障(agent_step/chat_message 按 session_id 关联) */}
            {active && (
              <button
                type="button"
                onClick={copySessionId}
                title="点击复制会话 ID,用于日志与数据库排障"
                className="hidden md:inline-flex items-center gap-1 px-2 py-1 rounded text-[10px] font-mono text-muted-foreground border border-dashed border-border hover:text-foreground hover:border-foreground/40 cursor-pointer transition-colors"
              >
                {copied ? <Check className="w-3 h-3 text-green-500" /> : <Copy className="w-3 h-3" />}
                {active.id}
              </button>
            )}
            {active && (
              <span className="hidden md:inline text-[10px] text-muted-foreground/70 tabular-nums" title="最近活跃时间">
                {relativeTime(active.updatedAt)}
              </span>
            )}
            <button
              type="button"
              onClick={() => navigate("/settings?tab=模型管理")}
              className="px-2 py-1 bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400 border border-blue-100 dark:border-blue-900 rounded text-xs flex items-center gap-1.5 mr-2 hover:bg-blue-100 dark:hover:bg-blue-900/50 cursor-pointer transition-colors"
              title="点击管理模型服务商"
            >
              <Sparkles className="w-3 h-3" /> {defaultModel}
            </button>
            <Button
              variant="ghost"
              size="icon"
              className="h-8 w-8 text-muted-foreground hover:text-red-500 dark:hover:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/40"
              title="删除当前会话"
              onClick={handleDeleteActive}
            >
              <Trash2 className="w-4 h-4" />
            </Button>
          </div>
        }
      />
      <div className="flex-1 flex overflow-hidden">
        {active ? (
          <div key={active.id} className="flex-1 relative flex flex-col min-w-0">
            {/* B7(2026-09-27):agent-service 不可用时的局部提示——不再全屏
                替换整个工作台(文件/任务/数据源区域仍可用),只在此区域说明 */}
            {agentOnline === false && (
              <div className="shrink-0 px-4 py-2 bg-amber-50 dark:bg-amber-950/30 border-b border-amber-200 dark:border-amber-900/50 text-[11px] text-amber-800 dark:text-amber-200">
                Agent 服务不可用——对话暂时无法执行;其他区域(文件、任务、数据源)不受影响。服务恢复后本提示自动消失。
              </div>
            )}
            <ChatConversation sessionId={active.id} initialMessages={active.messages} initialInput={prefillPrompt} initialRefs={prefillRefs} autoSendInitial={autoSend} />
          </div>
        ) : (
          <div className="flex-1 flex flex-col items-center justify-center gap-3 text-muted-foreground">
            <p className="text-sm">还没有对话</p>
            <Button
              size="sm"
              className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600"
              onClick={() => {
                const s = createSession();
                setActiveSession(s);
              }}
            >
              <Sparkles className="w-3.5 h-3.5 mr-1.5" /> 开始新对话
            </Button>
            <p className="text-xs opacity-70">或从左侧「新建对话」开始</p>
          </div>
        )}
      </div>
    </>
  );
}

