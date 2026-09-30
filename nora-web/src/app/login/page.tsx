'use client';

import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { KeyRound, Loader2, Eye, EyeOff } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { setAuthToken, getAuthToken } from "@/lib/auth";

/**
 * 令牌登录页(2026-09-19):单用户自部署工作台的访问门。
 *
 * 流程:服务端配了 NORA_AUTH_TOKEN 时全 API 需令牌 → 前端任意请求 401
 * 跳到这里 → 输入令牌 → POST /api/auth/login 校验 → 存 localStorage
 * → 跳回原地址(back 参数)。
 *
 * 未配令牌(免登录)时本页直接跳首页——不挡本地开发。
 */
export default function LoginPage() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const back = params.get("back") || "/";
  const [token, setToken] = useState("");
  const [showToken, setShowToken] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [hint, setHint] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [checking, setChecking] = useState(true);

  // 进入时先问服务端"需要登录吗":免登录直接放行;已带有效令牌也直接放行
  useEffect(() => {
    (async () => {
      try {
        const res = await fetch(`/api/auth/status`, {
          headers: getAuthToken() ? { Authorization: `Bearer ${getAuthToken()}` } : {},
        });
        const body = await res.json();
        const data = body?.data ?? body;
        if (data?.authRequired === false || data?.authenticated === true) {
          navigate(back, { replace: true });
          return;
        }
      } catch {
        /* 网关不可达:留在本页,提交时会再报错 */
      }
      setChecking(false);
    })();
  }, [back, navigate]);

  const submit = async () => {
    const value = token.trim();
    if (!value) {
      setError("请输入访问令牌");
      return;
    }
    setBusy(true);
    setError(null);
    setHint(null);
    try {
      const res = await fetch(`/api/auth/login`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ token: value }),
      });
      if (res.ok) {
        setAuthToken(value);
        navigate(back, { replace: true });
        return;
      }
      const body = await res.json().catch(() => null);
      setError(body?.message || `登录失败(HTTP ${res.status})`);
      setHint(body?.hint || null);
    } catch {
      setError("无法连接服务,请确认 Nora 已启动");
    } finally {
      setBusy(false);
    }
  };

  if (checking) {
    return (
      <div className="h-screen flex items-center justify-center bg-background">
        <Loader2 className="w-5 h-5 animate-spin text-muted-foreground" />
      </div>
    );
  }

  return (
    <div className="h-screen flex items-center justify-center bg-background p-4">
      <div className="w-full max-w-sm">
        <div className="flex flex-col items-center mb-6">
          <img src="/logo-mark.svg" alt="Nora" width={48} height={48} className="w-12 h-12 mb-3" />
          <h1 className="text-lg font-bold text-foreground">Nora</h1>
          <p className="text-xs text-muted-foreground mt-1">输入访问令牌以继续</p>
        </div>

        <div className="bg-card border border-border rounded-xl shadow-sm p-6 space-y-4">
          <div className="space-y-1.5">
            <label className="text-xs font-bold text-foreground flex items-center gap-1.5">
              <KeyRound className="w-3.5 h-3.5" /> 访问令牌
            </label>
            <div className="relative">
              <Input
                type={showToken ? "text" : "password"}
                value={token}
                autoFocus
                placeholder="服务端配置的 NORA_AUTH_TOKEN"
                onChange={(e) => { setToken(e.target.value); setError(null); }}
                onKeyDown={(e) => { if (e.key === "Enter") void submit(); }}
                className="h-10 pr-9 font-mono text-sm"
              />
              <button
                type="button"
                title={showToken ? "隐藏" : "显示"}
                onClick={() => setShowToken((v) => !v)}
                className="absolute right-2 top-1/2 -translate-y-1/2 p-1 text-muted-foreground hover:text-foreground cursor-pointer"
              >
                {showToken ? <EyeOff className="w-4 h-4" /> : <Eye className="w-4 h-4" />}
              </button>
            </div>
            {error && <p className="text-xs text-red-600 dark:text-red-400">{error}</p>}
            {hint && <p className="text-[11px] text-muted-foreground">{hint}</p>}
          </div>

          <Button
            className="w-full h-10 bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 text-white"
            onClick={() => void submit()}
            disabled={busy}
          >
            {busy ? <><Loader2 className="w-4 h-4 mr-2 animate-spin" /> 验证中…</> : "登录"}
          </Button>

          <p className="text-[10px] text-muted-foreground text-center leading-relaxed">
            令牌在服务端 <code className="font-mono">.env.local</code> 的
            <code className="font-mono"> NORA_AUTH_TOKEN</code> 配置;
            留空则不启用登录。
          </p>
        </div>
      </div>
    </div>
  );
}
