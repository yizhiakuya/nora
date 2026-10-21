'use client';

import { useNavigate } from "react-router-dom";
import { Header } from "@/components/layout/Header";
import { MessageSquarePlus, Trash2, Copy, Check, MoreHorizontal, Settings } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useChatSessions } from "@/hooks/useChatSessions";
import { ChatConversation } from "@/components/chat/ChatConversation";
import { useWorkspace } from "@/components/layout/WorkspaceContext";
import { useModelProviders } from "@/hooks/useModelProviders";
import { useDeleteChatSession } from "@/hooks/useDeleteChatSession";
import { ApiError } from "@/lib/api/client";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";
import { useAgentOnline } from "@/hooks/useBackendHealth";
import { toast } from "sonner";
import { useEffect, useState } from "react";

export default function ChatPage() {
  const navigate = useNavigate();
  const { browser } = useWorkspace();
  const sessions = useChatSessions((s) => s.sessions);
  const createSession = useChatSessions((s) => s.createSession);
  const activeId = useChatSessions((s) => s.activeId);
  const deleteSession = useDeleteChatSession();
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
  const loadSession = useChatSessions((s) => s.loadSession);
  const [sessionError, setSessionError] = useState<string | null>(null);
  const [sessionReady, setSessionReady] = useState(!targetSessionId);

  useEffect(() => {
    let disposed = false;
    if (targetSessionId) {
      setActiveSession(targetSessionId);
      if (useChatSessions.getState().sessions.some(session => session.id === targetSessionId && session.draft)) {
        setSessionReady(true);
      } else {
        void loadSession(targetSessionId).then(() => { if (!disposed) setSessionReady(true); })
          .catch(error => {
            if (disposed) return;
            if (error instanceof ApiError && error.code === 404) {
              navigate("/sessions", { replace: true });
              toast.info("会话已删除或不存在");
            } else setSessionError(error instanceof Error ? error.message : String(error));
          });
      }
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
    return () => { disposed = true; };
  }, [targetSessionId, wantNewSession, setActiveSession, createSession, loadSession, navigate]);

  useEffect(() => {
    void syncProviders().catch(() => undefined);
  }, [syncProviders]);

  const active = targetSessionId ? sessions.find(s => s.id === targetSessionId) : sessions.find(s => s.id === activeId) ?? sessions[0];

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

  const renderHeader = () => (
    <Header
      compactActions
      breadcrumbs={[
        { label: "会话", href: "/sessions", isCurrent: false },
        { label: active?.title ?? (targetSessionId ? sessionError ? "会话不可用" : "加载会话…" : "新对话"), isCurrent: true }
      ]}
      actions={
        <DropdownMenu>
          <DropdownMenuTrigger asChild><Button variant="ghost" size="icon" className="h-8 w-8 text-muted-foreground" aria-label="会话操作" title="会话操作"><MoreHorizontal /></Button></DropdownMenuTrigger>
          <DropdownMenuContent align="end" className="w-60">
            <DropdownMenuLabel className="text-xs text-muted-foreground">当前会话</DropdownMenuLabel>
            <DropdownMenuItem disabled={!active} onSelect={() => void copySessionId()}>{copied ? <Check /> : <Copy />}复制会话 ID</DropdownMenuItem>
            <DropdownMenuItem onSelect={() => navigate("/agent?section=model")}><Settings />模型设置</DropdownMenuItem>
            {active && <p className="px-2 py-1 text-[11px] text-muted-foreground break-all font-mono">{active.id}</p>}
            <DropdownMenuSeparator />
            <DropdownMenuItem className="text-destructive focus:text-destructive" disabled={!active || !sessionReady || Boolean(sessionError)} onSelect={() => active && deleteSession(active.id)}><Trash2 />删除当前会话</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      }
    />
  );

  if (sessionError || !sessionReady) return <>
    {renderHeader()}
    <div className="p-6 space-y-3" role={sessionError ? "alert" : "status"}>
      <p className="text-sm whitespace-pre-wrap break-words">{sessionError ?? "正在加载会话…"}</p>
      {sessionError && <Button variant="outline" onClick={() => window.location.reload()}>重试</Button>}
    </div>
  </>;

  return active ? (
    <>
      {renderHeader()}
        {/* B7(2026-09-27):agent-service 不可用时的局部提示——不再全屏
            替换整个工作台(文件/任务/数据源区域仍可用),只在此区域说明 */}
        {agentOnline === false && (
          <div className="shrink-0 px-4 py-2 bg-amber-50 dark:bg-amber-950/30 border-b border-amber-200 dark:border-amber-900/50 text-[11px] text-amber-800 dark:text-amber-200">
            Agent 服务不可用——对话暂时无法执行;其他区域(文件、任务、数据源)不受影响。服务恢复后本提示自动消失。
          </div>
        )}
        <ChatConversation key={active.id} browser={browser} sessionId={active.id} initialInput={prefillPrompt} initialRefs={prefillRefs} autoSendInitial={autoSend} />
    </>
  ) : (
    <>
      {renderHeader()}
      <div className="flex-1 min-h-0 flex flex-col items-center justify-center gap-3 text-muted-foreground">
        <p className="text-sm">还没有对话</p>
        <Button
          size="sm"
          className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600"
          onClick={() => {
            const s = createSession();
            setActiveSession(s);
          }}
        >
          <MessageSquarePlus className="w-3.5 h-3.5 mr-1.5" /> 开始新对话
        </Button>
        <p className="text-xs opacity-70">或从左侧「新建对话」开始</p>
      </div>
    </>
  );
}
