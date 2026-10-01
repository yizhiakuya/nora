'use client';

import { Plus, MessageSquare } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Modal } from "@/components/ui/custom/Modal";

import type { DocumentLibraryState } from "@/hooks/useDocumentLibrary";
import { DocumentChunk } from "./DocumentChunk";

export function DocumentDetailModal({ library }: { library: DocumentLibraryState }) {
  const { detail, setDetail, addingChunk, setAddingChunk, newChunkText, setNewChunkText, chunkBusy, handleChunkAdd, detailSourceSession } = library;
  return (<>
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
          {/* 来源会话(A2):对话保存的文档可回到那次对话 */}
          {detail && detailSourceSession && (
            <div className="flex items-center gap-2 text-[11px] text-muted-foreground">
              <MessageSquare className="w-3 h-3 text-teal-500 shrink-0" />
              <span>对话产出,来源会话:</span>
              <a
                href={`/chat?session=${encodeURIComponent(detailSourceSession)}`}
                className="font-mono text-blue-500 dark:text-blue-400 hover:underline truncate max-w-[320px]"
                title={`回到来源会话 ${detailSourceSession}`}
              >
                {detailSourceSession}
              </a>
            </div>
          )}
          {detail && detail.chunks.length === 0 && (
            <div className="text-sm text-muted-foreground">该文档暂无分块。</div>
          )}
          {detail?.chunks.map((chunk) => <DocumentChunk key={chunk.id ?? chunk.chunkIndex} chunk={chunk} library={library} />)}
          {/* 手动新增分段(阶段 D) */}
          {detail && (
            <div className="pt-1">
              {addingChunk ? (
                <div className="space-y-2 border border-dashed border-border rounded-lg p-3">
                  <textarea
                    value={newChunkText}
                    onChange={(e) => setNewChunkText(e.target.value)}
                    rows={4}
                    placeholder="新分段的正文…（保存后自动重算向量并参与检索）"
                    className="w-full px-2 py-1.5 text-xs bg-background border border-border rounded-lg focus:outline-none focus:ring-1 focus:ring-primary font-mono"
                  />
                  <div className="flex justify-end gap-2">
                    <Button variant="outline" size="sm" className="h-6 text-[11px]" onClick={() => { setAddingChunk(false); setNewChunkText(""); }}>取消</Button>
                    <Button size="sm" className="h-6 text-[11px]" disabled={chunkBusy || !newChunkText.trim()} onClick={() => void handleChunkAdd(detail.doc.id)}>
                      {chunkBusy ? "添加中…" : "添加分段"}
                    </Button>
                  </div>
                </div>
              ) : (
                <Button variant="outline" size="sm" className="h-7 text-[11px] w-full border-dashed" onClick={() => setAddingChunk(true)}>
                  <Plus className="w-3 h-3 mr-1" /> 手动新增分段
                </Button>
              )}
            </div>
          )}
        </div>
      </Modal>
  </>);
}
