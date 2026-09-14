'use client';

import { useEffect, useState } from "react";
import { Database, Layers, Boxes, Clock, CheckCircle2, AlertCircle } from "lucide-react";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { computeIndexStats, fetchIndexStats } from "@/lib/services/ragService";
import { USE_BACKEND } from "@/lib/api/client";
import type { IndexStats } from "@/types";

function StatCard({ icon: Icon, label, value, sub, color }: {
  icon: React.ElementType; label: string; value: string | number; sub?: string; color: string;
}) {
  return (
    <div className="bg-card border border-border rounded-xl p-4">
      <div className="flex items-center gap-2 mb-2">
        <Icon className={`w-4 h-4 ${color}`} />
        <span className="text-xs text-muted-foreground">{label}</span>
      </div>
      <div className="text-2xl font-bold text-foreground tabular-nums">{value}</div>
      {sub && <div className="text-[10px] text-muted-foreground mt-1">{sub}</div>}
    </div>
  );
}

export function IndexStatus() {
  const docs = useKnowledgeDocs((state) => state.docs);
  const localStats = computeIndexStats(docs);
  const [stats, setStats] = useState<IndexStats>(localStats);

  useEffect(() => {
    if (!USE_BACKEND) return;
    let mounted = true;
    fetchIndexStats()
      .then((s) => { if (mounted) setStats(s); })
      .catch(() => { /* 后端不可用时保留本地推导值 */ });
    return () => { mounted = false; };
  }, []);

  const s = stats;
  return (
    <div className="space-y-6">
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <StatCard icon={Database} label="已索引文档" value={s.totalDocs} sub={`待处理 ${s.pendingDocs} 个`} color="text-blue-600 dark:text-blue-400" />
        <StatCard icon={Layers} label="向量 chunks" value={s.totalChunks.toLocaleString()} sub={`维度 ${s.vectorDim}`} color="text-purple-600 dark:text-purple-400" />
        <StatCard icon={Boxes} label="嵌入模型" value={s.model.split("-").slice(0, 3).join("-")} sub={s.model} color="text-green-600 dark:text-green-400" />
        <StatCard icon={Clock} label="上次更新" value={s.lastUpdate} sub="增量索引" color="text-orange-600 dark:text-orange-400" />
      </div>

      <div className="bg-card border border-border rounded-xl p-5 space-y-4">
        <h3 className="text-sm font-bold text-foreground">索引健康检查</h3>

        <div className="flex items-center justify-between py-2 border-b border-border">
          <div className="flex items-center gap-2.5">
            {s.vectorReady ? <CheckCircle2 className="w-4 h-4 text-green-500" /> : <AlertCircle className="w-4 h-4 text-red-500" />}
            <div>
              <div className="text-sm font-medium text-foreground">向量索引</div>
              <div className="text-xs text-muted-foreground">支持语义检索，{s.vectorDim} 维</div>
            </div>
          </div>
          <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${s.vectorReady ? "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-300" : "bg-red-50 dark:bg-red-950/40 text-red-700 dark:text-red-300"}`}>
            {s.vectorReady ? "正常" : "异常"}
          </span>
        </div>

        <div className="flex items-center justify-between py-2">
          <div className="flex items-center gap-2.5">
            {s.pendingDocs === 0 ? <CheckCircle2 className="w-4 h-4 text-green-500" /> : <AlertCircle className="w-4 h-4 text-yellow-500" />}
            <div>
              <div className="text-sm font-medium text-foreground">清洗队列</div>
              <div className="text-xs text-muted-foreground">
                {s.pendingDocs === 0 ? "队列为空，全部已处理" : `${s.pendingDocs} 个文档等待清洗和索引`}
              </div>
            </div>
          </div>
          <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${s.pendingDocs === 0 ? "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-300" : "bg-yellow-50 dark:bg-yellow-950/40 text-yellow-700 dark:text-yellow-300"}`}>
            {s.pendingDocs === 0 ? "空闲" : "处理中"}
          </span>
        </div>
      </div>
    </div>
  );
}
