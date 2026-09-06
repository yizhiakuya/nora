import { useCallback, useEffect, useRef, useState } from "react";
import { CheckCircle2, Globe, Loader2, Network, XCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { requestJson, USE_BACKEND } from "@/lib/api/client";
import { toast } from "sonner";

/** GET /api/network/proxy 响应 */
interface ProxyView {
  enabled: boolean;
  host: string | null;
  port: number | null;
  url: string | null;
}

/** POST /api/network/probe 响应,status 0 = 传输层失败 */
interface ProbeResult {
  directStatus: number;
  directMs: number;
  proxyStatus: number | null;
  proxyMs: number | null;
}

function StatusLine({ label, status, ms }: { label: string; status: number | null; ms?: number | null }) {
  const ok = status != null && status >= 200 && status < 400;
  return (
    <div className="flex items-center gap-2 text-xs">
      {status == null ? (
        <span className="text-muted-foreground">—</span>
      ) : ok ? (
        <CheckCircle2 className="w-3.5 h-3.5 text-green-600 dark:text-green-500 shrink-0" />
      ) : (
        <XCircle className="w-3.5 h-3.5 text-red-500 shrink-0" />
      )}
      <span className="text-foreground">{label}</span>
      <span className="text-muted-foreground tabular-nums ml-auto shrink-0">
        {status == null ? "未测试" : status === 0 ? "连接失败" : `HTTP ${status}${ms != null ? ` · ${(ms / 1000).toFixed(1)}s` : ""}`}
      </span>
    </div>
  );
}

/**
 * 网络设置:出站代理状态展示 + 外网连通性测试(直连 vs 走代理对比)。
 * 代理本身经 application.yml / 环境变量(NORA_PROXY_*)配置,页面只读+验证,
 * 重启后端进程才会生效——避免"页面改了但运行中的 JVM 不感知"的错觉。
 */
export function NetworkSettings() {
  const [proxy, setProxy] = useState<ProxyView | null>(null);
  const [probeUrl, setProbeUrl] = useState("https://opencode.ai");
  const [probing, setProbing] = useState(false);
  const [result, setResult] = useState<ProbeResult | null>(null);
  const mounted = useRef(true);

  useEffect(() => {
    mounted.current = true;
    if (!USE_BACKEND) return;
    requestJson<ProxyView>("/network/proxy")
      .then((p) => { if (mounted.current) setProxy(p); })
      .catch(() => { /* 后端不可用时保持 null */ });
    return () => { mounted.current = false; };
  }, []);

  const probe = useCallback(async () => {
    if (!probeUrl.trim() || probing) return;
    setProbing(true);
    setResult(null);
    try {
      const r = await requestJson<ProbeResult>("/network/probe", {
        method: "POST",
        body: JSON.stringify({ url: probeUrl.trim() }),
      });
      if (mounted.current) setResult(r);
    } catch (e) {
      toast.error(`测试失败: ${(e as Error).message}`);
    } finally {
      if (mounted.current) setProbing(false);
    }
  }, [probeUrl, probing]);

  return (
    <div className="space-y-4">
      {/* 出站代理状态 */}
      <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm p-5">
        <div className="flex items-center justify-between mb-1">
          <div className="flex items-center gap-2">
            <Network className="w-4 h-4 text-blue-500" />
            <h2 className="text-sm font-bold text-foreground">出站代理</h2>
          </div>
          {proxy && (
            <div className="flex items-center gap-2">
              <span className="text-[10px] text-muted-foreground">{proxy.enabled ? "已启用" : "未启用"}</span>
              <Switch checked={proxy.enabled} disabled title="由服务端配置决定(NORA_PROXY_*)，页面只读" />
            </div>
          )}
        </div>
        <p className="text-xs text-muted-foreground leading-relaxed">
          后端对外网（LLM 上游、embedding 提供方等）的请求经代理转发；内网服务互调始终直连。
          <br />
          {proxy?.enabled ? (
            <>当前代理:<span className="font-mono text-foreground">{proxy.url}</span></>
          ) : (
            <>当前未启用。配置方式:设置环境变量 <code className="font-mono text-[10px] bg-muted px-1 rounded">NORA_PROXY_ENABLED=true</code>、<code className="font-mono text-[10px] bg-muted px-1 rounded">NORA_PROXY_HOST</code>、<code className="font-mono text-[10px] bg-muted px-1 rounded">NORA_PROXY_PORT</code> 后重启后端。</>
          )}
        </p>
      </div>

      {/* 连通性测试 */}
      <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm p-5">
        <div className="flex items-center gap-2 mb-3">
          <Globe className="w-4 h-4 text-violet-500" />
          <h2 className="text-sm font-bold text-foreground">连通性测试</h2>
        </div>
        <div className="flex gap-2 mb-3">
          <Input
            placeholder="https://目标地址（如 https://opencode.ai）"
            className="h-9 text-xs font-mono"
            value={probeUrl}
            onChange={(e) => setProbeUrl(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") void probe(); }}
          />
          <Button size="sm" className="h-9 px-3 shrink-0" onClick={() => void probe()} disabled={probing || !USE_BACKEND}>
            {probing ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Globe className="w-3.5 h-3.5" />}
            {probing ? "测试中…" : "测试"}
          </Button>
        </div>
        {result ? (
          <div className="space-y-1.5 rounded-lg border border-border p-3">
            <StatusLine label="直连" status={result.directStatus} ms={result.directMs} />
            <StatusLine label="走代理" status={result.proxyStatus} ms={result.proxyMs} />
            <p className="text-[10px] text-muted-foreground pt-1">
              {result.proxyStatus == null
                ? "代理未启用,仅测试直连"
                : result.directStatus !== 0 && result.proxyStatus === 0
                  ? "直连可用、代理失败——该目标不需要代理"
                  : result.directStatus === 0 && result.proxyStatus != null && result.proxyStatus !== 0
                    ? "直连失败、代理可用——该目标需要代理"
                    : "以两条结果综合判断"}
            </p>
          </div>
        ) : (
          <p className="text-[10px] text-muted-foreground">
            输入外网地址测试直连与代理两条通路的可达性（如模型上游、https://api.jina.ai）。
          </p>
        )}
      </div>
    </div>
  );
}
