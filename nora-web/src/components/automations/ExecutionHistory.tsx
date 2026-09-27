'use client';

import { useState } from "react";
import { RotateCw, History, ChevronRight, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Modal } from "@/components/ui/custom/Modal";
import MarkdownContent from "@/components/shared/MarkdownContent";
import type { ExecutionRecord } from "@/types";
import { useAutomations } from "@/hooks/useAutomations";
import { EXECUTION_STATUS_META as STATUS_META } from "@/lib/executionStatus";

// 终态展示(B5,2026-09-27):改用共享映射(lib/executionStatus.ts)——
// 与首页「最近成果」、资料页「已保存任务结果」同一份,同一条记录在各入口
// 状态一致(partial/cancelled/unknown/missed_schedule 各有独立视觉)。

/** 解析规则原始动作 JSON(B2;失败返回 null,调用方退回结果文本兜底)。 */
function parseActionJson(actionJson: string | null | undefined): { prompt?: string; sql?: string } | null {
  if (!actionJson) return null;
  try {
    const parsed = JSON.parse(actionJson) as { prompt?: string; sql?: string };
    return parsed && typeof parsed === "object" ? parsed : null;
  } catch {
    return null;
  }
}

/**
 * 执行历史(M0-04,2026-09-20):
 * 列表只展示摘要(首行,≤120 字),点击行打开**全文详情**——此前详情被
 * 截为 120 字且没有查看入口,「成功」之后拿不到报告(审查报告 B04)。
 * detail 字段本身已是全文(automationsApi.toExecution),详情面板直接渲染。
 */
export function ExecutionHistory({ onCreateSchedule }: { onCreateSchedule?: (prefill: { name: string; action: string }) => void } = {}) {
  const [retrying, setRetrying] = useState<number | null>(null);
  const [viewing, setViewing] = useState<ExecutionRecord | null>(null);
  const records = useAutomations((s) => s.executions);
  const retryExecution = useAutomations((s) => s.retryExecution);

  const retry = (record: ExecutionRecord) => {
    // 真实重试(2026-09-19 去假功能):等待态只覆盖真实请求时长,不再用
    // 800ms 假动画 + 硬编码"重试成功 1.4s"文案;结果由 store 的真实回调
    // 决定(成功/失败各有通知)。
    setRetrying(record.id);
    retryExecution(record.id);
    // 重试是异步的:短时间内清等待态(真实结果通过执行历史刷新呈现)
    setTimeout(() => setRetrying(null), 3000);
  };

  return (
    <>
      <div className="bg-card border border-border rounded-xl overflow-hidden">
        <div className="px-4 py-2.5 border-b border-border bg-gray-50/50 dark:bg-gray-950/50 flex items-center gap-1.5">
          <History className="w-3.5 h-3.5 text-muted-foreground" />
          <span className="text-xs font-bold text-foreground">执行历史</span>
          <span className="text-[10px] text-muted-foreground ml-auto">{records.length} 条 · 点击查看完整结果</span>
        </div>
        <div className="divide-y divide-gray-100 dark:divide-gray-800">
          {records.map((rec) => {
            const meta = STATUS_META[rec.status];
            const Icon = meta.icon;
            return (
              <div
                key={rec.id}
                className="flex items-center gap-3 px-4 py-3 cursor-pointer hover:bg-muted/40 transition-colors"
                onClick={() => setViewing(rec)}
                title="查看完整结果"
              >
                <Icon className={`w-4 h-4 shrink-0 ${meta.cls} ${rec.status === "running" ? "animate-spin" : ""}`} />
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-bold text-foreground">{rec.ruleName}</span>
                    <span className={`text-[9px] font-bold ${meta.cls}`}>{meta.label}</span>
                  </div>
                  {/* 列表摘要:首行截断;全文在详情面板 */}
                  <div className="text-[11px] text-muted-foreground truncate mt-0.5">
                    {rec.detailSummary ?? rec.detail}
                  </div>
                </div>
                <div className="text-right shrink-0">
                  <div className="text-[10px] text-muted-foreground tabular-nums">{rec.time}</div>
                  <div className="text-[10px] text-muted-foreground tabular-nums">{rec.duration}</div>
                </div>
                {rec.status === "failed" && (
                  <Button
                    variant="outline" size="sm" className="h-6 text-[10px] px-2 shrink-0"
                    onClick={(e) => { e.stopPropagation(); retry(rec); }}
                    disabled={retrying === rec.id}
                  >
                    {retrying === rec.id ? <Loader2 className="w-2.5 h-2.5 animate-spin" /> : <RotateCw className="w-2.5 h-2.5" />}
                    重试
                  </Button>
                )}
                <ChevronRight className="w-3.5 h-3.5 text-muted-foreground/60 shrink-0" />
              </div>
            );
          })}
        </div>
      </div>

      {/* 完整结果详情:全文渲染(不截断),可复制 */}
      <Modal
        isOpen={viewing != null}
        onClose={() => setViewing(null)}
        title={viewing ? `${viewing.ruleName} · ${STATUS_META[viewing.status].label}` : ""}
        width="w-[94%] sm:w-[720px]"
        footer={viewing && (
          <>
            <Button
              variant="outline" size="sm"
              onClick={() => {
                // B2(2026-09-27,评审报告):复用**原始指令**创建定期任务——
                // 此前把结果文本(viewing.detail)当作下一次指令,结果陈述
                // (「已完成整理,文件在…」)不能表达「下周重新查找并整理」的
                // 操作要求。现在:action 取规则原始动作(agent 取 prompt /
                // SQL 取语句),上次结果作为**参考**附在指令后。
                const parsed = parseActionJson(viewing.actionJson);
                const original = parsed?.prompt ?? parsed?.sql;
                const action = original
                  ? `【原始指令】\n${original}\n\n【上次结果参考】\n${viewing.detail.slice(0, 1500)}`
                  : viewing.detail.slice(0, 2000);
                onCreateSchedule?.({
                  name: `${viewing.ruleName}（定期）`,
                  action,
                });
                setViewing(null);
              }}
              title="复用原始指令创建定期任务(上次结果作为参考附后)"
            >
              设为定期任务
            </Button>
            <Button
              variant="outline" size="sm"
              onClick={() => { void navigator.clipboard.writeText(viewing.detail); }}
            >
              复制全文
            </Button>
            <Button size="sm" onClick={() => setViewing(null)}>关闭</Button>
          </>
        )}
      >
        {viewing && (
          <div className="space-y-3">
            <div className="flex items-center gap-3 text-[11px] text-muted-foreground">
              <span>时间:{viewing.time}</span>
              <span>耗时:{viewing.duration}</span>
              <span>状态:{STATUS_META[viewing.status].label}</span>
            </div>
            <div className="max-h-[420px] overflow-auto rounded-lg border border-border bg-muted/40 p-3 text-xs leading-relaxed">
              <MarkdownContent className="prose-sm [&_pre]:whitespace-pre-wrap [&_pre]:break-words">{viewing.detail || "（本次执行没有输出内容）"}</MarkdownContent>
            </div>
          </div>
        )}
      </Modal>
    </>
  );
}
