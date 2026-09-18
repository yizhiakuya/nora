'use client';

import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { Trash2, RefreshCw, Loader2, Server as ServerIcon, Plug, Wrench } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import {
  fetchMcpServers,
  deleteMcpServer,
  setMcpServerEnabled,
  setMcpServerToolPolicy,
  refreshMcpServer,
  type McpServer,
} from "@/lib/api/mcpApi";
import { USE_BACKEND } from "@/lib/api/client";
import { McpToolsModal } from "@/components/settings/McpToolsModal";

const STATUS_META: Record<McpServer["status"], { label: string; className: string; dot: string }> = {
  connected: { label: "已连接", className: "text-green-600 dark:text-green-400", dot: "bg-green-500" },
  error: { label: "连接失败", className: "text-red-500", dot: "bg-red-500" },
  untested: { label: "未测试", className: "text-muted-foreground", dot: "bg-gray-400" },
};

/**
 * MCP 服务器管理页主体(侧边栏「MCP」→ /mcp):注册(页头按钮 → 弹窗,
 * 对齐数据源/技能页)、测试连接(refresh 拉取 tools/list 并缓存)、启停、删除。
 * listVersion 变化(注册成功)时重新拉取列表。
 */
export function McpManager({ listVersion }: { listVersion: number }) {
  const [servers, setServers] = useState<McpServer[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<number | null>(null);
  /** 工具列表弹窗的目标服务器;null = 关闭 */
  const [toolsFor, setToolsFor] = useState<McpServer | null>(null);

  const reload = useCallback(async () => {
    setLoading(true);
    try {
      setServers(await fetchMcpServers());
    } catch (e) {
      toast.error(`MCP 服务器列表加载失败：${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (USE_BACKEND) void reload();
    else setLoading(false);
  }, [reload, listVersion]);

  const withBusy = async (id: number, action: () => Promise<void>, okMsg?: string) => {
    setBusyId(id);
    try {
      await action();
      if (okMsg) toast.success(okMsg);
    } catch (e) {
      toast.error(`操作失败：${e instanceof Error ? e.message : String(e)}`);
    } finally {
      // 失败也要刷新:后端会把 status=error + detail 落库,卡片要同步显示
      await reload();
      setBusyId(null);
    }
  };

  const handleRefresh = (s: McpServer) =>
    withBusy(s.id, async () => {
      const result = await refreshMcpServer(s.id);
      const count = result.tools?.length ?? 0;
      if (count > 0) {
        toast.success(`连接成功,挂载 ${count} 个工具:${result.tools!.map((t) => t.name).slice(0, 5).join(", ")}${count > 5 ? " …" : ""}`);
      } else {
        toast.success("连接成功,但该服务器没有提供任何工具");
      }
    });

  const handleToggle = (s: McpServer, enabled: boolean) =>
    withBusy(s.id, async () => {
      await setMcpServerEnabled(s.id, enabled);
      toast.success(enabled ? `「${s.name}」已启用` : `「${s.name}」已停用,其工具不再挂载给 Agent`);
    });

  const handleToolPolicy = (s: McpServer) =>
    withBusy(s.id, async () => {
      const next = s.toolPolicy === "lazy" ? "eager" : "lazy";
      await setMcpServerToolPolicy(s.id, next);
      toast.success(next === "lazy"
        ? `「${s.name}」工具改为按需加载:不再占用每轮上下文,Agent 需要时自行检索调用`
        : `「${s.name}」工具已恢复直接挂载(mcp__${s.name}__*)`);
    });

  const handleDelete = (s: McpServer) => {
    if (!window.confirm(`确定删除 MCP 服务器「${s.name}」?其挂载的工具将不再可用。`)) return;
    void withBusy(s.id, async () => {
      await deleteMcpServer(s.id);
      toast.success(`服务器「${s.name}」已删除`);
    });
  };

  const connectedCount = servers.filter((s) => s.enabled).length;
  const toolTotal = servers.reduce((sum, s) => sum + (s.enabled ? s.toolCount : 0), 0);

  return (
    <div className="space-y-6">
      {/* 顶部统计条 */}
      <div className="grid grid-cols-3 gap-4">
        {[
          { label: "已注册服务器", value: servers.length },
          { label: "已启用", value: connectedCount },
          { label: "挂载工具数", value: toolTotal },
        ].map((stat) => (
          <div key={stat.label} className="rounded-xl border border-border bg-card dark:bg-card p-4 shadow-sm">
            <div className="text-2xl font-bold text-foreground">{stat.value}</div>
            <div className="text-xs text-muted-foreground mt-0.5">{stat.label}</div>
          </div>
        ))}
      </div>

      {/* 服务器卡片网格 */}
      {loading ? (
        <div className="py-20 flex flex-col items-center justify-center text-muted-foreground">
          <Loader2 className="w-8 h-8 mb-3 animate-spin opacity-30" />
          <div className="text-sm">加载中…</div>
        </div>
      ) : servers.length === 0 ? (
        <div className="py-20 flex flex-col items-center justify-center text-muted-foreground animate-in fade-in">
          <Plug className="w-10 h-10 mb-4 opacity-20" />
          <div className="text-sm">还没有注册 MCP 服务器</div>
          <div className="text-xs mt-1 text-muted-foreground/70">点击右上角「注册服务器」接入远程工具,扩展 Agent 能力</div>
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-5 animate-in fade-in slide-in-from-bottom-4 duration-500">
          {servers.map((s) => {
            const status = STATUS_META[s.status] ?? STATUS_META.untested;
            const busy = busyId === s.id;
            return (
              <div key={s.id} className="rounded-xl border border-border bg-card dark:bg-card shadow-sm p-5 flex flex-col gap-3 hover:shadow-md transition-shadow">
                <div className="flex items-start justify-between gap-2">
                  <div className="flex items-center gap-2.5 min-w-0">
                    <div className="w-9 h-9 rounded-lg bg-blue-100 dark:bg-blue-900/50 flex items-center justify-center shrink-0">
                      <ServerIcon className="w-4.5 h-4.5 text-blue-500 dark:text-blue-400" />
                    </div>
                    <div className="min-w-0">
                      <div className="text-sm font-bold text-foreground truncate">{s.name}</div>
                      <div className={`text-[11px] font-medium flex items-center gap-1.5 ${status.className}`}>
                        <span className={`w-1.5 h-1.5 rounded-full ${status.dot}`} />
                        {status.label}
                      </div>
                    </div>
                  </div>
                  <Switch checked={s.enabled} disabled={busy} onCheckedChange={(v) => void handleToggle(s, v)} />
                </div>

                <div className="px-3 py-2 rounded-md bg-muted/50 border border-border text-[11px] font-mono text-muted-foreground truncate" title={s.transport === "STDIO" ? `${s.command} ${s.args ?? ""}`.trim() : s.url ?? ""}>
                  {s.transport === "STDIO" ? `${s.command} ${s.args ?? ""}`.trim() : s.url}
                </div>
                {s.status === "error" && s.statusDetail && (
                  <div className="text-[11px] text-red-500/80 break-words line-clamp-2">{s.statusDetail}</div>
                )}

                <div className="pt-2 border-t border-border/60 space-y-1.5">
                  {/* 第一行:传输方式 + 工具数(点击看详情);第二行:操作按钮。窄卡片下不挤压 */}
                  <div className="flex items-center justify-between gap-2">
                    <button
                      type="button"
                      onClick={() => setToolsFor(s)}
                      title="查看工具列表与详情"
                      className="text-[11px] text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400 transition-colors cursor-pointer inline-flex items-center gap-1.5 min-w-0 whitespace-nowrap"
                    >
                      <span className="truncate">{s.transport === "STDIO" ? "本地进程 (STDIO)" : s.transport === "SSE" ? "SSE" : "Streamable HTTP"}</span>
                      <span className="inline-flex items-center gap-0.5 underline decoration-dotted underline-offset-2 shrink-0 tabular-nums">
                        <Wrench className="w-3 h-3" />{s.toolCount} 个工具
                      </span>
                    </button>
                    <div className="flex items-center gap-1 shrink-0">
                      <button
                        type="button"
                        disabled={busy}
                        onClick={() => void handleToolPolicy(s)}
                        title={s.toolPolicy === "lazy"
                          ? "按需加载:工具不占用每轮上下文,Agent 需要时自行检索调用。点击恢复直接挂载"
                          : "直接挂载:工具随每轮请求注入(mcp__服务器名__工具名)。点击改为按需加载省上下文"}
                        className={`text-[11px] px-1.5 py-0.5 rounded border transition-colors whitespace-nowrap ${
                          s.toolPolicy === "lazy"
                            ? "border-amber-300 dark:border-amber-800 text-amber-600 dark:text-amber-400 bg-amber-50/60 dark:bg-amber-950/30"
                            : "border-border text-muted-foreground hover:text-foreground"
                        }`}
                      >
                        {s.toolPolicy === "lazy" ? "按需加载" : "直接挂载"}
                      </button>
                      <Button variant="ghost" size="sm" className="h-7 px-2 text-[11px] text-muted-foreground hover:text-foreground whitespace-nowrap" disabled={busy} onClick={() => void handleRefresh(s)}>
                        {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />} 测试连接
                      </Button>
                      <Button variant="ghost" size="icon" className="w-7 h-7 text-muted-foreground hover:text-destructive" title="删除" disabled={busy} onClick={() => handleDelete(s)}>
                        <Trash2 className="w-3.5 h-3.5" />
                      </Button>
                    </div>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      <p className="text-[11px] text-muted-foreground leading-relaxed flex items-start gap-2">
        <Plug className="w-3.5 h-3.5 mt-0.5 shrink-0" />
        已启用服务器的工具对所有对话生效,以 <code className="font-mono text-foreground">mcp__服务器名__工具名</code> 挂载;MCP 工具调用默认按高风险处理——「帮我批准」档位下每次执行都会请求确认。停用不会删除配置。点击卡片上的工具数可查看工具列表与参数详情。
      </p>

      {/* 工具列表/详情弹窗(点击卡片工具数打开) */}
      <McpToolsModal
        server={toolsFor}
        onClose={() => setToolsFor(null)}
      />
    </div>
  );
}
