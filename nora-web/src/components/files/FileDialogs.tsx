'use client';

import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Modal } from "@/components/ui/custom/Modal";
import { UploadModal } from "@/components/ui/custom/UploadModal";
import { IndexToKnowledgeModal } from "@/components/files/IndexToKnowledgeModal";
import { HandoffChoiceDialog } from "@/components/shared/HandoffChoiceDialog";

import type { FileLibraryState } from "@/hooks/useFileLibrary";

export function FileDialogs({ library }: { library: FileLibraryState }) {
  const { currentFolder, folderNameInput, setFolderNameInput, folderDialog, setFolderDialog, upload, folders, submitFolderDialog, moveOpen, setMoveOpen, moveTargetIds, doMove, handoff, setHandoff, handleUploadComplete, syncFromBackend, refreshFolders, indexTarget, setIndexTarget, handleIndexConfirm, indexSubmitting } = library;
  return (<>
      {/* 新建/重命名文件夹弹窗 */}
      <Modal
        isOpen={folderDialog != null}
        onClose={() => setFolderDialog(null)}
        title={folderDialog?.mode === "rename" ? "重命名文件夹" : "新建文件夹"}
        width="w-[92%] sm:w-[420px]"
        footer={
          <>
            <Button variant="outline" size="sm" onClick={() => setFolderDialog(null)}>取消</Button>
            <Button size="sm" onClick={submitFolderDialog}>
              {folderDialog?.mode === "rename" ? "保存" : "创建"}
            </Button>
          </>
        }
      >
        <Input
          autoFocus
          placeholder="文件夹名称"
          value={folderNameInput}
          onChange={(e) => setFolderNameInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") void submitFolderDialog();
          }}
        />
      </Modal>

      {/* 移动文件弹窗 */}
      <Modal
        isOpen={moveOpen}
        onClose={() => setMoveOpen(false)}
        title={`移动 ${moveTargetIds.length} 个文件到…`}
        width="w-[92%] sm:w-[460px]"
      >
        <div className="space-y-1.5">
          <button
            type="button"
            className="w-full text-left px-3 py-2 rounded-lg border border-border hover:bg-muted transition-colors text-sm"
            onClick={() => void doMove(null)}
          >
            📁 根目录（不放入文件夹）
          </button>
          {folders.map((folder) => (
            <button
              key={folder.id}
              type="button"
              className="w-full text-left px-3 py-2 rounded-lg border border-border hover:bg-muted transition-colors text-sm"
              onClick={() => void doMove(folder.id)}
            >
              📁 {folder.name}
              <span className="ml-2 text-xs text-muted-foreground">{folder.fileCount} 个文件</span>
            </button>
          ))}
          {folders.length === 0 && (
            <div className="text-xs text-muted-foreground py-3 text-center">
              还没有文件夹——先在工具栏「新建文件夹」创建
            </div>
          )}
        </div>
      </Modal>

      {/* 交给助手去向选择(B6):新建处理 / 加入当前对话 */}
      <HandoffChoiceDialog
        isOpen={handoff != null}
        onClose={() => setHandoff(null)}
        prompt={handoff?.prompt ?? ""}
        refs={handoff?.refs ?? []}
      />
      <UploadModal
        upload={upload}
        title={currentFolder ? `上传到「${currentFolder.name}」` : "上传到资料"}
        onUploadComplete={handleUploadComplete}
        onBatchComplete={(ok) => {
          // 多文件批量完成:刷新列表与文件夹计数(逐个 sync 已在 onUploadComplete 做过,
          // 这里再拉一次保证一致——批量上传几十个文件时避免逐条去重遗漏)
          if (ok > 0) {
            void syncFromBackend();
            refreshFolders();
          }
        }}
      />
      {/* 加入知识库配置弹窗(F5:目标资料库 + 分段参数 + 预览) */}
      <IndexToKnowledgeModal
        file={indexTarget}
        onClose={() => setIndexTarget(null)}
        onConfirm={handleIndexConfirm}
        submitting={indexSubmitting}
      />
  </>);
}
