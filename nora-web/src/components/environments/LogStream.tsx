'use client';

import { useEffect, useMemo, useRef, useState } from "react";
import { Sparkles, Terminal, Zap, Check, Activity, Wifi, WifiOff, ArrowDownToLine } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Markdown } from "@/components/shared/Markdown";
import type { LogEntry, ServiceInstance } from "@/types";
import { useServices } from "@/hooks/useServices";
import { useAutomations } from "@/hooks/useAutomations";
import { useNotifications } from "@/hooks/useNotifications";
import { environmentApi, subscribeSourceLogs } from "@/lib/services/environmentApi";
import { USE_BACKEND } from "@/lib/api/client";
import { toast } from "sonner";

const LEVEL_CLS: Record<LogEntry["level"], string> = {
  info:  "text-blue-600 dark:text-blue-400",
  warn:  "text-amber-600 dark:text-amber-400",
  error: "text-red-600 dark:text-red-400",
};

const LEVEL_BADGE_CLS: Record<LogEntry["level"], string> = {
  info:  "bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-300",
  warn:  "bg-amber-50 dark:bg-amber-950/40 text-amber-700 dark:text-amber-300",
  error: "bg-red-50 dark:bg-red-950/40 text-red-700 dark:text-red-300",
};

export function LogStream() {
  const [filter, setFilter] = useState<"all" | LogEntry["level"]>("all");
  const [selected, setSelected] = useState<LogEntry | null>(null);
  const [taskCreated, setTaskCreated] = useState(false);
  const services = useServices((s) => s.services);
  const activeServiceId = useServices((s) => s.activeServiceId);
  const addRule = useAutomations((s) => s.addRule);
  const ingestDockerLog = useServices((s) => s.ingestDockerLog);
  const clearLogsFor = useServices((s) => s.clearLogsFor);
  const addNotification = useNotifications((s) => s.addNotification);
  /** SSE 连接状态(open/reconnecting/closed 时在头部提示) */
  const [connState, setConnState] = useState<"connecting" | "open" | "reconnecting" | "closed">("open");
  /** 每个会话只告警一次(组件挂载期);改 store 生命周期会跨服务串扰 */
  const notifiedErrorRef = useRef(false);
  /** 跟随滚动:新日志到达时滚回列表头(逆序渲染,最新在最上) */
  const [follow, setFollow] = useState(false);
  const logBoxRef = useRef<HTMLDivElement | null>(null);
  const logs = useServices((s) => s.logs);

  // 选中服务:store 里的 activeServiceId,未选时回落到第一个(卡片点击即切换,不再用下拉框)
  const activeSource: ServiceInstance | undefined = useMemo(
    () => services.find((s) => s.id === activeServiceId) ?? services[0],
    [services, activeServiceId]
  );

  // 只展示当前选中服务的日志;切换服务时清掉上一家的行选中态
  const sourceLogs = useMemo(
    () => logs.filter((l) => l.service === activeSource?.name),
    [logs, activeSource?.name]
  );
  const filtered = sourceLogs.filter((l) => filter === "all" || l.level === filter);

  // 跟随开启时,新日志到达滚回列表顶部(逆序:最新在最上,scrollTop=0 即"底")
  useEffect(() => {
    if (follow && logBoxRef.current) {
      logBoxRef.current.scrollTop = 0;
    }
  }, [filtered.length, follow]);

  // 后端模式:订阅当前选中源的真实日志 SSE(FILE/DOCKER 后端自动区分)。
  // 先清该服务旧日志再订阅,避免 tail=100 回放造成重复行;断线自动重连并在头部提示
  useEffect(() => {
    if (!USE_BACKEND || !activeSource?.sourceId) return;
    const name = activeSource.name;
    clearLogsFor(name);
    setConnState("connecting");
    const cancel = subscribeSourceLogs(
      activeSource.sourceId,
      (line) => {
        if (line.trim()) ingestDockerLog(name, line);
      },
      () => { /* 单次失败由重连机制处理 */ },
      setConnState
    );
    return cancel;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSource?.sourceId, USE_BACKEND]);

  // AI 即时分析(只读):调 env-service 分析端点,agent 走 ASSIST 档
  const [analyzing, setAnalyzing] = useState(false);
  const [analysis, setAnalysis] = useState<string | null>(null);
  const runAnalysis = async () => {
    if (!USE_BACKEND || !activeSource?.sourceId) return;
    setAnalyzing(true);
    setAnalysis(null);
    try {
      const result = await environmentApi.analyzeSource(activeSource.sourceId);
      setAnalysis(result.analysis || (result.status === "completed" ? "无异常。" : "分析失败。"));
    } catch (e) {
      setAnalysis(`分析失败:${(e as Error).message}`);
    } finally {
      setAnalyzing(false);
    }
  };

  const handleSelect = (log: LogEntry) => {
    setSelected(log);
    // 服务异常告警事件：选中 ERROR 级日志时产生，受「通知偏好 → 事件开关」过滤。
    // 挂载期内只告警一次,避免反复点击重复轰炸
    if (log.level === "error" && !notifiedErrorRef.current) {
      notifiedErrorRef.current = true;
      addNotification(
        "服务异常告警",
        `${log.service} 出现 ERROR 级日志：${log.message}`,
        "svcError"
      );
    }
  };

  const createFixTask = async () => {
    if (taskCreated || !selected) return;
    // Phase 4.4:诊断结论 → 可执行的修复任务。prompt 携带真实出错日志,
    // agent 运行时经工具循环(read_service_logs 等)真查日志给出分析,
    // 不再是硬编码假任务
    const prompt =
      `自动化修复诊断:${selected.service} 服务出现以下 ERROR 日志:\n` +
      `${selected.time} ${selected.message}\n` +
      `请读取该服务最近日志定位根因,结合知识库(项目文档)给出修复建议;只分析,不要执行任何修改操作。`;
    const saved = await addRule(`修复任务:${selected.service} 异常诊断`, "手动触发", prompt);
    if (!saved) {
      // 创建失败:store 已 toast 人话错误;不置"已创建"态,用户可重试(2026-09-19 修假成功)
      return;
    }
    setTaskCreated(true);
    toast.success("修复任务已创建,到「自动任务」页运行");
  };

  return (
    <div className="bg-card border border-border rounded-xl overflow-hidden">
      {/* 头部:当前选中服务 + 状态摘要(替代原下拉框) */}
      <div className="flex items-center justify-between px-4 py-2.5 border-b border-border bg-muted/30">
        <div className="flex items-center gap-2 min-w-0">
          <Terminal className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
          <span className="text-xs font-bold text-foreground shrink-0">日志流</span>
          {activeSource && (
            <span className="flex items-center gap-1.5 min-w-0 text-[11px] text-muted-foreground">
              <span className="font-mono font-bold text-foreground truncate">{activeSource.name}</span>
              <span className={`shrink-0 inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] font-bold border ${
                activeSource.health === "healthy"
                  ? "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-300 border-green-200 dark:border-green-800"
                  : activeSource.health === "degraded"
                    ? "bg-yellow-50 dark:bg-yellow-950/40 text-yellow-700 dark:text-yellow-300 border-yellow-200 dark:border-yellow-800"
                    : "bg-red-50 dark:bg-red-950/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800"
              }`}>
                <span className={`w-1.5 h-1.5 rounded-full ${activeSource.health === "down" ? "bg-red-500" : "bg-current animate-pulse"}`} />
                {activeSource.status === "running" ? "运行中" : activeSource.status === "stopped" ? "已停止" : "异常"}
              </span>
              {activeSource.kind === "DOCKER" && activeSource.port ? (
                <span className="font-mono tabular-nums shrink-0">:{activeSource.port}</span>
              ) : null}
              <span className="truncate hidden sm:inline">· {activeSource.uptime}</span>
            </span>
          )}
          {/* SSE 连接状态:正常不显示,异常时黄/红点提示 */}
          {USE_BACKEND && activeSource?.sourceId && connState !== "open" && (
            <span
              title={connState === "reconnecting" ? "日志连接断开,重连中…" : connState === "connecting" ? "日志连接中…" : "日志连接已断开,请刷新重试"}
              className="shrink-0 inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] font-bold border bg-yellow-50 dark:bg-yellow-950/40 text-yellow-700 dark:text-yellow-300 border-yellow-200 dark:border-yellow-800"
            >
              {connState === "closed" ? <WifiOff className="w-2.5 h-2.5" /> : <Wifi className="w-2.5 h-2.5 animate-pulse" />}
              {connState === "reconnecting" ? "重连中" : connState === "connecting" ? "连接中" : "已断开"}
            </span>
          )}
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <button
            type="button"
            title={follow ? "跟随新日志(已开启)" : "开启跟随:新日志自动滚动"}
            onClick={() => setFollow((v) => !v)}
            className={`px-1.5 py-0.5 rounded text-[10px] font-medium cursor-pointer transition-colors inline-flex items-center gap-1 ${follow ? "bg-blue-600 text-white" : "text-muted-foreground hover:bg-muted hover:text-foreground"}`}
          >
            <ArrowDownToLine className="w-2.5 h-2.5" /> 跟随
          </button>
          {(["all", "info", "warn", "error"] as const).map((lv) => (
            <button
              key={lv}
              type="button"
              onClick={() => setFilter(lv)}
              className={`px-2 py-0.5 rounded text-[10px] font-medium cursor-pointer transition-colors ${filter === lv ? "bg-foreground text-background" : "text-muted-foreground hover:bg-muted hover:text-foreground"}`}
            >
              {lv === "all" ? "全部" : lv.toUpperCase()}
            </button>
          ))}
        </div>
      </div>

      {/* 浅色日志区(逆序:最新在最上;跟随开启时新日志滚到顶部) */}
      <div ref={logBoxRef} className="bg-muted/20 font-mono text-xs leading-relaxed max-h-96 overflow-y-auto custom-scroll p-3 space-y-0.5 divide-y divide-border/40">
        {filtered.map((log, i) => (
          <div
            key={i}
            onClick={() => handleSelect(log)}
            className={`flex gap-2 px-1.5 py-1 rounded cursor-pointer transition-colors ${selected?.message === log.message ? "bg-blue-50 dark:bg-blue-950/30 ring-1 ring-blue-200 dark:ring-blue-800" : "hover:bg-muted/60"}`}
          >
            <span className="text-muted-foreground/70 shrink-0 tabular-nums">{log.time}</span>
            <span className={`shrink-0 uppercase w-10 font-bold px-1 rounded text-center h-fit ${LEVEL_BADGE_CLS[log.level]}`}>{log.level}</span>
            <span className={`shrink-0 font-bold ${LEVEL_CLS[log.level]}`}>{log.service}</span>
            <span className="text-foreground/80 break-all min-w-0">{log.message}</span>
          </div>
        ))}
        {filtered.length === 0 && (
          <div className="text-muted-foreground text-center py-6">
            {activeSource ? "当前服务暂无日志" : "先在上方添加或选中一个服务"}
          </div>
        )}
      </div>

      <div className="border-t border-border px-4 py-2 flex items-center justify-between">
        <span className="text-[10px] text-muted-foreground">点击服务卡片切换日志源;点击日志行获取诊断;AI 分析只读,不执行修改</span>
        <Button
          variant="outline"
          size="sm"
          className="h-6 text-[10px] px-2"
          disabled={!USE_BACKEND || !activeSource?.sourceId || analyzing}
          onClick={runAnalysis}
        >
          <Sparkles className="w-2.5 h-2.5 mr-0.5" />
          {analyzing ? "分析中…" : "AI 分析最近日志"}
        </Button>
      </div>

      {/* AI 即时分析结果(内联展示,只读结论) */}
      {(analyzing || analysis) && (
        <div className="border-t border-border p-3 bg-violet-50/50 dark:bg-violet-950/20 animate-in fade-in">
          <div className="flex items-start gap-2">
            <Activity className="w-3.5 h-3.5 text-violet-600 dark:text-violet-400 shrink-0 mt-0.5" />
            <div className="min-w-0">
              <div className="text-xs font-bold text-foreground mb-1">
                AI 分析 · {activeSource?.name} · 只读
              </div>
              {analyzing ? (
                <p className="text-xs text-muted-foreground animate-pulse">
                  Agent 正在读取最近日志并结合知识库分析(约 10-60 秒)…
                </p>
              ) : (
                // agent 返回的是 markdown(结论/分级/建议),按 md 渲染而非纯文本
                <Markdown className="chat-markdown text-xs text-muted-foreground leading-relaxed">
                  {analysis ?? ""}
                </Markdown>
              )}
            </div>
          </div>
        </div>
      )}

      {selected && (
        <div className="border-t border-border p-3 bg-blue-50/50 dark:bg-blue-950/20 animate-in fade-in">
          <div className="flex items-start gap-2">
            <Sparkles className="w-3.5 h-3.5 text-blue-600 dark:text-blue-400 shrink-0 mt-0.5" />
            <div className="min-w-0">
              <div className="text-xs font-bold text-foreground mb-1">AI 诊断</div>
              <p className="text-xs text-muted-foreground leading-relaxed break-all">
                已选中 <code className="font-mono text-red-600 dark:text-red-400">{selected.service}</code> 的 {selected.level.toUpperCase()} 日志:
                {selected.message.slice(0, 120)}{selected.message.length > 120 ? "…" : ""}
                。创建修复任务后,Agent 将真实读取该服务最近日志定位根因并给出修复建议。
              </p>
              <button
                type="button"
                onClick={createFixTask}
                className={`mt-2 inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-[11px] font-medium border transition-colors cursor-pointer ${taskCreated ? "bg-green-50 dark:bg-green-950/40 text-green-600 dark:text-green-400 border-green-200 dark:border-green-800" : "bg-card text-muted-foreground border-border hover:text-blue-600 dark:hover:text-blue-400 hover:border-blue-300 dark:hover:border-blue-700"}`}
              >
                {taskCreated ? <Check className="w-3 h-3" /> : <Zap className="w-3 h-3" />}
                {taskCreated ? "修复任务已创建" : "创建修复任务"}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
