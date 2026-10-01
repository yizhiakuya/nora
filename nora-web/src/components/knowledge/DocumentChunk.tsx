'use client';

import { Trash2, Pencil, Eye, EyeOff } from "lucide-react";
import { DocDetail } from "@/types";
import { Button } from "@/components/ui/button";

import type { DocumentLibraryState } from "@/hooks/useDocumentLibrary";

export function DocumentChunk({ chunk, library }: { chunk: DocDetail["chunks"][number]; library: DocumentLibraryState }) {
  const { editingChunkId, setEditingChunkId, editingChunkText, setEditingChunkText, chunkBusy, handleChunkSave, handleChunkToggle, handleChunkDelete } = library;
  const c = chunk;
  const isEditing = editingChunkId === c.id;
  return (<>
            <div key={c.id ?? c.chunkIndex} className={`border rounded-lg p-3 ${c.enabled === false ? "border-border/50 bg-muted/40" : "border-border"}`}>
              <div className="flex items-center gap-2 mb-1.5 text-[10px] text-muted-foreground flex-wrap">
                <span className="font-medium text-foreground">chunk #{c.chunkIndex}</span>
                <span>· {c.length} 字</span>
                <span>· ~{c.tokenCount} tokens</span>
                {c.origin === "manual" && (
                  <span className="px-1.5 py-0.5 rounded border border-purple-200 dark:border-purple-800 bg-purple-50 dark:bg-purple-950/40 text-purple-700 dark:text-purple-300">手动</span>
                )}
                {c.edited && c.origin !== "manual" && (
                  <span className="px-1.5 py-0.5 rounded border border-amber-200 dark:border-amber-800 bg-amber-50 dark:bg-amber-950/40 text-amber-700 dark:text-amber-300">已编辑</span>
                )}
                {c.enabled === false && (
                  <span className="px-1.5 py-0.5 rounded border border-border bg-muted text-muted-foreground">已停用</span>
                )}
                {/* 父子模式标注(阶段 B):命中子块、返回父块——让用户理解检索行为 */}
                {c.parentIndex != null && (
                  <span className="px-1.5 py-0.5 rounded border border-blue-200 dark:border-blue-800 bg-blue-50 dark:bg-blue-950/40 text-blue-700 dark:text-blue-300">
                    章节 #{c.parentIndex}(命中后返回整章)
                  </span>
                )}
                {/* 分段级操作(阶段 D,Dify 同款) */}
                {c.id != null && (
                  <span className="ml-auto flex items-center gap-1">
                    <button
                      type="button"
                      onClick={() => { setEditingChunkId(c.id!); setEditingChunkText(c.content); }}
                      className="p-1 rounded hover:bg-muted text-muted-foreground hover:text-foreground cursor-pointer"
                      title="编辑此分段（保存后自动重算向量）"
                    >
                      <Pencil className="w-3 h-3" />
                    </button>
                    <button
                      type="button"
                      onClick={() => void handleChunkToggle(c.id!, c.enabled === false)}
                      disabled={chunkBusy}
                      className="p-1 rounded hover:bg-muted text-muted-foreground hover:text-foreground cursor-pointer disabled:opacity-50"
                      title={c.enabled === false ? "启用此分段" : "停用此分段（退出检索）"}
                    >
                      {c.enabled === false ? <Eye className="w-3 h-3" /> : <EyeOff className="w-3 h-3" />}
                    </button>
                    <button
                      type="button"
                      onClick={() => void handleChunkDelete(c.id!)}
                      disabled={chunkBusy}
                      className="p-1 rounded hover:bg-muted text-muted-foreground hover:text-red-600 dark:hover:text-red-400 cursor-pointer disabled:opacity-50"
                      title="删除此分段（剩余分段重新编号）"
                    >
                      <Trash2 className="w-3 h-3" />
                    </button>
                  </span>
                )}
              </div>
              {isEditing ? (
                <div className="space-y-2">
                  <textarea
                    value={editingChunkText}
                    onChange={(e) => setEditingChunkText(e.target.value)}
                    rows={6}
                    className="w-full px-2 py-1.5 text-xs bg-background border border-border rounded-lg focus:outline-none focus:ring-1 focus:ring-primary font-mono"
                  />
                  <div className="flex justify-end gap-2">
                    <Button variant="outline" size="sm" className="h-6 text-[11px]" onClick={() => setEditingChunkId(null)}>取消</Button>
                    <Button size="sm" className="h-6 text-[11px]" disabled={chunkBusy || !editingChunkText.trim()} onClick={() => void handleChunkSave(c.id!)}>
                      {chunkBusy ? "保存中…" : "保存并重算向量"}
                    </Button>
                  </div>
                </div>
              ) : (
                <pre className="text-xs text-muted-foreground whitespace-pre-wrap break-words leading-relaxed">
                  {c.content}
                </pre>
              )}
              {/* 父块正文与子块不同时,折叠展示(默认收起,避免抽屉过长) */}
              {c.parentContent && !isEditing && (
                <details className="mt-2">
                  <summary className="text-[10px] text-blue-500 dark:text-blue-400 cursor-pointer hover:underline">
                    展开所属章节完整正文({c.parentContent.length} 字符)
                  </summary>
                  <pre className="mt-1.5 text-[11px] text-muted-foreground/80 whitespace-pre-wrap break-words leading-relaxed border-l-2 border-blue-200 dark:border-blue-800 pl-2">
                    {c.parentContent}
                  </pre>
                </details>
              )}
            </div>
  </>);
}
