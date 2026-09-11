'use client';

import { useCallback, useEffect, useState } from "react";
import { toast } from "sonner";
import { Plus, Trash2, RefreshCw, Loader2, Server as ServerIcon, Plug, Search } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import {
  fetchMcpServers,
  createMcpServer,
  deleteMcpServer,
  setMcpServerEnabled,
  refreshMcpServer,
  type McpServer,
} from "@/lib/api/mcpApi";
import { USE_BACKEND } from "@/lib/api/client";

const STATUS_META: Record<McpServer["status"], { label: string; className: string; dot: string }> = {
  connected: { label: "已连接", className: "text-green-600 dark:text-green-400", dot: "bg-green-500" },
  error: { label: "连接失败", className: "text-red-500", dot: "bg-red-500" },
  untested: { label: "未测试", className: "text-muted-foreground", dot: "bg-gray-400" },
};

/**
 * MCP 服务器管理页主体(侧边栏「MCP」→ /mcp):注册远程 MCP 工具服务器、
 * 测试连接(refresh 拉取 tools/list 并缓存)、启停、删除。
 * 设计语言对齐 AI 能力页:页头主操作按钮(由页面传入 formOpen)+ 卡片网格。
 */
export function McpManager({ formOpen, onFormToggle }: {
  formOpen: boolean;
  onFormToggle: (open: boolean) => void;
}) {
  const [servers, setServers] = useState<McpServer[]>([]);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [newName, setNewName] = useState("");
  const [newUrl, setNewUrl] = useState("");
  const [newTransport, setNewTransport] = useState<"STREAMABLE" | "SSE" | "STDIO">("STREAMABLE");
  const [newHeaderKey, setNewHeaderKey] = useState("");
  const [newHeaderValue, setNewHeaderValue] = useState("");
  // STDIO:命令 + 参数(空格分隔输入,提交时按引号/空格拆分为数组)+ env
  const [newCommand, setNewCommand] = useState("");
  const [newArgs, setNewArgs] = useState("");
  const [newEnvKey, setNewEnvKey] = useState("");
  const [newEnvValue, setNewEnvValue] = useState("");
  const [adding, setAdding] = useState(false);

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
  }, [reload]);

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

  /** 拆分参数输入:支持双引号包裹(空格保留),否则按空白切分 */
  const splitArgs = (raw: string): string[] => {
    const out: string[] = [];
    let cur = "";
    let inQuote = false;
    for (const ch of raw.trim()) {
      if (ch === '"') {
        inQuote = !inQuote;
      } else if (!inQuote && /\s/.test(ch)) {
        if (cur) { out.push(cur); cur = ""; }
      } else {
        cur += ch;
      }
    }
    if (cur) out.push(cur);
    return out;
  };

  const handleAdd = async () => {
    const name = newName.trim();
    if (!name) return void toast.error("请填写服务器名称");
    if (newTransport === "STDIO") {
      const command = newCommand.trim();
      if (!command) return void toast.error("请填写启动命令(如 npx / node / docker)");
      const env: Record<string, string> = {};
      if (newEnvKey.trim() || newEnvValue.trim()) {
        if (!newEnvKey.trim() || !newEnvValue.trim()) {
          return void toast.error("环境变量的键和值需同时填写,或都留空");
        }
        env[newEnvKey.trim()] = newEnvValue.trim();
      }
      setAdding(true);
      try {
        await createMcpServer({
          name,
          transport: "STDIO",
          command,
          args: splitArgs(newArgs),
          env,
        });
        toast.success(`服务器「${name}」已添加,点击「测试连接」启动进程并拉取工具列表`);
        onFormToggle(false);
        setNewName(""); setNewCommand(""); setNewArgs(""); setNewEnvKey(""); setNewEnvValue("");
        await reload();
      } catch (e) {
        toast.error(`添加失败：${e instanceof Error ? e.message : String(e)}`);
      } finally {
        setAdding(false);
      }
      return;
    }
    const url = newUrl.trim();
    if (!/^https?:\/\//.test(url)) return void toast.error("URL 必须以 http(s):// 开头");
    const headers: Record<string, string> = {};
    if (newHeaderKey.trim() || newHeaderValue.trim()) {
      if (!newHeaderKey.trim() || !newHeaderValue.trim()) {
        return void toast.error("Header 的键和值需同时填写,或都留空");
      }
      headers[newHeaderKey.trim()] = newHeaderValue.trim();
    }
    setAdding(true);
    try {
      await createMcpServer({ name, url, transport: newTransport, headers });
      toast.success(`服务器「${name}」已添加,点击「测试连接」拉取工具列表`);
      onFormToggle(false);
      setNewName(""); setNewUrl(""); setNewHeaderKey(""); setNewHeaderValue("");
      await reload();
    } catch (e) {
      toast.error(`添加失败：${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setAdding(false);
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

      {/* 添加表单(页头按钮展开) */}
      {formOpen && (
        <div className="rounded-xl border border-border bg-card dark:bg-card shadow-sm p-5 space-y-4 animate-in fade-in slide-in-from-top-2">
          <div className="text-sm font-medium text-foreground">注册 MCP 服务器</div>
          <div className="grid grid-cols-1 md:grid-cols-12 gap-4">
            <div className="md:col-span-3">
              <label className="text-[10px] font-bold text-muted-foreground uppercase mb-1.5 block">名称</label>
              <Input placeholder="例如:search-tools" className="h-9 text-sm bg-background" value={newName} onChange={(e) => setNewName(e.target.value)} />
            </div>
            <div className="md:col-span-6">
              <label className="text-[10px] font-bold text-muted-foreground uppercase mb-1.5 block">
                {newTransport === "STDIO" ? "启动命令" : "URL"}
              </label>
              {newTransport === "STDIO" ? (
                <Input placeholder="npx / node / docker / uvx" className="h-9 text-sm font-mono bg-background" value={newCommand} onChange={(e) => setNewCommand(e.target.value)} />
              ) : (
                <Input placeholder="https://mcp.example.com/mcp" className="h-9 text-sm font-mono bg-background" value={newUrl} onChange={(e) => setNewUrl(e.target.value)} />
              )}
            </div>
            <div className="md:col-span-3">
              <label className="text-[10px] font-bold text-muted-foreground uppercase mb-1.5 block">传输协议</label>
              <div className="flex gap-2">
                {(["STREAMABLE", "SSE", "STDIO"] as const).map((t) => (
                  <button
                    key={t}
                    type="button"
                    onClick={() => setNewTransport(t)}
                    className={`flex-1 h-9 rounded-md border text-xs font-medium transition-colors ${
                      newTransport === t
                        ? "border-primary bg-primary/10 text-primary"
                        : "border-border bg-background text-muted-foreground hover:text-foreground"
                    }`}
                  >
                    {t === "STREAMABLE" ? "HTTP" : t}
                  </button>
                ))}
              </div>
            </div>
          </div>
          {newTransport === "STDIO" && (
            <div className="grid grid-cols-1 md:grid-cols-12 gap-4 items-end">
              <div className="md:col-span-6">
                <label className="text-[10px] font-bold text-muted-foreground uppercase mb-1.5 block">命令参数(可选,空格分隔;含空格用引号)</label>
                <Input placeholder='-y @modelcontextprotocol/server-filesystem "D:/docs"' className="h-9 text-sm font-mono bg-background" value={newArgs} onChange={(e) => setNewArgs(e.target.value)} />
              </div>
              <div className="md:col-span-3">
                <label className="text-[10px] font-bold text-muted-foreground uppercase mb-1.5 block">环境变量(可选)</label>
                <Input placeholder="API_KEY" className="h-9 text-sm font-mono bg-background" value={newEnvKey} onChange={(e) => setNewEnvKey(e.target.value)} />
              </div>
              <div className="md:col-span-3">
                <label className="text-[10px] font-bold text-muted-foreground uppercase mb-1.5 block">值</label>
                <Input placeholder="sk-…" type="password" className="h-9 text-sm font-mono bg-background" value={newEnvValue} onChange={(e) => setNewEnvValue(e.target.value)} />
              </div>
            </div>
          )}
          {newTransport !== "STDIO" && (
            <div className="grid grid-cols-1 md:grid-cols-12 gap-4 items-end">
              <div className="md:col-span-4">
                <label className="text-[10px] font-bold text-muted-foreground uppercase mb-1.5 block">认证 Header(可选)</label>
                <Input placeholder="Authorization" className="h-9 text-sm font-mono bg-background" value={newHeaderKey} onChange={(e) => setNewHeaderKey(e.target.value)} />
              </div>
              <div className="md:col-span-5">
                <label className="text-[10px] font-bold text-muted-foreground uppercase mb-1.5 block">值</label>
                <Input placeholder="Bearer sk-…" type="password" className="h-9 text-sm font-mono bg-background" value={newHeaderValue} onChange={(e) => setNewHeaderValue(e.target.value)} />
              </div>
              <div className="md:col-span-3 flex gap-2 justify-end">
                <Button size="sm" variant="outline" className="h-9 px-4 text-xs" onClick={() => onFormToggle(false)}>取消</Button>
                <Button size="sm" className="h-9 px-4 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 text-white" disabled={adding} onClick={() => void handleAdd()}>
                  {adding ? <Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" /> : <Plus className="w-3.5 h-3.5 mr-1" />} 注册
                </Button>
              </div>
            </div>
          )}
          {newTransport === "STDIO" && (
            <div className="flex gap-2 justify-end">
              <Button size="sm" variant="outline" className="h-9 px-4 text-xs" onClick={() => onFormToggle(false)}>取消</Button>
              <Button size="sm" className="h-9 px-4 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 text-white" disabled={adding} onClick={() => void handleAdd()}>
                {adding ? <Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" /> : <Plus className="w-3.5 h-3.5 mr-1" />} 注册
              </Button>
            </div>
          )}
        </div>
      )}

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

                <div className="flex items-center justify-between pt-1 border-t border-border/60">
                  <span className="text-[11px] text-muted-foreground">
                    {s.transport === "STDIO" ? "本地进程 (STDIO)" : s.transport === "SSE" ? "SSE" : "Streamable HTTP"} · {s.toolCount} 个工具
                  </span>
                  <div className="flex items-center gap-1">
                    <Button variant="ghost" size="sm" className="h-7 px-2 text-[11px] text-muted-foreground hover:text-foreground" disabled={busy} onClick={() => void handleRefresh(s)}>
                      {busy ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />} 测试连接
                    </Button>
                    <Button variant="ghost" size="icon" className="w-7 h-7 text-muted-foreground hover:text-destructive" title="删除" disabled={busy} onClick={() => handleDelete(s)}>
                      <Trash2 className="w-3.5 h-3.5" />
                    </Button>
                  </div>
                </div>
              </div>
            );
          })}
        </div>
      )}

      <p className="text-[11px] text-muted-foreground leading-relaxed flex items-start gap-2">
        <Plug className="w-3.5 h-3.5 mt-0.5 shrink-0" />
        已启用服务器的工具对所有对话生效,以 <code className="font-mono text-foreground">mcp__服务器名__工具名</code> 挂载;MCP 工具调用默认按高风险处理——「帮我批准」档位下每次执行都会请求确认。停用不会删除配置。
      </p>
    </div>
  );
}
