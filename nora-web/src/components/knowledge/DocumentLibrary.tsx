'use client';

import { useMemo, useState } from "react";
import { Search, Trash2, Pencil, RefreshCw, Loader2 } from "lucide-react";
import { SOURCE_META } from "@/lib/knowledgeSourceMeta";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { KnowledgeDoc, KnowledgeSource, DocDetail } from "@/types";
import { Checkbox } from "@/components/ui/checkbox";
import { Button } from "@/components/ui/button";
import { Modal } from "@/components/ui/custom/Modal";
import { fetchDocDetail, reindexDoc } from "@/lib/services/ragService";
import { toast } from "sonner";

const SOURCE_ORDER: KnowledgeSource[] = ["file", "database", "repo", "environment", "chat", "text"];

function DocIcon({ source }: { source: KnowledgeSource }) {
  const meta = SOURCE_META[source];
  const Icon = meta.icon;
  return <Icon className={`w-4 h-4 shrink-0 ${meta.color}`} />;
}

function StatusBadge({ status }: { status: KnowledgeDoc["status"] }) {
  const map = {
    indexed:    { label: "已索引", cls: "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-300 border-green-200 dark:border-green-800" },
    processing: { label: "处理中", cls: "bg-yellow-50 dark:bg-yellow-950/40 text-yellow-700 dark:text-yellow-300 border-yellow-200 dark:border-yellow-800" },
    failed:     { label: "失败",   cls: "bg-red-50 dark:bg-red-950/40 text-red-700 dark:text-red-300 border-red-200 dark:border-red-800" },
  } as const;
  const s = map[status];
  return <span className={`inline-flex items-center px-1.5 py-0.5 rounded text-[9px] font-medium border whitespace-nowrap ${s.cls}`}>{s.label}</span>;
}

function QualityBar({ score }: { score: number }) {
  if (score === 0) return <span className="text-[10px] text-muted-foreground">—</span>;
  const color = score >= 90 ? "bg-green-500" : score >= 80 ? "bg-yellow-500" : "bg-red-500";
  return (
    <div className="flex items-center gap-1.5">
      <div className="w-12 h-1.5 bg-gray-200 dark:bg-gray-700 rounded-full overflow-hidden">
        <div className={`h-full ${color}`} style={{ width: `${score}%` }} />
      </div>
      <span className="text-[10px] text-muted-foreground tabular-nums">{score}</span>
    </div>
  );
}

