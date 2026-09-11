'use client';

import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Modal } from "@/components/ui/custom/Modal";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { createMcpServer } from "@/lib/api/mcpApi";

type Transport = "STREAMABLE" | "SSE" | "STDIO";

interface NewMcpServerModalProps {
  isOpen: boolean;
  onClose: () => void;
  /** 注册成功回调(父组件刷新列表) */
  onCreated?: () => void;
}

/**
 * 注册 MCP 服务器弹窗(对齐数据源/技能页的模态框模式):
 * 远程(URL + 鉴权头)与本地进程(STDIO:命令/参数/环境变量)两种形态。
 */
export function NewMcpServerModal({ isOpen, onClose, onCreated }: NewMcpServerModalProps) {
  const [name, setName] = useState("");
  const [transport, setTransport] = useState<Transport>("STREAMABLE");
  const [url, setUrl] = useState("");
  const [headerKey, setHeaderKey] = useState("");
  const [headerValue, setHeaderValue] = useState("");
  const [command, setCommand] = useState("");
  const [args, setArgs] = useState("");
  const [envKey, setEnvKey] = useState("");
  const [envValue, setEnvValue] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // 每次打开重置表单(与 NewConnectionModal 同款)
  useEffect(() => {
    if (isOpen) {
      setName("");
      setTransport("STREAMABLE");
      setUrl("");
      setHeaderKey("");
      setHeaderValue("");
      setCommand("");
      setArgs("");
      setEnvKey("");
      setEnvValue("");
      setError(null);
    }
  }, [isOpen]);

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

  const handleSubmit = async () => {
    const n = name.trim();
    if (!n) {
      setError("服务器名称不能为空");
      return;
    }
    if (transport === "STDIO") {
      if (!command.trim()) {
        setError("启动命令不能为空(如 npx / node / docker)");
        return;
      }
      if ((envKey.trim() && !envValue.trim()) || (!envKey.trim() && envValue.trim())) {
        setError("环境变量的键和值需同时填写,或都留空");
        return;
      }
      const env: Record<string, string> = {};
      if (envKey.trim()) env[envKey.trim()] = envValue.trim();
      setSubmitting(true);
      try {
        await createMcpServer({ name: n, transport: "STDIO", command: command.trim(), args: splitArgs(args), env });
        toast.success(`服务器「${n}」已添加,点击「测试连接」启动进程并拉取工具列表`);
        onCreated?.();
        onClose();
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
      } finally {
        setSubmitting(false);
      }
      return;
    }
    const u = url.trim();
    if (!/^https?:\/\//.test(u)) {
      setError("URL 必须以 http(s):// 开头");
      return;
    }
    if ((headerKey.trim() && !headerValue.trim()) || (!headerKey.trim() && headerValue.trim())) {
      setError("Header 的键和值需同时填写,或都留空");
      return;
    }
    const headers: Record<string, string> = {};
    if (headerKey.trim()) headers[headerKey.trim()] = headerValue.trim();
    setSubmitting(true);
    try {
      await createMcpServer({ name: n, url: u, transport, headers });
      toast.success(`服务器「${n}」已添加,点击「测试连接」拉取工具列表`);
      onCreated?.();
      onClose();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={onClose}
      title="注册 MCP 服务器"
      width="w-[94%] sm:w-[620px]"
      footer={
        <>
          <Button variant="outline" size="sm" onClick={onClose}>取消</Button>
          <Button size="sm" className="bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={() => void handleSubmit()} disabled={submitting}>
            {submitting ? "注册中…" : "注册"}
          </Button>
        </>
      }
    >
      <div className="space-y-4">
        <div className="grid grid-cols-[1fr_200px] gap-3">
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">服务器名称</label>
            <Input placeholder="例如：search-tools" className="h-9 text-sm" value={name} onChange={(e) => { setName(e.target.value); setError(null); }} />
          </div>
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground">传输协议</label>
            <Select value={transport} onValueChange={(v) => { setTransport(v as Transport); setError(null); }}>
              <SelectTrigger className="w-full">
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="STREAMABLE">Streamable HTTP</SelectItem>
                <SelectItem value="SSE">SSE</SelectItem>
                <SelectItem value="STDIO">STDIO（本地进程）</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>

        {transport === "STDIO" ? (
          <>
            <div className="space-y-1.5">
              <label className="text-xs font-bold text-foreground">启动命令</label>
              <Input placeholder="npx / node / docker / uvx" className="h-9 text-sm font-mono" value={command} onChange={(e) => { setCommand(e.target.value); setError(null); }} />
              <p className="text-[10px] text-muted-foreground">需本机已安装对应运行时；也可用 Docker 方式：docker run -i --rm 镜像名</p>
            </div>
            <div className="space-y-1.5">
              <label className="text-xs font-bold text-foreground">命令参数（可选，空格分隔；含空格用引号）</label>
              <Input placeholder='-y @modelcontextprotocol/server-filesystem "D:/docs"' className="h-9 text-sm font-mono" value={args} onChange={(e) => setArgs(e.target.value)} />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <label className="text-xs font-bold text-foreground">环境变量（可选）</label>
                <Input placeholder="API_KEY" className="h-9 text-sm font-mono" value={envKey} onChange={(e) => setEnvKey(e.target.value)} />
              </div>
              <div className="space-y-1.5">
                <label className="text-xs font-bold text-foreground">值</label>
                <Input type="password" placeholder="••••••••" className="h-9 text-sm font-mono" value={envValue} onChange={(e) => setEnvValue(e.target.value)} />
              </div>
            </div>
          </>
        ) : (
          <>
            <div className="space-y-1.5">
              <label className="text-xs font-bold text-foreground">URL</label>
              <Input placeholder="https://mcp.example.com/mcp" className="h-9 text-sm font-mono" value={url} onChange={(e) => { setUrl(e.target.value); setError(null); }} />
            </div>
            <div className="grid grid-cols-2 gap-3">
              <div className="space-y-1.5">
                <label className="text-xs font-bold text-foreground">认证 Header（可选）</label>
                <Input placeholder="Authorization" className="h-9 text-sm font-mono" value={headerKey} onChange={(e) => setHeaderKey(e.target.value)} />
              </div>
              <div className="space-y-1.5">
                <label className="text-xs font-bold text-foreground">值</label>
                <Input type="password" placeholder="Bearer sk-…" className="h-9 text-sm font-mono" value={headerValue} onChange={(e) => setHeaderValue(e.target.value)} />
              </div>
            </div>
          </>
        )}

        {error && <p className="text-[11px] text-red-500 dark:text-red-400">{error}</p>}
      </div>
    </Modal>
  );
}
