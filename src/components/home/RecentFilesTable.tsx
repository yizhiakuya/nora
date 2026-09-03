'use client';

import { useRouter } from "next/navigation";
import { Clock, FileText, FileSpreadsheet, BookOpen } from "lucide-react";
import { useFileViewer } from "@/hooks/useFileViewer";
import { FileViewerModal } from "@/components/files/viewer/FileViewerModal";
import { FileItem } from "@/types";

const RECENT_FILES = [
  { name: "2024_Q2_产品规划.pdf", icon: FileText, iconClass: "text-red-500 dark:text-red-400", indexed: true, time: "10 分钟前" },
  { name: "竞品分析数据.xlsx", icon: FileSpreadsheet, iconClass: "text-green-600 dark:text-green-400", indexed: true, time: "2 小时前" },
];

export function RecentFilesTable() {
  const router = useRouter();
  const viewer = useFileViewer();

  const openRecent = (name: string) => {
    const isExcel = name.endsWith(".xlsx");
    const file: FileItem = {
      id: isExcel ? 901 : 900,
      name,
      type: isExcel ? "Excel 表格" : "PDF 文档",
      size: isExcel ? "1.2 MB" : "2.4 MB",
      date: "刚刚",
      icon: isExcel ? FileSpreadsheet : FileText,
      color: isExcel ? "text-green-600 dark:text-green-400" : "text-red-500 dark:text-red-400",
      indexed: false,
    };
    void viewer.open(file);
  };

  return (
    <div className="w-full lg:w-[65%] flex flex-col space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-bold text-gray-800 dark:text-gray-100 flex items-center gap-2">
          <Clock className="w-4 h-4 text-blue-500 dark:text-blue-400" /> 最近使用
        </h2>
        <span className="text-xs text-blue-600 dark:text-blue-400 cursor-pointer hover:underline" onClick={() => router.push("/files")}>查看全部</span>
      </div>

      <div className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-xl shadow-sm overflow-hidden overflow-x-auto">
        <table className="w-full min-w-[500px] text-left border-collapse">
          <thead>
            <tr className="bg-gray-50/80 dark:bg-gray-900/80 border-b border-gray-200 dark:border-gray-800 text-[11px] text-gray-500 dark:text-gray-400 font-medium">
              <th className="p-3 pl-4">文件名</th>
              <th className="p-3">索引状态</th>
              <th className="p-3 whitespace-nowrap">打开时间</th>
            </tr>
          </thead>
          <tbody className="text-sm">
            {RECENT_FILES.map(({ name, icon: Icon, iconClass, indexed, time }) => (
              <tr key={name} className="border-b border-gray-50 dark:border-gray-800 hover:bg-gray-50 dark:hover:bg-gray-800 transition-colors group">
                <td className="p-3 pl-4">
                  <div className="flex items-center gap-3">
                    <Icon className={`${iconClass} w-5 h-5 flex-shrink-0`} />
                    <div
                      className="font-medium text-gray-800 dark:text-gray-100 group-hover:text-blue-600 dark:group-hover:text-blue-400 cursor-pointer hover:underline underline-offset-2 truncate max-w-[150px] sm:max-w-[200px]"
                      title="点击预览"
                      onClick={() => openRecent(name)}
                    >
                      {name}
                    </div>
                  </div>
                </td>
                <td className="p-3">
                  <span
                    onClick={() => router.push("/knowledge")}
                    className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[10px] border cursor-pointer hover:opacity-80 transition-colors whitespace-nowrap ${indexed ? "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-400 border-green-100 dark:border-green-900" : "bg-gray-50 dark:bg-gray-800 text-gray-500 dark:text-gray-400 border-gray-200 dark:border-gray-700"}`}
                  >
                    <BookOpen className="w-3 h-3" /> {indexed ? "已索引" : "未索引"}
                  </span>
                </td>
                <td className="p-3 text-[11px] text-gray-500 dark:text-gray-400 whitespace-nowrap">{time}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <FileViewerModal file={viewer.activeFile} preview={viewer.preview} status={viewer.status} onClose={viewer.close} />
    </div>
  );
}
