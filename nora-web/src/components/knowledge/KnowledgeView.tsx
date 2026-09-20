'use client';

import { useEffect, useState } from "react";
import { Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { UploadModal } from "@/components/ui/custom/UploadModal";
import { useSimulatedUpload } from "@/hooks/useUpload";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { useNotifications } from "@/hooks/useNotifications";
import { toast } from "sonner";
import { KnowledgeTabs } from "@/components/knowledge/KnowledgeTabs";
import { DocumentLibrary } from "@/components/knowledge/DocumentLibrary";
import { RetrievalTest } from "@/components/knowledge/RetrievalTest";
import { IndexStatus } from "@/components/knowledge/IndexStatus";
import { USE_BACKEND } from "@/lib/api/client";
import { FileItem } from "@/types";

/**
 * 长期知识视图(M1-03,2026-09-20):原知识库页主体,抽为组件供
 * 资料页(/files?view=knowledge)与原 /knowledge 路由共用。
 */
export function KnowledgeView() {
  const [activeTab, setActiveTab] = useState("文档库");
  const upload = useSimulatedUpload();
  const indexFile = useKnowledgeDocs((s) => s.indexFile);
  const indexFileFromBackend = useKnowledgeDocs((s) => s.indexFileFromBackend);
  const syncFromBackend = useKnowledgeDocs((s) => s.syncFromBackend);
  const addNotification = useNotifications((s) => s.addNotification);

  useEffect(() => { if (USE_BACKEND) void syncFromBackend().catch(() => undefined); }, [syncFromBackend]);

  const handleImport = (fileName?: string, uploadedFile?: FileItem) => {
    if (USE_BACKEND && uploadedFile) {
      // 上传已完成,再触发 rag-service 索引(异步)
      indexFileFromBackend(uploadedFile.id, uploadedFile.name)
        .then(() => {
          addNotification(
            "文档索引入库",
            `「${uploadedFile.name}」已开始清洗与向量化，完成后 AI 即可检索其内容。`,
            "indexed"
          );
          toast.success(`「${uploadedFile.name}」索引任务已提交`);
        })
        .catch((e: Error) => toast.error(`索引失败：${e.message}`));
      return;
    }
    const name = fileName ?? `导入文档_${Date.now().toString().slice(-4)}.pdf`;
    indexFile(name);
    addNotification(
      "文档索引入库",
      `「${name}」已完成清洗与向量化，AI 现在可以检索其内容。`,
      "indexed"
    );
    toast.success(`「${name}」已导入知识库`);
  };

  return (
    <>
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <p className="text-xs text-muted-foreground">
            已加入长期知识的文件与文本，AI 检索时优先从这里找内容。
          </p>
          <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={() => upload.open()}>
            <Plus className="w-3.5 h-3.5 mr-1.5" /> 导入文档
          </Button>
        </div>
        <KnowledgeTabs active={activeTab} onChange={setActiveTab} />
      </div>

      <div className="animate-in fade-in slide-in-from-bottom-4 duration-500">
        {activeTab === "文档库" && <DocumentLibrary />}
        {activeTab === "检索测试" && <RetrievalTest />}
        {activeTab === "索引状态" && <IndexStatus />}
      </div>

      <UploadModal
        upload={upload}
        title="导入文档到知识库"
        hint="支持 PDF / Word / Excel / Markdown，完成后自动清洗与索引"
        onUploadComplete={handleImport}
      />
    </>
  );
}
