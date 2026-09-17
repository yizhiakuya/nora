'use client';

import { useEffect, useState } from "react";
import { Header } from "@/components/layout/Header";
import { Search, FolderPlus, CloudUpload, Bot, HardDrive } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { FolderGrid } from "@/components/files/FolderGrid";
import { FileTable } from "@/components/files/FileTable";
import { UploadModal } from "@/components/ui/custom/UploadModal";
import { useSelection } from "@/hooks/useSelection";
import { useSimulatedUpload } from "@/hooks/useUpload";
import { useFileViewer } from "@/hooks/useFileViewer";
import { FileViewerModal } from "@/components/files/viewer/FileViewerModal";
import { WorkspaceBrowser } from "@/components/files/WorkspaceBrowser";
import { MediaCacheBrowser } from "@/components/files/MediaCacheBrowser";
import { toast } from "sonner";
import { FileItem } from "@/types";
import { useFiles } from "@/hooks/useFiles";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { useNotifications } from "@/hooks/useNotifications";
import { useRecentFiles } from "@/hooks/useRecentFiles";
import { filesApi } from "@/lib/services/filesApi";
import { USE_BACKEND } from "@/lib/api/client";

export default function FilesPage() {
  const [searchQuery, setSearchQuery] = useState("");
  /** 工作区导航状态:null=文件中心根视图;""=工作区根目录;"memory/..."=子目录。
   *  工作区是文件系统的一部分——像普通文件夹一样进入,而不是独立 Tab。 */
  const [workspaceDir, setWorkspaceDir] = useState<string | null>(null);
  /** 媒体缓存文件夹视图(与工作区互斥)。 */
  const [mediaCacheOpen, setMediaCacheOpen] = useState(false);
  const files = useFiles((s) => s.files);
  const addFile = useFiles((s) => s.addFile);
  const deleteFiles = useFiles((s) => s.deleteFiles);
  const markIndexed = useFiles((s) => s.markIndexed);
  const syncFile = useFiles((s) => s.syncFile);

  const upload = useSimulatedUpload();
  const viewer = useFileViewer();
  const indexFile = useKnowledgeDocs((s) => s.indexFile);
  const indexFileFromBackend = useKnowledgeDocs((s) => s.indexFileFromBackend);
  const addNotification = useNotifications((s) => s.addNotification);
  const addRecent = useRecentFiles((s) => s.addRecent);

  // 后端模式:进入页面拉一次真实文件列表
  useEffect(() => {
    if (!USE_BACKEND) return;
    filesApi.listFiles()
      .then((items) => items.forEach((item) => syncFile(item)))
      .catch(() => { /* 后端不可用时沿用本地缓存 */ });
  }, [syncFile]);

  const filteredFiles = files.filter((f) => f.name.toLowerCase().includes(searchQuery.toLowerCase()));
  const selection = useSelection(filteredFiles, "id");

  const handleDeleteSelected = () => {
    const count = selection.selectedIds.length;
    if (USE_BACKEND) {
      filesApi.deleteFiles(selection.selectedIds)
        .then(() => toast.success(`已删除 ${count} 个文件`))
        .catch((e: Error) => toast.error(`删除失败：${e.message}`));
    } else {
      toast.success(`已删除 ${count} 个文件`);
    }
    deleteFiles(selection.selectedIds);
    selection.clearSelection();
  };

  const handleUploadComplete = (fileName?: string, uploadedFile?: FileItem) => {
    if (uploadedFile) {
      syncFile(uploadedFile);
      addRecent(uploadedFile.name, uploadedFile.type);
      addNotification("上传完成", `「${uploadedFile.name}」已保存到文件中心，可在列表中查看。`);
      return;
    }
    const defaultName = `上传文档_${Date.now().toString().slice(-4)}.pdf`;
    const newFile = addFile(fileName ?? defaultName);
    addRecent(newFile.name, newFile.type);
    addNotification("上传完成", `「${newFile.name}」已保存到文件中心，可在列表中查看。`);
  };

  const handleIndexFile = (file: FileItem) => {
    if (USE_BACKEND) {
      indexFileFromBackend(file.id, file.name)
        .then(() => {
          // 索引是异步的:file-service 触发 rag-service,完成后回调置位;这里先乐观标记
          markIndexed(file.id);
          addNotification(
            "文件索引入库",
            `「${file.name}」已开始解析与向量化，完成后 AI 即可检索其内容。`,
            "indexed"
          );
          toast.success(`「${file.name}」索引任务已提交`);
        })
        .catch((e: Error) => toast.error(`索引失败：${e.message}`));
      return;
    }
    indexFile(file.name);
    markIndexed(file.id);
    addNotification(
      "文件索引入库",
      `「${file.name}」已完成解析与向量化，AI 现在可以检索其内容。`,
      "indexed"
    );
    toast.success(`「${file.name}」已加入知识库`);
  };

  return (
    <>
      <Header
        breadcrumbs={[
          { label: "工作台", isCurrent: false },
          { label: "文件中心", isCurrent: workspaceDir === null && !mediaCacheOpen },
          ...(workspaceDir !== null
            ? [{ label: "Agent 工作区", isCurrent: true }]
            : []),
          ...(mediaCacheOpen
            ? [{ label: "媒体缓存", isCurrent: true }]
            : []),
        ]}
        actions={
          <div className="flex items-center gap-1 sm:gap-2 shrink-0">
            {workspaceDir === null && !mediaCacheOpen && (
            <>
            <div className="relative w-[100px] sm:w-[180px] shrink-0">
              <Search className="absolute left-2.5 top-1/2 transform -translate-y-1/2 text-muted-foreground w-3.5 h-3.5" />
              <Input
                placeholder="搜索..."
                className="pl-7 pr-3 py-1.5 h-8 bg-muted border-border text-xs focus-visible:ring-1 focus-visible:ring-blue-500 transition-all w-full"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
            </div>
            <div className="w-px h-5 bg-gray-200 dark:bg-gray-800 mx-1 shrink-0 hidden sm:block"></div>
            <Button variant="outline" size="sm" className="h-8 text-xs bg-card text-foreground hover:bg-muted shrink-0 hidden md:flex">
              <FolderPlus className="w-3.5 h-3.5 mr-1.5" /> 新建文件夹
            </Button>
            <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 shrink-0" onClick={upload.open}>
              <CloudUpload className="w-3.5 h-3.5 sm:mr-1.5" /> <span className="hidden sm:inline">上传文件</span>
            </Button>
            </>
            )}
          </div>
        }
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-background relative">
        <div className="max-w-6xl mx-auto pb-24">
          {mediaCacheOpen ? (
            <MediaCacheBrowser onExit={() => setMediaCacheOpen(false)} />
          ) : workspaceDir === null ? (
            <>
              <div className="flex items-center justify-between mb-4 animate-in fade-in">
                <h2 className="text-sm font-bold text-foreground">所有文件</h2>
                <div className="text-xs text-muted-foreground">共 {files.length} 个文件</div>
              </div>

              <FileTable
                files={filteredFiles}
                selection={selection}
                onDeleteSelected={handleDeleteSelected}
                onOpen={(f) => { addRecent(f.name, f.type); viewer.open(f); }}
                onIndex={handleIndexFile}
                folderRows={[
                  {
                    name: "Agent 工作区",
                    description: "AI 的工作目录与长期记忆(SOUL/AGENTS/USER/MEMORY.md)",
                    icon: Bot,
                    onOpen: () => setWorkspaceDir(""),
                  },
                  {
                    name: "媒体缓存",
                    description: "相册等远程媒体的本地副本(查看秒开、手机离线可看)",
                    icon: HardDrive,
                    onOpen: () => setMediaCacheOpen(true),
                  },
                ]}
              />
            </>
          ) : (
            <WorkspaceBrowser
              dir={workspaceDir}
              onNavigate={setWorkspaceDir}
              onExit={() => setWorkspaceDir(null)}
            />
          )}
        </div>
      </div>

      <UploadModal upload={upload} title="上传到文件中心" onUploadComplete={handleUploadComplete} />
      <FileViewerModal file={viewer.activeFile} preview={viewer.preview} status={viewer.status} onClose={viewer.close} />
    </>
  );
}
