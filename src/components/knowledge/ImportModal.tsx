'use client';

import { Plus, FileText, FileDown, Database } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Modal } from "@/components/ui/custom/Modal";
import { useSimulatedUpload } from "@/hooks/useUpload";

export function KnowledgeImportModal() {
  const upload = useSimulatedUpload();

  return (
    <>
      <Button size="sm" className="h-8 text-xs bg-blue-600 hover:bg-blue-700 shrink-0" onClick={upload.open}>
        <Plus className="w-3.5 h-3.5 sm:mr-1.5" /> <span className="hidden sm:inline">导入数据源</span>
      </Button>

      <Modal
        isOpen={upload.isOpen}
        onClose={upload.close}
        title="导入数据源"
        width="w-[90%] sm:w-[500px]"
        footer={
          <>
            <Button variant="outline" size="sm" onClick={upload.close}>取消</Button>
            <Button size="sm" className="bg-blue-600 hover:bg-blue-700" onClick={upload.close}>开始导入</Button>
          </>
        }
      >
        <div className="space-y-4">
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <div className="border border-blue-200 bg-blue-50/50 rounded-lg p-4 cursor-pointer hover:bg-blue-50 transition-colors group">
              <div className="w-8 h-8 rounded-full bg-blue-100 text-blue-600 flex items-center justify-center mb-2 group-hover:scale-110 transition-transform">
                <FileText className="w-4 h-4" />
              </div>
              <div className="text-sm font-bold text-gray-800">本地文件</div>
              <div className="text-xs text-gray-500 mt-1">支持 PDF, Word, Excel, TXT 等</div>
            </div>
            <div className="border border-gray-200 rounded-lg p-4 cursor-pointer hover:border-gray-300 hover:bg-gray-50 transition-colors group">
              <div className="w-8 h-8 rounded-full bg-gray-100 text-gray-600 flex items-center justify-center mb-2 group-hover:scale-110 transition-transform">
                <Database className="w-4 h-4" />
              </div>
              <div className="text-sm font-bold text-gray-800">数据库同步</div>
              <div className="text-xs text-gray-500 mt-1">MySQL, PostgreSQL, MongoDB</div>
            </div>
          </div>

          <div className="border-2 border-dashed border-gray-200 rounded-lg p-8 flex flex-col items-center justify-center text-center bg-gray-50/50 hover:bg-gray-50 transition-colors cursor-pointer">
            <FileDown className="w-8 h-8 text-gray-400 mb-3" />
            <div className="text-sm font-medium text-gray-700">点击或拖拽文件到此处上传</div>
            <div className="text-xs text-gray-400 mt-1">单文件最大支持 50MB</div>
          </div>
        </div>
      </Modal>
    </>
  );
}
