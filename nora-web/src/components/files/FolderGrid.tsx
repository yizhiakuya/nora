'use client';

import { Folder } from "lucide-react";
import { toast } from "sonner";
import { FolderItem } from "@/types";

export function FolderGrid({ folders }: { folders: FolderItem[] }) {
  return (
    <div className="animate-in fade-in slide-in-from-top-4 duration-500">
      <h2 className="text-sm font-bold text-gray-800 dark:text-gray-100 mb-4">文件夹</h2>
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-3 sm:gap-4 mb-8">
        {folders.map((folder, idx) => (
          <div
            key={idx}
            className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-xl p-3 sm:p-4 flex items-center gap-3 cursor-pointer hover:shadow-md hover:border-blue-300 dark:hover:border-blue-700 transition-all group"
            onClick={() => toast.info(`打开文件夹: ${folder.name}`)}
          >
            <div className="w-8 h-8 sm:w-10 sm:h-10 bg-blue-50 dark:bg-blue-950/40 rounded-lg flex items-center justify-center text-blue-500 dark:text-blue-400 group-hover:scale-110 transition-transform shrink-0">
              <Folder className="w-4 h-4 sm:w-5 sm:h-5 fill-blue-500/20" />
            </div>
            <div className="min-w-0">
              <div className="text-xs sm:text-sm font-medium text-gray-800 dark:text-gray-100 group-hover:text-blue-700 dark:group-hover:text-blue-400 transition-colors truncate">{folder.name}</div>
              <div className="text-[10px] text-gray-500 dark:text-gray-400">{folder.count} 个文件</div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
