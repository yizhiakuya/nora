'use client';

import { Button } from "@/components/ui/button";
import { Modal } from "@/components/ui/custom/Modal";

import type { DocumentLibraryState } from "@/hooks/useDocumentLibrary";

export function DocumentDialogs({ library }: { library: DocumentLibraryState }) {
  const { busyId, renaming, setRenaming, renameValue, setRenameValue, confirmDelete, setConfirmDelete, runDelete, submitRename } = library;
  return (<>
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

  </>);
}
