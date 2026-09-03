'use client';

import { useState } from "react";
import { Header } from "@/components/layout/Header";
import { Search, FolderPlus, CloudUpload, FileText, FileSpreadsheet, FileImage } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { FolderGrid } from "@/components/files/FolderGrid";
import { FileTable } from "@/components/files/FileTable";
import { UploadModal } from "@/components/ui/custom/UploadModal";
import { MOCK_FOLDERS, MOCK_FILES } from "@/lib/mockData";
import { useSelection } from "@/hooks/useSelection";
import { useSimulatedUpload } from "@/hooks/useUpload";
import { useFileViewer } from "@/hooks/useFileViewer";
import { FileViewerModal } from "@/components/files/viewer/FileViewerModal";
import { toast } from "sonner";
import { FileItem } from "@/types";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { useNotifications } from "@/hooks/useNotifications";
import { useRecentFiles } from "@/hooks/useRecentFiles";

export default function FilesPage() {
  const [searchQuery, setSearchQuery] = useState("");
  const [files, setFiles] = useState<FileItem[]>(MOCK_FILES);
  const upload = useSimulatedUpload();
  const viewer = useFileViewer();
  const indexFile = useKnowledgeDocs((s) => s.indexFile);
  const addNotification = useNotifications((s) => s.addNotification);
  const addRecent = useRecentFiles((s) => s.addRecent);

  const filteredFiles = files.filter((f) => f.name.toLowerCase().includes(searchQuery.toLowerCase()));
  const selection = useSelection(filteredFiles, "id");

  const handleDeleteSelected = () => {
    const count = selection.selectedIds.length;
    setFiles(files.filter((f) => !selection.selectedIds.includes(f.id)));
    selection.clearSelection();
    toast.success(`已成功移入回收站 (${count}个文件)`);
  };

  const handleUploadComplete = (fileName?: string) => {
    const ext = fileName?.split(".").pop()?.toLowerCase() ?? "pdf";
    const meta = ext === "xlsx" || ext === "csv" ? { type: "Excel 表格", Icon: FileSpreadsheet, color: "text-green-600 dark:text-green-400" }
      : ["png", "jpg", "jpeg", "gif", "webp"].includes(ext) ? { type: "图像", Icon: FileImage, color: "text-purple-500 dark:text-purple-400" }
      : { type: "PDF 文档", Icon: FileText, color: "text-red-500 dark:text-red-400" };
    const newFile: FileItem = {
      id: Date.now(),
      name: fileName ?? `上传文档_${Date.now().toString().slice(-4)}.pdf`,
      type: meta.type,
      size: "1.5 MB",
      date: new Date().toISOString().slice(0, 16).replace("T", " "),
      icon: meta.Icon,
      color: meta.color,
      indexed: false,
    };
    setFiles([newFile, ...files]);
    addRecent(newFile.name, meta.type);
    addNotification("上传完成", `「${newFile.name}」已保存到文件中心，可在列表中查看。`);
  };

  const handleIndexFile = (file: FileItem) => {
    indexFile(file.name);
    setFiles(files.map((f) => (f.id === file.id ? { ...f, indexed: true } : f)));
    addNotification("文件索引入库", `「${file.name}」已完成解析与向量化，AI 现在可以检索其内容。`);
    toast.success(`「${file.name}」已加入知识库`);
  };

  return (
    <>
      <Header
        breadcrumbs={[
          { label: "工作台", isCurrent: false },
          { label: "文件中心", isCurrent: false },
          { label: "全部文件", isCurrent: true },
        ]}
        actions={
          <div className="flex items-center gap-1 sm:gap-2 shrink-0">
            <div className="relative w-[100px] sm:w-[180px] shrink-0">
              <Search className="absolute left-2.5 top-1/2 transform -translate-y-1/2 text-gray-400 dark:text-gray-500 w-3.5 h-3.5" />
              <Input
                placeholder="搜索..."
                className="pl-7 pr-3 py-1.5 h-8 bg-gray-50 dark:bg-gray-900 border-gray-200 dark:border-gray-800 text-xs focus-visible:ring-1 focus-visible:ring-blue-500 transition-all w-full"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
            </div>
            <div className="w-px h-5 bg-gray-200 dark:bg-gray-800 mx-1 shrink-0 hidden sm:block"></div>
            <Button variant="outline" size="sm" className="h-8 text-xs bg-white dark:bg-gray-900 text-gray-700 dark:text-gray-200 hover:bg-gray-50 dark:hover:bg-gray-800 shrink-0 hidden md:flex">
              <FolderPlus className="w-3.5 h-3.5 mr-1.5" /> 新建文件夹
            </Button>
            <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 shrink-0" onClick={upload.open}>
              <CloudUpload className="w-3.5 h-3.5 sm:mr-1.5" /> <span className="hidden sm:inline">上传文件</span>
            </Button>
          </div>
        }
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 bg-[#f4f5f7] dark:bg-gray-950 relative">
        <div className="max-w-6xl mx-auto pb-24">
          <FolderGrid folders={MOCK_FOLDERS} />

          <div className="flex items-center justify-between mb-4 animate-in fade-in">
            <h2 className="text-sm font-bold text-gray-800 dark:text-gray-100">所有文件</h2>
            <div className="text-xs text-gray-500 dark:text-gray-400">共 {files.length} 个文件</div>
          </div>

          <FileTable
            files={filteredFiles}
            selection={selection}
            onDeleteSelected={handleDeleteSelected}
            onOpen={(f) => { addRecent(f.name, f.type); viewer.open(f); }}
            onIndex={handleIndexFile}
          />
        </div>
      </div>

      <UploadModal upload={upload} title="上传到文件中心" onUploadComplete={handleUploadComplete} />
      <FileViewerModal file={viewer.activeFile} preview={viewer.preview} status={viewer.status} onClose={viewer.close} />
    </>
  );
}
