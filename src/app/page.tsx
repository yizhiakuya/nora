'use client';

import { useState, useEffect } from "react";
import { Header } from "@/components/layout/Header";
import { Search, CloudUpload } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { UploadModal } from "@/components/ui/custom/UploadModal";
import { useSimulatedUpload } from "@/hooks/useUpload";
import { CommandPalette } from "@/components/home/CommandPalette";
import { QuickActions } from "@/components/home/QuickActions";
import { RecentFilesTable } from "@/components/home/RecentFilesTable";
import { HomeSidePanel } from "@/components/home/HomeSidePanel";

export default function Home() {
  const [isCmdKOpen, setIsCmdKOpen] = useState(false);
  const upload = useSimulatedUpload();

  // Global Cmd+K / Escape listener
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key === "k") {
        e.preventDefault();
        setIsCmdKOpen(true);
      }
      if (e.key === "Escape") setIsCmdKOpen(false);
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, []);

  const openUpload = () => upload.open();

  const headerActions = (
    <>
      <div className="relative w-full max-w-[12rem] md:w-64 hidden sm:block" onClick={() => setIsCmdKOpen(true)}>
        <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 text-gray-400 dark:text-gray-500 w-4 h-4" />
        <Input readOnly placeholder="搜索..." className="pl-9 pr-4 py-1.5 h-8 bg-gray-50 dark:bg-gray-900 border-gray-200 dark:border-gray-800 text-xs focus-visible:ring-1 focus-visible:ring-blue-500 cursor-pointer w-full" />
        <div className="absolute right-2 top-1/2 transform -translate-y-1/2 hidden md:flex items-center gap-1 cursor-pointer">
          <kbd className="border border-gray-200 dark:border-gray-800 rounded px-1 text-[9px] text-gray-400 dark:text-gray-500 bg-white dark:bg-gray-900">⌘K</kbd>
        </div>
      </div>

      <Button variant="ghost" size="icon" className="sm:hidden h-8 w-8 text-gray-500 dark:text-gray-400" onClick={() => setIsCmdKOpen(true)}>
        <Search className="w-4 h-4" />
      </Button>

      <div className="w-px h-5 bg-gray-200 dark:bg-gray-800 mx-1 sm:mx-2"></div>

      <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 hidden sm:flex" onClick={openUpload}>
        <CloudUpload className="w-3.5 h-3.5 mr-1.5" /> 上传文件
      </Button>

      <Button size="icon" className="h-8 w-8 bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 sm:hidden rounded-lg" onClick={openUpload}>
        <CloudUpload className="w-4 h-4 text-white" />
      </Button>
    </>
  );

  return (
    <>
      <Header
        breadcrumbs={[{ label: "AI 工作台", isCurrent: false }, { label: "概览", isCurrent: true }]}
        actions={headerActions}
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-4 sm:p-6 relative">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="flex items-center justify-between mb-2 animate-in fade-in slide-in-from-bottom-2">
            <div>
              <h1 className="text-xl sm:text-2xl font-bold text-gray-800 dark:text-gray-100">早上好，Nora！</h1>
              <p className="text-xs sm:text-sm text-gray-500 dark:text-gray-400 mt-1">今天你想让 AI 帮你处理什么工作？</p>
            </div>
          </div>

          <QuickActions />

          <div className="flex flex-col lg:flex-row gap-6 mt-6 animate-in fade-in slide-in-from-bottom-4 duration-700">
            <RecentFilesTable />
            <HomeSidePanel />
          </div>
        </div>
      </div>

      <UploadModal upload={upload} title="上传到个人空间" hint="支持 PDF, DOCX, XLSX, 图片等格式" />
      <CommandPalette isOpen={isCmdKOpen} onClose={() => setIsCmdKOpen(false)} />
    </>
  );
}
