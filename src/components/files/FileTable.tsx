'use client';

import { Search, MoreHorizontal, Trash2, Download, Bot, Eye } from "lucide-react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/custom/States";
import { useSelection } from "@/hooks/useSelection";
import { FileItem } from "@/types";
import { toast } from "sonner";

type FileSelection = ReturnType<typeof useSelection<FileItem>>;

interface FileTableProps {
  files: FileItem[];
  selection: FileSelection;
  onDeleteSelected: () => void;
  onOpen: (file: FileItem) => void;
}

export function FileTable({ files, selection, onDeleteSelected, onOpen }: FileTableProps) {
  return (
    <>
      {/* Batch Action Bar */}
      {selection.hasSelection && (
        <div className="mb-4 p-2 sm:p-3 bg-blue-50 border border-blue-100 rounded-lg flex flex-col sm:flex-row sm:items-center justify-between gap-3 animate-in fade-in slide-in-from-top-2">
          <div className="text-xs sm:text-sm font-medium text-blue-800">
            已选择 {selection.selectedIds.length} 个文件
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" className="h-7 text-xs bg-white text-gray-700" onClick={() => toast.success("开始打包下载...")}>
              <Download className="w-3.5 h-3.5 mr-1" /> 下载
            </Button>
            <Button variant="outline" size="sm" className="h-7 text-xs bg-white text-red-600 border-red-200 hover:bg-red-50" onClick={onDeleteSelected}>
              <Trash2 className="w-3.5 h-3.5 mr-1" /> 删除
            </Button>
            <Button variant="ghost" size="sm" className="h-7 text-xs text-blue-600 hover:bg-blue-100" onClick={selection.clearSelection}>取消选择</Button>
          </div>
        </div>
      )}

      <div className="bg-white border border-gray-200 rounded-xl overflow-hidden overflow-x-auto animate-in fade-in slide-in-from-bottom-4 duration-500">
        <table className="w-full text-left border-collapse min-w-[600px]">
          <thead>
            <tr className="bg-gray-50 border-b border-gray-200 text-xs text-gray-500 font-medium select-none">
              <th className="p-3 pl-4 w-10">
                <input
                  type="checkbox"
                  className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 cursor-pointer"
                  checked={selection.isAllSelected}
                  onChange={selection.toggleSelectAll}
                />
              </th>
              <th className="p-3">文件名</th>
              <th className="p-3">类型</th>
              <th className="p-3">大小</th>
              <th className="p-3">修改时间</th>
              <th className="p-3 text-right pr-4">操作</th>
            </tr>
          </thead>
          <tbody className="text-sm">
            {files.length === 0 ? (
              <tr>
                <td colSpan={6}>
                  <EmptyState icon={Search} title="没有找到匹配的文件" description="换个关键词搜索，或者上传一份新文件吧。" />
                </td>
              </tr>
            ) : files.map((file) => {
              const Icon = file.icon;
              const isSelected = selection.selectedIds.includes(file.id);
              return (
                <tr
                  key={file.id}
                  className={`border-b border-gray-100 transition-colors group cursor-pointer ${isSelected ? "bg-blue-50/50" : "hover:bg-gray-50"}`}
                  onClick={() => selection.toggleSelect(file.id)}
                >
                  <td className="p-3 pl-4 w-10" onClick={(e) => e.stopPropagation()}>
                    <input
                      type="checkbox"
                      className="w-4 h-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500 cursor-pointer"
                      checked={isSelected}
                      onChange={() => selection.toggleSelect(file.id)}
                    />
                  </td>
                  <td className="p-3 max-w-[200px]">
                    <div className="flex items-center gap-3">
                      <Icon className={`${file.color} w-5 h-5 shrink-0`} />
                      <span
                        className="font-medium text-gray-800 group-hover:text-blue-600 transition-colors truncate cursor-pointer hover:underline underline-offset-2"
                        title="点击预览"
                        onClick={(e) => { e.stopPropagation(); onOpen(file); }}
                      >
                        {file.name}
                      </span>
                      {file.agent && (
                        <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] bg-blue-50 text-blue-600 border border-blue-100 font-medium ml-2 select-none shrink-0">
                          <Bot className="w-2.5 h-2.5" /> {file.agent}
                        </span>
                      )}
                    </div>
                  </td>
                  <td className="p-3 text-gray-500 text-xs whitespace-nowrap">{file.type}</td>
                  <td className="p-3 text-gray-500 text-xs whitespace-nowrap">{file.size}</td>
                  <td className="p-3 text-gray-500 text-xs whitespace-nowrap">{file.date}</td>
                  <td className="p-3 text-right pr-4" onClick={(e) => e.stopPropagation()}>
                    <div className="flex justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-gray-400 hover:text-blue-600 hover:bg-blue-50" title="预览" onClick={() => onOpen(file)}>
                        <Eye className="w-4 h-4" />
                      </Button>
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-gray-400 hover:text-blue-600 hover:bg-blue-50" onClick={() => toast.success(`已开始下载 ${file.name}`)}>
                        <Download className="w-4 h-4" />
                      </Button>
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-gray-400 hover:text-gray-800 hover:bg-gray-100">
                        <MoreHorizontal className="w-4 h-4" />
                      </Button>
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </>
  );
}
