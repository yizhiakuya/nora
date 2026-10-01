import { useCallback, useEffect, useRef, useState } from "react";
import { CheckCircle2, Globe, Loader2, Network, XCircle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Switch } from "@/components/ui/switch";
import { requestJson } from "@/lib/api/client";import { toast } from "sonner";

/** GET/PUT /api/network/proxy 响应 */
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
 * 网络设置:出站代理直接在页面配置(host/port/开关),保存即持久化到后端
 * app_setting 表并动态生效——无需环境变量、无需重启。
 * 连通性测试(直连 vs 走代理)帮助确认代理是否真的解锁目标。
 */
export function NetworkSettings() {
  const [proxy, setProxy] = useState<ProxyView | null>(null);
  const [enabled, setEnabled] = useState(false);
  const [host, setHost] = useState("127.0.0.1");
  const [port, setPort] = useState("7897");
  const [saving, setSaving] = useState(false);
  const [probeUrl, setProbeUrl] = useState("https://opencode.ai");
  const [probing, setProbing] = useState(false);
  const [result, setResult] = useState<ProbeResult | null>(null);
  const mounted = useRef(true);

  const loadProxy = useCallback(() => {
    requestJson<ProxyView>("/network/proxy")
      .then((p) => {
        if (!mounted.current) return;
        setProxy(p);
        setEnabled(p.enabled);
        if (p.host) setHost(p.host);
        if (p.port) setPort(String(p.port));
      })
      .catch(() => { /* 后端不可用时保持 null */ });
  }, []);

  useEffect(() => {
    mounted.current = true;
    loadProxy();
    return () => { mounted.current = false; };
  }, [loadProxy]);

  const save = useCallback(async (nextEnabled: boolean) => {
    if (saving) return;
    if (nextEnabled) {
      if (!host.trim()) { toast.error("请填写代理主机地址"); return; }
      const portNum = Number(port);
      if (!Number.isInteger(portNum) || portNum <= 0 || portNum > 65535) {
        toast.error("端口必须是 1-65535 的整数");
        return;
      }
    }
    setSaving(true);
    try {
      const p = await requestJson<ProxyView>("/network/proxy", {
        method: "PUT",
        body: JSON.stringify({
          enabled: nextEnabled,
          host: host.trim(),
          port: nextEnabled ? Number(port) : null,
        }),
      });
      if (!mounted.current) return;
      setProxy(p);
      setEnabled(p.enabled);
      toast.success(p.enabled ? `代理已启用并生效:${p.url}` : "代理已停用,外网请求恢复直连");
    } catch (e) {
      // 后端保存前会先探测代理可达性,失败时给出行内提示
      toast.error((e as Error).message || "保存失败");
      // 以后端实际状态为准(可能保存被拒)
      loadProxy();
    } finally {
      if (mounted.current) setSaving(false);
    }
  }, [host, port, saving, loadProxy]);

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
      {/* 出站代理:页面直接配置,保存即生效 */}
      <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm p-5">
        <div className="flex items-center justify-between mb-1">
          <div className="flex items-center gap-2">
            <Network className="w-4 h-4 text-blue-500" />
            <h2 className="text-sm font-bold text-foreground">出站代理</h2>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-[10px] text-muted-foreground">{enabled ? "已启用" : "未启用"}</span>
            <Switch
              checked={enabled}
              disabled={saving}
              onCheckedChange={(v) => { setEnabled(v); void save(v); }}
              title={enabled ? "点击停用代理" : "点击启用代理"}
            />
          </div>
        </div>
        <p className="text-xs text-muted-foreground leading-relaxed mb-3">
          后端对外网（LLM 上游、embedding 提供方等）的请求经代理转发；内网服务互调始终直连。
          保存后立即生效，无需重启。启用时会先探测代理可达性，避免保存一个坏配置。
        </p>
        <div className="flex items-end gap-2 max-w-md">
          <div className="flex-1">
            <label className="text-[10px] text-muted-foreground mb-1 block">主机地址</label>
            <Input
              className="h-8 text-xs font-mono"
              placeholder="127.0.0.1"
              value={host}
              onChange={(e) => setHost(e.target.value)}
              disabled={saving}
            />
          </div>
          <div className="w-24">
            <label className="text-[10px] text-muted-foreground mb-1 block">端口</label>
            <Input
              className="h-8 text-xs font-mono"
              placeholder="7897"
              inputMode="numeric"
              value={port}
              onChange={(e) => setPort(e.target.value.replace(/[^\d]/g, ""))}
              disabled={saving}
            />
          </div>
          <Button size="sm" variant="outline" className="h-8 px-3 text-xs shrink-0" onClick={() => void save(enabled)} disabled={saving}>
            {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : null}
            保存
          </Button>
        </div>
        {proxy && (
          <p className="text-[10px] text-muted-foreground mt-2">
            {proxy.enabled
              ? <>当前生效:<span className="font-mono text-foreground">{proxy.url}</span></>
              : "当前直连外网。"}
          </p>
        )}
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
          <Button size="sm" className="h-9 px-3 shrink-0" onClick={() => void probe()} disabled={probing}>
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