export function DocumentLibrary() {
  const allDocs = useKnowledgeDocs((s) => s.docs);
  const removeDoc = useKnowledgeDocs((s) => s.removeDoc);
  const removeDocs = useKnowledgeDocs((s) => s.removeDocs);
  const renameDoc = useKnowledgeDocs((s) => s.renameDoc);

  const [selected, setSelected] = useState<Set<number>>(new Set());
  const [busyId, setBusyId] = useState<number | null>(null);
  const [renaming, setRenaming] = useState<KnowledgeDoc | null>(null);
  const [renameValue, setRenameValue] = useState("");
  const [detail, setDetail] = useState<DocDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState<number | null>(null);
  const [confirmDelete, setConfirmDelete] = useState<number[] | null>(null);

  const grouped = useMemo(() => {
    const map = new Map<KnowledgeSource, KnowledgeDoc[]>();
    for (const src of SOURCE_ORDER) {
      const docs = allDocs.filter((d) => d.source === src);
      if (docs.length) map.set(src, docs);
    }
    return map;
  }, [allDocs]);

  const allIds = useMemo(() => allDocs.map((d) => d.id), [allDocs]);
  // 文档列表被外部刷新(syncFromBackend/轮次结束)后,selected 里可能残留已删 id:
  // 派生一个只含现存文档的选择集,全选判定与批量删除都以它为准
  const selectedLive = useMemo(
    () => new Set(allIds.filter((id) => selected.has(id))),
    [allIds, selected]
  );
  const allSelected = allIds.length > 0 && selectedLive.size === allIds.length;

  const toggleOne = (id: number) => {
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id); else next.add(id);
      return next;
    });
  };

  const toggleAll = () => {
    // 用 selectedLive(排除已不存在的 id)判定,残留旧 id 时也能正确切换
    setSelected((prev) => {
      const live = new Set(allIds.filter((id) => prev.has(id)));
      return live.size === allIds.length ? new Set<number>() : new Set(allIds);
    });
  };

  const openDetail = async (doc: KnowledgeDoc) => {
    setDetailLoading(doc.id);
    try {
      const d = await fetchDocDetail(doc.id);
      setDetail(d);
    } catch (e) {
      toast.error(`加载详情失败：${(e as Error).message}`);
    } finally {
      setDetailLoading(null);
    }
  };

  const handleReindex = async (doc: KnowledgeDoc) => {
    setBusyId(doc.id);
    try {
      await reindexDoc(doc.id);
      toast.success(`「${doc.name}」已重建索引`);
    } catch (e) {
      toast.error(`重建索引失败：${(e as Error).message}`);
    } finally {
      setBusyId(null);
    }
  };

  const runDelete = async (ids: number[]) => {
    setConfirmDelete(null);
    try {
      if (ids.length === 1) {
        await removeDoc(ids[0]);
      } else {
        await removeDocs(ids);
      }
      setSelected((prev) => {
        const next = new Set(prev);
        ids.forEach((id) => next.delete(id));
        return next;
      });
      toast.success(ids.length === 1 ? "已删除该文档" : `已删除 ${ids.length} 个文档`);
    } catch (e) {
      toast.error(`删除失败：${(e as Error).message}`);
    }
  };

  const submitRename = async () => {
    if (!renaming) return;
    const doc = renaming;
    setBusyId(doc.id);
    try {
      await renameDoc(doc.id, renameValue);
      setRenaming(null);
      toast.success("已重命名");
    } catch (e) {
      toast.error(`重命名失败：${(e as Error).message}`);
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="space-y-6">
      {allDocs.length > 0 && (
        <div className="flex items-center gap-3 min-h-[28px]">
          <label className="flex items-center gap-2 text-xs text-muted-foreground cursor-pointer">
            <Checkbox checked={allSelected} onCheckedChange={toggleAll} />
            全选
          </label>
          {selectedLive.size > 0 && (
            <>
              <span className="text-xs text-muted-foreground">已选 {selectedLive.size} 个</span>
              <Button
                size="sm"
                variant="outline"
                className="h-7 text-xs text-red-600 dark:text-red-400"
                onClick={() => setConfirmDelete(Array.from(selectedLive))}
              >
                <Trash2 className="w-3 h-3 mr-1" />
                批量删除
              </Button>
            </>
          )}
        </div>
      )}

      {SOURCE_ORDER.map((src) => {
        const docs = grouped.get(src);
        if (!docs) return null;
        const meta = SOURCE_META[src];
        const Icon = meta.icon;
        return (
          <div key={src}>
            <div className="flex items-center gap-2 mb-3">
              <Icon className={`w-4 h-4 ${meta.color}`} />
              <span className="text-sm font-bold text-foreground">{meta.label}</span>
              <span className="text-xs text-muted-foreground">({docs.length})</span>
            </div>
            <div className="bg-card border border-border rounded-xl overflow-hidden overflow-x-auto">
              <table className="w-full text-left table-fixed md:table-auto md:min-w-[640px]">
                <thead>
                  <tr className="bg-muted border-b border-border text-xs text-muted-foreground">
                    <th className="p-3 pl-4 w-10" />
                    <th className="p-3 font-medium">文档名</th>
                    <th className="p-3 font-medium hidden md:table-cell">Chunks</th>
                    <th className="p-3 font-medium hidden lg:table-cell">大小</th>
                    <th className="p-3 font-medium hidden lg:table-cell">质量</th>
                    <th className="p-3 font-medium w-[76px] md:w-auto">状态</th>
                    <th className="p-3 font-medium text-right pr-4 hidden md:table-cell">更新时间</th>
                    <th className="p-3 font-medium text-right pr-4 w-[104px] md:w-auto">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {docs.map((doc) => (
                    <tr key={doc.id} className="border-b border-border last:border-0 hover:bg-muted/50 transition-colors text-sm">
                      <td className="p-3 pl-4">
                        <Checkbox
                          checked={selectedLive.has(doc.id)}
                          onCheckedChange={() => toggleOne(doc.id)}
                          aria-label={`选择 ${doc.name}`}
                        />
                      </td>
                      <td className="p-3">
                        <div className="flex items-center gap-2.5 min-w-0">
                          <DocIcon source={doc.source} />
                          <div className="min-w-0">
                            <div className="flex items-center gap-2">
                              <button
                                type="button"
                                onClick={() => openDetail(doc)}
                                className="font-medium text-foreground hover:underline cursor-pointer text-left truncate"
                                title="查看分块详情"
                              >
                                {doc.name}
                              </button>
                              {detailLoading === doc.id && (
                                <Loader2 className="w-3 h-3 animate-spin text-muted-foreground shrink-0" />
                              )}
                            </div>
                            {/* 来源文件:跳转文件中心(预览原文件)——知识库与文件系统一体。
                                名称下方小字(窄屏不挤状态列) */}
                            {doc.source === "file" && doc.sourceId != null && (
                              <a
                                href={`/files?open=${doc.sourceId}`}
                                className="text-[10px] text-blue-500 dark:text-blue-400 hover:underline"
                                title="在文件中心查看原文件"
                                onClick={(e) => e.stopPropagation()}
                              >
                                原文件
                              </a>
                            )}
                            {/* 构建失败原因(阶段 A:失败可见可重试)——此前只有「失败」标签,原因不可见 */}
                            {doc.status === "failed" && doc.error && (
                              <div className="text-[10px] text-red-600 dark:text-red-400 mt-0.5 max-w-[360px] truncate" title={doc.error}>
                                {doc.error}
                              </div>
                            )}
                            {/* 解析告警(如超限截断;文档仍可检索) */}
                            {doc.status !== "failed" && doc.warning && (
                              <div className="text-[10px] text-amber-600 dark:text-amber-400 mt-0.5 max-w-[360px] truncate" title={doc.warning}>
                                ⚠ {doc.warning}
                              </div>
                            )}
                          </div>
                        </div>
                      </td>
                      <td className="p-3 text-muted-foreground text-xs tabular-nums hidden md:table-cell">{doc.chunks}</td>
                      <td className="p-3 text-muted-foreground text-xs hidden lg:table-cell">{doc.size}</td>
                      <td className="p-3 hidden lg:table-cell"><QualityBar score={doc.quality} /></td>
                      <td className="p-3"><StatusBadge status={doc.status} /></td>
                      <td className="p-3 text-right text-muted-foreground text-xs whitespace-nowrap hidden md:table-cell">{doc.updatedAt}</td>
                      <td className="p-3 pr-4">
                        <div className="flex items-center justify-end gap-1">
                          <button
                            type="button"
                            onClick={() => handleReindex(doc)}
                            disabled={busyId === doc.id}
                            className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground cursor-pointer disabled:opacity-50"
                            title="用已存分块重建向量（换模型或失败恢复）"
                          >
                            <RefreshCw className={`w-3.5 h-3.5 ${busyId === doc.id ? "animate-spin" : ""}`} />
                          </button>
                          <button
                            type="button"
                            onClick={() => { setRenaming(doc); setRenameValue(doc.name); }}
                            className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground cursor-pointer"
                            title="重命名"
                          >
                            <Pencil className="w-3.5 h-3.5" />
                          </button>
                          <button
                            type="button"
                            onClick={() => setConfirmDelete([doc.id])}
                            className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-red-600 dark:hover:text-red-400 cursor-pointer"
                            title="删除"
                          >
                            <Trash2 className="w-3.5 h-3.5" />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        );
      })}

      {allDocs.length === 0 && (
        <div className="py-16 flex flex-col items-center text-muted-foreground">
          <Search className="w-10 h-10 mb-3 opacity-20" />
          <div className="text-sm">没有已摄入的文档</div>
        </div>
      )}

      {/* 重命名 */}
      <Modal
        isOpen={renaming !== null}
        onClose={() => setRenaming(null)}
        title="重命名文档"
        width="w-[420px]"
        footer={
          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={() => setRenaming(null)}>取消</Button>
            <Button size="sm" onClick={submitRename} disabled={!renameValue.trim() || busyId !== null}>保存</Button>
          </div>
        }
      >
        <div className="p-4 space-y-2">
          <input
            value={renameValue}
            onChange={(e) => setRenameValue(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") void submitRename(); }}
            className="w-full px-3 py-2 text-sm bg-background border border-border rounded-lg focus:outline-none focus:ring-1 focus:ring-primary"
            placeholder="文档名"
            autoFocus
          />
          <p className="text-[11px] text-muted-foreground">
            仅改显示名，不影响已生成的分块与向量，也不会重新调用 embedding。
          </p>
        </div>
      </Modal>

      {/* 删除确认（删除会级联删掉全部 chunk，不可恢复） */}
      <Modal
        isOpen={confirmDelete !== null}
        onClose={() => setConfirmDelete(null)}
        title="删除文档"
        width="w-[420px]"
        footer={
          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={() => setConfirmDelete(null)}>取消</Button>
            <Button
              size="sm"
              className="bg-red-600 hover:bg-red-700 text-white"
              onClick={() => confirmDelete && void runDelete(confirmDelete)}
            >
              确认删除
            </Button>
          </div>
        }
      >
        <div className="p-4 text-sm text-foreground">
          确认删除 <span className="font-medium">{confirmDelete?.length ?? 0}</span> 个文档？
          其全部分块与向量会一并删除，且<strong>不可恢复</strong>。
        </div>
      </Modal>

      {/* 文档详情：分块正文 */}
      <Modal
        isOpen={detail !== null}
        onClose={() => setDetail(null)}
        title={detail ? `分块详情 · ${detail.doc.name}` : ""}
        width="w-[720px]"
        footer={
          <div className="flex justify-end">
            <Button variant="outline" size="sm" onClick={() => setDetail(null)}>关闭</Button>
          </div>
        }
      >
        <div className="p-4 space-y-3 max-h-[60vh] overflow-auto">
          {detail && detail.chunks.length === 0 && (
            <div className="text-sm text-muted-foreground">该文档暂无分块。</div>
          )}
          {detail?.chunks.map((c) => (
            <div key={c.chunkIndex} className="border border-border rounded-lg p-3">
              <div className="flex items-center gap-2 mb-1.5 text-[10px] text-muted-foreground">
                <span className="font-medium text-foreground">chunk #{c.chunkIndex}</span>
                <span>· {c.length} 字</span>
                <span>· ~{c.tokenCount} tokens</span>
              </div>
              <pre className="text-xs text-muted-foreground whitespace-pre-wrap break-words leading-relaxed">
                {c.content}
              </pre>
            </div>
          ))}
        </div>
      </Modal>
    </div>
  );
}
