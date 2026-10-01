'use client';

import { useCallback, useEffect, useRef, useState } from "react";
import { toast } from "sonner";
import { LogIn, Loader2, Copy, CheckCircle2, ExternalLink, AlertTriangle } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Modal } from "@/components/ui/custom/Modal";
import {
  fetchGitHubOAuthStatus,
  saveGitHubClientId,
  startGitHubOAuth,
  pollGitHubOAuth,
  type GitHubOAuthStatus,
  type GitHubOAuthStart,
} from "@/lib/api/mcpApi";

type Phase = "loading" | "need-client-id" | "ready" | "waiting" | "done" | "failed";

/**
 * GitHub 一键登录(OAuth 设备码流程,与 gh CLI 同款):
 * 未配置 client_id → 引导粘贴(一次性,创建 OAuth App 时勾 Enable Device Flow);
 * 已配置 → 发起授权 → 展示验证码 → 用户在浏览器输入 → 轮询完成自动配置 MCP。
 */
export function GitHubOAuthModal({ isOpen, onClose, onLoggedIn }: {
  isOpen: boolean;
  onClose: () => void;
  /** 登录完成(server 已配置好)后回调:父组件刷新列表 */
  onLoggedIn: () => void;
}) {
  const [phase, setPhase] = useState<Phase>("loading");
  const [status, setStatus] = useState<GitHubOAuthStatus | null>(null);
  const [clientIdInput, setClientIdInput] = useState("");
  const [saving, setSaving] = useState(false);
  const [flow, setFlow] = useState<GitHubOAuthStart | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [warning, setWarning] = useState<string | null>(null);
  const [toolCount, setToolCount] = useState<number | null>(null);
  const [copied, setCopied] = useState(false);
  const pollTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const stopped = useRef(false);

  // 打开时拉取状态
  useEffect(() => {
    if (!isOpen) return;
    stopped.current = false;
    setPhase("loading");
    setMessage(null);
    setWarning(null);
    setToolCount(null);
    setFlow(null);
    void fetchGitHubOAuthStatus()
      .then((s) => {
        setStatus(s);
        setPhase(s.clientIdConfigured ? "ready" : "need-client-id");
      })
      .catch((e) => {
        setPhase("failed");
        setMessage(`状态加载失败：${e instanceof Error ? e.message : String(e)}`);
      });
  }, [isOpen]);

  // 关闭时停止轮询
  useEffect(() => {
    if (!isOpen) {
      stopped.current = true;
      if (pollTimer.current) clearTimeout(pollTimer.current);
    }
  }, [isOpen]);

  const schedulePoll = useCallback((flowId: string, intervalSec: number) => {
    // 用局部递归函数承载续轮询,避免回调自引用 useCallback 变量
    // (react-hooks/immutability:自引用闭包在并发渲染下可能拿到旧值)
    const poll = (fid: string, sec: number) => {
      pollTimer.current = setTimeout(async () => {
        if (stopped.current) return;
        try {
          const result = await pollGitHubOAuth(fid);
          if (stopped.current) return;
          switch (result.status) {
            case "pending":
              poll(fid, sec);
              break;
            case "slow_down":
              poll(fid, sec + 5);
              break;
            case "complete":
              setPhase("done");
              setToolCount(result.toolCount);
              setWarning(result.warning);
              onLoggedIn();
              break;
            case "denied":
            case "expired":
            case "error":
            default:
              setPhase("failed");
              setMessage(result.message ?? "授权失败");
              break;
          }
        } catch (e) {
          // 网络抖动:继续轮询(后端也容错)
          if (!stopped.current) poll(fid, sec);
        }
      }, Math.max(5, sec) * 1000);
    };
    poll(flowId, intervalSec);
  }, [onLoggedIn]);

  const handleSaveClientId = async () => {
    const id = clientIdInput.trim();
    if (!id) return void toast.error("请粘贴 OAuth App 的 Client ID");
    setSaving(true);
    try {
      const s = await saveGitHubClientId(id);
      setStatus(s);
      setPhase("ready");
      toast.success("Client ID 已保存");
    } catch (e) {
      toast.error(`保存失败：${e instanceof Error ? e.message : String(e)}`);
    } finally {
      setSaving(false);
    }
  };

  const handleStart = async () => {
    setMessage(null);
    try {
      const f = await startGitHubOAuth();
      setFlow(f);
      setPhase("waiting");
      schedulePoll(f.flowId, f.interval);
    } catch (e) {
      setPhase("failed");
      setMessage(e instanceof Error ? e.message : String(e));
    }
  };

  const copyCode = async () => {
    if (!flow) return;
    try {
      await navigator.clipboard.writeText(flow.userCode);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // 剪贴板不可用:用户手动选中复制
    }
  };

  const handleClose = () => {
    stopped.current = true;
    if (pollTimer.current) clearTimeout(pollTimer.current);
    onClose();
  };

  return (
    <Modal
      isOpen={isOpen}
      onClose={handleClose}
      title="登录 GitHub"
      width="w-[94%] sm:w-[520px]"
      footer={
        phase === "done" ? (
          <Button size="sm" className="bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={handleClose}>
            完成
          </Button>
        ) : (
          <Button variant="outline" size="sm" onClick={handleClose}>关闭</Button>
        )
      }
    >
      <div className="space-y-4">
        {phase === "loading" && (
          <div className="py-8 flex items-center justify-center text-muted-foreground">
            <Loader2 className="w-5 h-5 animate-spin mr-2" /> 检查登录配置…
          </div>
        )}

        {phase === "need-client-id" && (
          <>
            <p className="text-xs text-muted-foreground leading-relaxed">
              一次性配置(所有环境复用):打开
              <a href="https://github.com/settings/developers" target="_blank" rel="noreferrer"
                 className="text-blue-600 dark:text-blue-400 hover:underline inline-flex items-center gap-0.5 mx-1">
                GitHub OAuth Apps <ExternalLink className="w-3 h-3" />
              </a>
              → New OAuth App → 勾选 <b>Enable Device Flow</b>(回调地址随意填)→ 创建后把
              <b> Client ID</b> 粘贴到这里。Client ID 非机密。
            </p>
            <div className="space-y-1.5">
              <label className="text-xs font-bold text-foreground">GitHub OAuth Client ID</label>
              <Input placeholder="Ov23li…" className="h-9 text-sm font-mono" value={clientIdInput}
                     onChange={(e) => setClientIdInput(e.target.value)} />
            </div>
            <Button size="sm" className="bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600"
                    disabled={saving} onClick={() => void handleSaveClientId()}>
              {saving ? <Loader2 className="w-3.5 h-3.5 mr-1 animate-spin" /> : null} 保存并继续
            </Button>
          </>
        )}

        {phase === "ready" && (
          <>
            {status?.serverName === "github" && status.serverStatus === "connected" && (
              <div className="flex items-center gap-2 text-xs text-green-600 dark:text-green-400">
                <CheckCircle2 className="w-4 h-4" />
                当前已连接 GitHub(「github」· {status.toolCount ?? 0} 个工具)——重新登录会更新凭据
              </div>
            )}
            <p className="text-xs text-muted-foreground leading-relaxed">
              点击下方按钮后,Nora 会生成一个验证码;在浏览器打开 GitHub 输入验证码并授权,
              即可自动完成配置(无需手动创建 token)。
            </p>
            <div className="flex items-center gap-3">
              <Button size="sm" className="bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600"
                      onClick={() => void handleStart()}>
                <LogIn className="w-3.5 h-3.5 mr-1.5" /> 开始授权
              </Button>
              <button type="button"
                      className="text-[11px] text-muted-foreground hover:text-foreground underline underline-offset-2"
                      onClick={() => { setClientIdInput(""); setPhase("need-client-id"); }}>
                更改 Client ID
              </button>            </div>
          </>
        )}

        {phase === "waiting" && flow && (
          <>
            <div className="rounded-lg border border-border bg-muted/50 p-4 space-y-3">
              <div className="text-xs text-muted-foreground">1. 复制验证码,在 GitHub 页面输入:</div>
              <div className="flex items-center gap-2">
                <code className="text-xl font-mono font-bold tracking-widest text-foreground select-all">
                  {flow.userCode}
                </code>
                <Button variant="ghost" size="icon" className="w-7 h-7 text-muted-foreground hover:text-foreground"
                        title="复制验证码" onClick={() => void copyCode()}>
                  {copied ? <CheckCircle2 className="w-3.5 h-3.5 text-green-500" /> : <Copy className="w-3.5 h-3.5" />}
                </Button>
              </div>
              <div className="text-xs text-muted-foreground">2. 打开验证页并授权:</div>
              <a href={flow.verificationUri} target="_blank" rel="noreferrer"
                 className="text-sm text-blue-600 dark:text-blue-400 hover:underline inline-flex items-center gap-1 font-mono">
                {flow.verificationUri} <ExternalLink className="w-3.5 h-3.5" />
              </a>
            </div>
            <div className="flex items-center gap-2 text-xs text-muted-foreground">
              <Loader2 className="w-3.5 h-3.5 animate-spin" /> 等待你在 GitHub 完成授权…(此窗口保持打开)
            </div>
          </>
        )}

        {phase === "done" && (
          <div className="space-y-3">
            <div className="flex items-center gap-2 text-sm text-green-600 dark:text-green-400 font-medium">
              <CheckCircle2 className="w-5 h-5" /> 登录成功,已自动配置 GitHub MCP
            </div>
            {toolCount != null && (
              <div className="text-xs text-muted-foreground">
                发现 {toolCount} 个工具(含 get_me 查账号、仓库/PR/Issue 全套),下轮对话即可使用。
              </div>
            )}
            {warning && (
              <div className="flex items-start gap-2 text-[11px] text-amber-600 dark:text-amber-400">
                <AlertTriangle className="w-3.5 h-3.5 mt-0.5 shrink-0" /> {warning}
              </div>
            )}
          </div>
        )}

        {phase === "failed" && (
          <div className="space-y-3">
            <div className="flex items-start gap-2 text-xs text-red-500 dark:text-red-400">
              <AlertTriangle className="w-4 h-4 mt-0.5 shrink-0" /> {message ?? "授权失败"}
            </div>
            <Button variant="outline" size="sm" onClick={() => setPhase(status?.clientIdConfigured ? "ready" : "need-client-id")}>
              重试
            </Button>
          </div>
        )}
      </div>
    </Modal>
  );
}
