'use client';

import { Search, MoreHorizontal, Trash2, Download, BookOpen, Plus, Eye, ChevronRight } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import { EmptyState } from "@/components/ui/custom/States";
import { SelectionResult } from "@/hooks/useSelection";
import { FileItem } from "@/types";
import { toast } from "sonner";

type FileSelection = SelectionResult<number>;

/** 特殊文件夹行(如 Agent 工作区):文件系统视图里的"目录",点击进入。 */
export interface FolderRow {
  name: string;
  description?: string;
  icon?: LucideIcon;
  onOpen: () => void;
}

interface FileTableProps {
  files: FileItem[];
  selection: FileSelection;
  onDeleteSelected: () => void;
  onOpen: (file: FileItem) => void;
  /** 未索引文件 → 加入知识库 */
  onIndex?: (file: FileItem) => void;
  /** 置顶显示的文件夹行(文件系统一体化:工作区/媒体缓存作为目录出现) */
  folderRow?: FolderRow;
  /** 置顶文件夹行(多行版本;与 folderRow 二选一,优先本字段) */
  folderRows?: FolderRow[];
}

export function FileTable({ files, selection, onDeleteSelected, onOpen, onIndex, folderRow, folderRows }: FileTableProps) {
  // 统一为列表:folderRows 优先,兼容既有单行调用
  const folders: FolderRow[] = folderRows ?? (folderRow ? [folderRow] : []);
  return (
    <>
      {/* Batch Action Bar */}
      {selection.hasSelection && (
        <div className="mb-4 p-2 sm:p-3 bg-blue-50 dark:bg-blue-950/40 border border-blue-100 dark:border-blue-900 rounded-lg flex flex-col sm:flex-row sm:items-center justify-between gap-3 animate-in fade-in slide-in-from-top-2">
          <div className="text-xs sm:text-sm font-medium text-blue-800 dark:text-blue-300">
            已选择 {selection.selectedIds.length} 个文件
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" className="h-7 text-xs bg-card text-foreground" onClick={() => toast.success("开始打包下载...")}>
              <Download className="w-3.5 h-3.5 mr-1" /> 下载
            </Button>
            <Button variant="outline" size="sm" className="h-7 text-xs bg-card text-red-600 dark:text-red-400 border-red-200 dark:border-red-800 hover:bg-red-50 dark:hover:bg-red-950/40" onClick={onDeleteSelected}>
              <Trash2 className="w-3.5 h-3.5 mr-1" /> 删除
            </Button>
            <Button variant="ghost" size="sm" className="h-7 text-xs text-blue-600 dark:text-blue-400 hover:bg-blue-100 dark:hover:bg-blue-900/50" onClick={selection.clearSelection}>取消选择</Button>
          </div>
        </div>
      )}

      <div className="bg-card border border-border rounded-xl overflow-hidden overflow-x-auto animate-in fade-in slide-in-from-bottom-4 duration-500">
        <table className="w-full text-left border-collapse min-w-[600px]">
          <thead>
            <tr className="bg-muted border-b border-border text-xs text-muted-foreground font-medium select-none">
              <th className="p-3 pl-4 w-10">
                <input
                  type="checkbox"
                  className="w-4 h-4 rounded border-gray-300 dark:border-gray-700 text-blue-600 dark:text-blue-400 focus:ring-blue-500 cursor-pointer"
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
            {folders.map((folder) => (
              <tr
                key={folder.name}
                className="border-b border-border transition-colors group cursor-pointer hover:bg-muted"
                onClick={folder.onOpen}
                title={folder.description}
              >
                <td className="p-3 pl-4 w-10" />
                <td className="p-3 max-w-[200px]">
                  <div className="flex items-center gap-3">
                    {folder.icon
                      ? <folder.icon className="w-5 h-5 shrink-0 text-blue-500 dark:text-blue-400" />
                      : <FolderIcon />}
                    <span className="font-medium text-foreground group-hover:text-blue-600 dark:group-hover:text-blue-400 transition-colors truncate">
                      {folder.name}
                    </span>
                    <span className="text-[10px] text-muted-foreground/70 truncate hidden md:inline ml-1">{folder.description}</span>
                  </div>
                </td>
                <td className="p-3 text-muted-foreground text-xs whitespace-nowrap">文件夹</td>
                <td className="p-3 text-muted-foreground text-xs whitespace-nowrap">—</td>
                <td className="p-3 text-muted-foreground text-xs whitespace-nowrap">—</td>
                <td className="p-3 text-right pr-4">
                  <ChevronRight className="w-4 h-4 text-muted-foreground inline-block" />
                </td>
              </tr>
            ))}
            {files.length === 0 && folders.length === 0 ? (
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
                  className={`border-b border-border transition-colors group cursor-pointer ${isSelected ? "bg-blue-50/50 dark:bg-blue-950/30" : "hover:bg-muted"}`}
                  onClick={() => selection.toggleSelect(file.id)}
                >
                  <td className="p-3 pl-4 w-10" onClick={(e) => e.stopPropagation()}>
                    <input
                      type="checkbox"
                      className="w-4 h-4 rounded border-gray-300 dark:border-gray-700 text-blue-600 dark:text-blue-400 focus:ring-blue-500 cursor-pointer"
                      checked={isSelected}
                      onChange={() => selection.toggleSelect(file.id)}
                    />
                  </td>
                  <td className="p-3 max-w-[200px]">
                    <div className="flex items-center gap-3">
                      <Icon className={`${file.color} w-5 h-5 shrink-0`} />
                      <span
                        className="font-medium text-foreground group-hover:text-blue-600 dark:group-hover:text-blue-400 transition-colors truncate cursor-pointer hover:underline underline-offset-2"
                        title="点击预览"
                        onClick={(e) => { e.stopPropagation(); onOpen(file); }}
                      >
                        {file.name}
                      </span>
                      {file.indexed ? (
                        <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] bg-green-50 dark:bg-green-950/40 text-green-600 dark:text-green-400 border border-green-100 dark:border-green-900 font-medium ml-2 select-none shrink-0" title="已索引入知识库，AI 可检索此文件内容">
                          <BookOpen className="w-2.5 h-2.5" /> 已索引
                        </span>
                      ) : onIndex && (
                        <button
                          type="button"
                          title="解析文件内容并索引入知识库，AI 即可检索"
                          onClick={(e) => { e.stopPropagation(); onIndex(file); }}
                          className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[9px] font-medium ml-2 select-none shrink-0 cursor-pointer border border-dashed border-blue-300 dark:border-blue-700 text-blue-500 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950/40 transition-colors"
                        >
                          <Plus className="w-2.5 h-2.5" /> 加入知识库
                        </button>
                      )}
                    </div>
                  </td>
                  <td className="p-3 text-muted-foreground text-xs whitespace-nowrap">{file.type}</td>
                  <td className="p-3 text-muted-foreground text-xs whitespace-nowrap">{file.size}</td>
                  <td className="p-3 text-muted-foreground text-xs whitespace-nowrap">{file.date}</td>
                  <td className="p-3 text-right pr-4" onClick={(e) => e.stopPropagation()}>
                    <div className="flex justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950/40" title="预览" onClick={() => onOpen(file)}>
                        <Eye className="w-4 h-4" />
                      </Button>
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950/40" onClick={() => toast.success(`已开始下载 ${file.name}`)}>
                        <Download className="w-4 h-4" />
                      </Button>
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground hover:text-foreground hover:bg-muted/80">
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

/** 默认文件夹图标(未提供自定义 icon 时)。 */
function FolderIcon() {
  return (
    <svg className="w-5 h-5 shrink-0 text-amber-500" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z" />
    </svg>
  );
}
