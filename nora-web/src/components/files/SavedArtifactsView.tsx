'use client';

import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { FileText, ChevronRight, Loader2, MessageSquare, FileDown, BookOpen, FolderOpen } from "lucide-react";
import { Button } from "@/components/ui/button";
import { savedArtifactsApi, type SavedArtifact } from "@/lib/services/savedArtifactsApi";
import { USE_BACKEND } from "@/lib/api/client";

/**
 * 「已保存成果」视图(B1,2026-09-27,评审报告:成果入口和实际保存行为没有对齐)。
 *
 * 此前这里读的是 automation execution_record(最近 50 条,含失败/取消/错过
 * 计划)——用户保存的报告(工作区 Markdown / 知识库文档)反而找不到;失败
 * 运行也会混进来。现在:
 *
 * - 数据源改为 saved_artifact(对话保存时服务端登记,权威、跨浏览器);
 * - 每行显示类型徽章 + 名称 + 来源会话,可**打开**(工作区文件 / 知识文档)
 *   并**回到来源会话**;
 * - 执行记录留在「任务 → 执行历史」,不在成果列表出现(职责分离)。
 */
export function SavedArtifactsView() {
  const navigate = useNavigate();
  const [items, setItems] = useState<SavedArtifact[]>([]);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      if (!USE_BACKEND) {
        setLoading(false);
        return;
      }
      try {
        const list = await savedArtifactsApi.list(50);
        if (!cancelled) setItems(list);
      } catch {
        if (!cancelled) setFailed(true);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => { cancelled = true; };
  }, []);

  if (loading) {
    return (
      <div className="bg-card border border-border rounded-xl py-16 text-center text-xs text-muted-foreground flex items-center justify-center gap-2">
        <Loader2 className="w-3.5 h-3.5 animate-spin" /> 加载已保存成果…
      </div>
    );
  }

  if (failed) {
    return (
      <div className="bg-card border border-border rounded-xl py-16 text-center space-y-1">
        <div className="text-xs text-muted-foreground">成果列表加载失败</div>
        <div className="text-[11px] text-muted-foreground/70">请检查后端服务后刷新重试</div>
      </div>
    );
  }

  if (items.length === 0) {
    return (
      <div className="bg-card border border-dashed border-border rounded-xl py-16 text-center space-y-1">
        <FileText className="w-6 h-6 mx-auto text-muted-foreground/40" />
        <div className="text-xs text-muted-foreground">还没有已保存的成果</div>
        <div className="text-[11px] text-muted-foreground/70">
          在对话中把回答「保存为文件」或「保存到知识库」后,会出现在这里,并可回到来源会话。
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-2">
      {items.map((a) => {
        const isFile = a.kind === "workspace_file";
        const Icon = isFile ? FileDown : BookOpen;
        const kindLabel = isFile ? "工作区文件" : "知识库文档";
        const kindCls = isFile
          ? "bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-300 border-blue-200 dark:border-blue-800"
          : "bg-purple-50 dark:bg-purple-950/40 text-purple-700 dark:text-purple-300 border-purple-200 dark:border-purple-800";
        return (
          <div key={a.id} className="bg-card border border-border rounded-xl px-4 py-3 flex items-center gap-3">
            <Icon className="w-4 h-4 shrink-0 text-muted-foreground" />
            <div className="min-w-0 flex-1">
              <div className="flex items-center gap-2">
                <span className="text-xs font-bold text-foreground truncate">{a.name}</span>
                <span className={`text-[9px] font-medium px-1.5 py-0.5 rounded border shrink-0 ${kindCls}`}>{kindLabel}</span>
              </div>
              <div className="text-[11px] text-muted-foreground truncate mt-0.5 font-mono">
                {isFile ? a.path : `文档 #${a.path}`}
              </div>
            </div>
            <div className="text-right shrink-0 text-[10px] text-muted-foreground tabular-nums">
              {a.createdAt ?? "—"}
            </div>
            <div className="flex items-center gap-1.5 shrink-0">
              {/* 打开:工作区文件 → 文件页工作区深链;知识文档 → 长期知识视图 */}
              <Button
                variant="outline" size="sm" className="h-6 text-[10px] px-2"
                onClick={() => {
                  if (isFile) {
                    navigate(`/files?workspace=${encodeURIComponent(a.path)}`);
                  } else {
                    navigate("/files?view=knowledge");
                  }
                }}
                title={isFile ? `打开工作区文件:${a.path}` : `打开知识库文档 #${a.path}`}
              >
                <FolderOpen className="w-2.5 h-2.5 mr-1" /> 打开
              </Button>
              {/* 回到来源会话(B1 验收:保存后可回到来源会话) */}
              {a.sessionId && (
                <Button
                  variant="outline" size="sm" className="h-6 text-[10px] px-2"
                  onClick={() => navigate(`/chat?session=${encodeURIComponent(a.sessionId!)}`)}
                  title={`回到来源会话:${a.sessionId}`}
                >
                  <MessageSquare className="w-2.5 h-2.5 mr-1" /> 来源会话
                </Button>
              )}
            </div>
          </div>
        );
      })}
    </div>
  );
}
