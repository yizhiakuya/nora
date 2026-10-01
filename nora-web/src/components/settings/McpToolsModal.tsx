'use client';

import { useCallback, useEffect, useMemo, useState } from "react";
import { toast } from "sonner";
import { ChevronDown, ChevronRight, Loader2, RefreshCw, Search, Wrench } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Modal } from "@/components/ui/custom/Modal";
import { fetchMcpTools, refreshMcpServer, type McpServer, type McpToolDetail } from "@/lib/api/mcpApi";

/**
 * MCP 工具列表/详情弹窗:展示某服务器缓存的工具快照(名称/描述/参数 Schema),
 * 数据来自 tools_cache(测试连接时快照),不触发远端调用;
 * 空缓存时引导「测试连接」拉取。搜索框按名称/描述过滤,行点击展开详情。
 */
export function McpToolsModal({ server, onClose }: {
  server: McpServer | null;
  onClose: () => void;
}) {
  const [tools, setTools] = useState<McpToolDetail[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [query, setQuery] = useState("");
  const [expanded, setExpanded] = useState<Set<string>>(new Set());

  const load = useCallback(async (id: number) => {
    setLoading(true);
    try {
      setTools(await fetchMcpTools(id));
    } catch (e) {
      toast.error(`工具列表加载失败：${e instanceof Error ? e.message : String(e)}`);
      setTools([]);
    } finally {
      setLoading(false);
    }
  }, []);

  // 打开/切换服务器时重置并拉取
  useEffect(() => {
    if (!server) return;
    setTools(null);
    setQuery("");
    setExpanded(new Set());
    void load(server.id);
  }, [server, load]);

  /** 测试连接(刷新 tools_cache)后重新拉取,保证看到的是最新快照 */
  const handleRefresh = async () => {
    if (!server) return;
    setRefreshing(true);
    try {
      const result = await refreshMcpServer(server.id);
      const count = result.tools?.length ?? 0;
      toast.success(count > 0 ? `连接成功,${count} 个工具已更新` : "连接成功,但该服务器没有提供任何工具");
      await load(server.id);
    } catch (e) {
      toast.error(`测试连接失败：${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setRefreshing(false);
    }
  };

  const toggle = (name: string) => {
    setExpanded((prev) => {
      const next = new Set(prev);
      if (next.has(name)) next.delete(name);
      else next.add(name);
      return next;
    });
  };

  const filtered = useMemo(() => {
    if (!tools) return null;
    const q = query.trim().toLowerCase();
    if (!q) return tools;
    return tools.filter((t) =>
      t.name.toLowerCase().includes(q) || (t.description ?? "").toLowerCase().includes(q));
  }, [tools, query]);

  if (!server) return null;

  return (
    <Modal
      isOpen={!!server}
      onClose={onClose}
      title={
        <span className="flex items-center gap-2">
          <Wrench className="w-3.5 h-3.5 text-blue-500 dark:text-blue-400" />
          工具列表 · <span className="font-mono">{server.name}</span>
          <span className="text-xs font-normal text-muted-foreground">
            {tools ? `${tools.length} 个` : ""}
          </span>
        </span>
      }
      width="w-[720px]"
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose}>关闭</Button>
          <Button size="sm" disabled={refreshing || loading} onClick={() => void handleRefresh()}>
            {refreshing ? <Loader2 className="w-3.5 h-3.5 mr-1.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5 mr-1.5" />}
            {refreshing ? "测试中…" : "测试连接并刷新"}
          </Button>
        </>
      }
    >
      <div className="space-y-3">
        {/* 搜索 + 挂载名说明 */}
        <div className="flex items-center gap-2">
          <div className="relative flex-1">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-muted-foreground" />
            <Input
              placeholder="搜索工具名或描述…"
              className="h-8 text-xs pl-8"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
        </div>

        {loading || tools === null ? (
          <div className="py-12 flex flex-col items-center justify-center text-muted-foreground">
            <Loader2 className="w-6 h-6 mb-2 animate-spin opacity-30" />
            <div className="text-xs">加载中…</div>
          </div>
        ) : tools.length === 0 ? (
          <div className="py-12 flex flex-col items-center justify-center text-muted-foreground">
            <Wrench className="w-8 h-8 mb-3 opacity-20" />
            <div className="text-sm">尚无工具快照</div>
            <div className="text-xs mt-1 text-muted-foreground/70">点击「测试连接并刷新」从服务器拉取工具清单</div>
          </div>
        ) : filtered && filtered.length === 0 ? (
          <div className="py-10 text-center text-xs text-muted-foreground">没有匹配「{query}」的工具</div>
        ) : (
          <div className="divide-y divide-border/60 border border-border rounded-lg overflow-hidden">
            {filtered!.map((tool) => {
              const isOpen = expanded.has(tool.name);
              return (
                <div key={tool.name}>
                  <button
                    type="button"
                    onClick={() => toggle(tool.name)}
                    className="w-full flex items-start gap-2 px-3 py-2.5 text-left hover:bg-muted/40 transition-colors cursor-pointer"
                  >
                    {isOpen
                      ? <ChevronDown className="w-3.5 h-3.5 mt-0.5 shrink-0 text-muted-foreground" />
                      : <ChevronRight className="w-3.5 h-3.5 mt-0.5 shrink-0 text-muted-foreground" />}
                    <div className="min-w-0 flex-1">
                      <div className="text-xs font-mono font-medium text-foreground break-all">
                        {tool.name}
                        <span className="ml-2 text-[10px] font-sans font-normal text-muted-foreground">
                          挂载为 mcp__{server.name}__{tool.name}
                        </span>
                      </div>
                      {!isOpen && tool.description && (
                        <div className="text-[11px] text-muted-foreground mt-0.5 truncate">{tool.description}</div>
                      )}
                    </div>
                  </button>
                  {isOpen && (
                    <div className="px-3 pb-3 pl-9 space-y-2 animate-in fade-in">
                      {tool.description ? (
                        <p className="text-[11px] text-muted-foreground leading-relaxed whitespace-pre-wrap break-words">
                          {tool.description}
                        </p>
                      ) : (
                        <p className="text-[11px] text-muted-foreground/60 italic">该工具未提供描述</p>
                      )}
                      {tool.inputSchema && (
                        <div>
                          <div className="text-[10px] font-bold text-muted-foreground mb-1">参数 Schema</div>
                          <pre className="text-[10px] font-mono bg-muted/50 border border-border rounded-md p-2.5 overflow-x-auto max-h-64 custom-scroll text-foreground/80">
                            {JSON.stringify(tool.inputSchema, null, 2)}
                          </pre>
                        </div>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}

        <p className="text-[10px] text-muted-foreground leading-relaxed">
          工具快照来自最近一次测试连接(tools/list);调用时按高风险处理,审批跟随当前权限档位。
        </p>
      </div>
    </Modal>
  );
}
