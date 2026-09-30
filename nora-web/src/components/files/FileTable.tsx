'use client';

import { Search, MoreHorizontal, Trash2, Download, Plus, Eye, ChevronRight, Pencil, FolderInput, MessageSquarePlus } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { EmptyState } from "@/components/ui/custom/States";
import { SelectionResult } from "@/hooks/useSelection";
import { FileItem } from "@/types";
import { USE_BACKEND } from "@/lib/api/client";
import { withAuthToken } from "@/lib/auth";

type FileSelection = SelectionResult<number>;

/** 是否图片文件(列表里显示真实缩略图)。 */
function isImageFile(name: string): boolean {
  return /\.(png|jpe?g|gif|webp|bmp|svg)$/i.test(name);
}

/** 特殊文件夹行(如 Agent 工作区):文件系统视图里的"目录",点击进入。 */
export interface FolderRow {
  name: string;
  description?: string;
  icon?: LucideIcon;
  onOpen: () => void;
  /** 重命名(仅用户文件夹;系统文件夹不传) */
  onRename?: () => void;
  /** 删除(仅用户文件夹;系统文件夹不传) */
  onDelete?: () => void;
  /** 接收拖入的文件(拖拽文件到文件夹行 = 移动到该文件夹) */
  onDropFiles?: (ids: number[]) => void;
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
  /** 批量下载选中文件 */
  onDownloadSelected?: () => void;
  /** 批量移动选中文件 */
  onMoveSelected?: () => void;
  /** 单个文件操作 */
  onDownload?: (file: FileItem) => void;
  onRename?: (file: FileItem) => void;
  onMove?: (file: FileItem) => void;
  onDelete?: (file: FileItem) => void;
}

export function FileTable({ files, selection, onDeleteSelected, onOpen, onIndex, folderRow, folderRows,
                            onDownloadSelected, onMoveSelected, onDownload, onRename, onMove, onDelete }: FileTableProps) {
  // 统一为列表:folderRows 优先,兼容既有单行调用
  const folders: FolderRow[] = folderRows ?? (folderRow ? [folderRow] : []);
  return (
    <>
      <div className="bg-card border border-border rounded-xl overflow-hidden overflow-x-auto animate-in fade-in slide-in-from-bottom-4 duration-500">
        <table className="w-full text-left border-collapse min-w-[560px] table-fixed">
          {/* 列宽:文件名列自适应占满;其余固定窄列右对齐(元数据不抢主视觉)。
              响应式:colgroup 的 col 必须与 th 的 hidden 断点同步,否则隐藏列仍占宽 */}
          <colgroup>
            <col className="w-10" />
            <col />
            <col className="w-[76px] hidden lg:table-column" />
            <col className="w-[84px]" />
            <col className="w-[128px] hidden xl:table-column" />
            <col className="w-[104px]" />
          </colgroup>
          <thead>
            <tr className="bg-muted/60 border-b border-border text-xs text-muted-foreground font-medium select-none">
              <th className="p-3 pl-4">
                <input
                  type="checkbox"
                  className="w-4 h-4 rounded border-gray-300 dark:border-gray-700 text-blue-600 dark:text-blue-400 focus:ring-blue-500 cursor-pointer"
                  checked={selection.isAllSelected}
                  onChange={selection.toggleSelectAll}
                />
              </th>
              <th className="p-3 font-medium">文件名</th>
              <th className="p-3 font-medium hidden lg:table-cell">类型</th>
              <th className="p-3 font-medium text-right tabular-nums">大小</th>
              <th className="p-3 font-medium text-right tabular-nums hidden xl:table-cell">修改时间</th>
              <th className="p-3 font-medium text-right pr-4">操作</th>
            </tr>
          </thead>
          <tbody className="text-sm">
            {folders.map((folder) => (
              <tr
                key={folder.name}
                className="border-b border-border last:border-0 transition-colors group cursor-pointer hover:bg-muted/60"
                onClick={folder.onOpen}
                title={folder.description}
                // 拖拽文件到文件夹行 = 移动(有 onDropFiles 时才响应)
                onDragOver={folder.onDropFiles ? (e) => {
                  e.preventDefault();
                  e.currentTarget.classList.add("bg-blue-50", "dark:bg-blue-950/40");
                } : undefined}
                onDragLeave={folder.onDropFiles ? (e) => {
                  e.currentTarget.classList.remove("bg-blue-50", "dark:bg-blue-950/40");
                } : undefined}
                onDrop={folder.onDropFiles ? (e) => {
                  e.preventDefault();
                  e.currentTarget.classList.remove("bg-blue-50", "dark:bg-blue-950/40");
                  const raw = e.dataTransfer.getData("application/x-nora-file-ids");
                  if (!raw) return;
                  try {
                    const ids = JSON.parse(raw) as number[];
                    if (ids.length > 0) folder.onDropFiles?.(ids);
                  } catch { /* 忽略坏数据 */ }
                } : undefined}
              >
                <td className="p-3 pl-4" />
                <td className="p-3 overflow-hidden">
                  <div className="flex items-center gap-3 min-w-0">
                    {folder.icon
                      ? <folder.icon className="w-5 h-5 shrink-0 text-blue-500 dark:text-blue-400" />
                      : <FolderIcon />}
                    <span className="font-medium text-foreground group-hover:text-blue-600 dark:group-hover:text-blue-400 transition-colors truncate">
                      {folder.name}
                    </span>
                    <span className="text-[11px] text-muted-foreground/70 truncate hidden lg:inline shrink-0">{folder.description}</span>
                  </div>
                </td>
                <td className="p-3 text-muted-foreground text-xs whitespace-nowrap hidden lg:table-cell">文件夹</td>
                <td className="p-3 text-muted-foreground text-xs text-right tabular-nums whitespace-nowrap">—</td>
                <td className="p-3 text-muted-foreground text-xs text-right tabular-nums whitespace-nowrap hidden xl:table-cell">—</td>
                <td className="p-3 text-right pr-4" onClick={(e) => e.stopPropagation()}>
                  {(folder.onRename || folder.onDelete) ? (
                    <div className="flex justify-end gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                      {folder.onRename && (
                        <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950/40" title="重命名" onClick={folder.onRename}>
                          <Pencil className="w-4 h-4" />
                        </Button>
                      )}
                      {folder.onDelete && (
                        <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground hover:text-red-600 dark:hover:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/40" title="删除文件夹（文件回到根目录）" onClick={folder.onDelete}>
                          <Trash2 className="w-4 h-4" />
                        </Button>
                      )}
                    </div>
                  ) : (
                    <ChevronRight className="w-4 h-4 text-muted-foreground inline-block" />
                  )}
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
                  className={`border-b border-border last:border-0 transition-colors group cursor-pointer ${isSelected ? "bg-blue-50/60 dark:bg-blue-950/30" : "hover:bg-muted/60"}`}
                  onClick={() => selection.toggleSelect(file.id)}
                  // 可拖拽:拖到文件夹行 = 移动(数据用自定义 MIME 传递)
                  draggable
                  onDragStart={(e) => {
                    const ids = isSelected ? selection.selectedIds : [file.id];
                    e.dataTransfer.setData("application/x-nora-file-ids", JSON.stringify(ids));
                    e.dataTransfer.effectAllowed = "move";
                  }}
                >
                  <td className="p-3 pl-4" onClick={(e) => e.stopPropagation()}>
                    <input
                      type="checkbox"
                      className="w-4 h-4 rounded border-gray-300 dark:border-gray-700 text-blue-600 dark:text-blue-400 focus:ring-blue-500 cursor-pointer"
                      checked={isSelected}
                      onChange={() => selection.toggleSelect(file.id)}
                    />
                  </td>
                  <td className="p-3 overflow-hidden">
                    <div className="flex items-center gap-3 min-w-0">
                      {/* 图片文件显示真实缩略图(小圆角);mock 模式无 raw 端点,退回类型图标 */}
                      {USE_BACKEND && isImageFile(file.name) ? (
                        <img
                          src={withAuthToken(`/api/files/${file.id}/raw`)}
                          alt=""
                          loading="lazy"
                          className="w-8 h-8 rounded-md object-cover border border-border shrink-0 bg-muted"
                        />
                      ) : (
                        <Icon className={`${file.color} w-5 h-5 shrink-0`} />
                      )}
                      <button
                        type="button"
                        className="font-medium text-foreground group-hover:text-blue-600 dark:group-hover:text-blue-400 transition-colors truncate cursor-pointer text-left min-w-0"
                        title={file.name}
                        onClick={(e) => { e.stopPropagation(); onOpen(file); }}
                      >
                        {file.name}
                      </button>
                      {/* 索引状态:小圆点(悬停显示全文案;比徽章更省横向空间) */}
                      {file.indexed ? (
                        <span
                          className="w-1.5 h-1.5 rounded-full bg-green-500 shrink-0"
                          title="已索引入知识库，AI 可检索此文件内容"
                        />
                      ) : null}
                    </div>
                  </td>
                  <td className="p-3 text-muted-foreground text-xs whitespace-nowrap hidden lg:table-cell">{file.type}</td>
                  <td className="p-3 text-muted-foreground text-xs text-right tabular-nums whitespace-nowrap">{file.size}</td>
                  <td className="p-3 text-muted-foreground text-xs text-right tabular-nums whitespace-nowrap hidden xl:table-cell">{file.date}</td>
                  <td className="p-3 text-right pr-4" onClick={(e) => e.stopPropagation()}>
                    <div className="flex justify-end items-center gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
                      {/* 未索引:悬停时提供「加入知识库」快捷动作 */}
                      {!file.indexed && onIndex && (
                        <button
                          type="button"
                          title="解析文件内容并索引入知识库，AI 即可检索"
                          onClick={(e) => { e.stopPropagation(); onIndex(file); }}
                          className="inline-flex items-center gap-1 px-1.5 py-1 rounded-md text-[10px] font-medium select-none cursor-pointer text-blue-500 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950/40 transition-colors whitespace-nowrap"
                        >
                          <Plus className="w-3 h-3" /> 入知识库
                        </button>
                      )}
                      <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950/40" title="预览" onClick={() => onOpen(file)}>
                        <Eye className="w-4 h-4" />
                      </Button>
                      {onDownload && (
                        <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground hover:text-blue-600 dark:hover:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950/40" title="下载" onClick={() => onDownload(file)}>
                          <Download className="w-4 h-4" />
                        </Button>
                      )}
                      <DropdownMenu>
                        <DropdownMenuTrigger asChild>
                          <Button variant="ghost" size="icon" className="w-8 h-8 text-muted-foreground hover:text-foreground hover:bg-muted/80" title="更多操作">
                            <MoreHorizontal className="w-4 h-4" />
                          </Button>
                        </DropdownMenuTrigger>
                        <DropdownMenuContent align="end" className="w-40">
                          {onDownload && (
                            <DropdownMenuItem onClick={() => onDownload(file)}>
                              <Download className="w-3.5 h-3.5 mr-2" /> 下载
                            </DropdownMenuItem>
                          )}
                          {onRename && (
                            <DropdownMenuItem onClick={() => onRename(file)}>
                              <Pencil className="w-3.5 h-3.5 mr-2" /> 重命名
                            </DropdownMenuItem>
                          )}
                          {onMove && (
                            <DropdownMenuItem onClick={() => onMove(file)}>
                              <FolderInput className="w-3.5 h-3.5 mr-2" /> 移动到…
                            </DropdownMenuItem>
                          )}
                          {onDelete && (
                            <>
                              <DropdownMenuSeparator />
                              <DropdownMenuItem className="text-red-600 dark:text-red-400" onClick={() => onDelete(file)}>
                                <Trash2 className="w-3.5 h-3.5 mr-2" /> 删除
                              </DropdownMenuItem>
                            </>
                          )}
                        </DropdownMenuContent>
                      </DropdownMenu>
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


/**
 * 批量操作栏(2026-09-17 提取):列表/网格两视图共用,由页面级渲染
 * (此前在 FileTable 内部,网格视图缺失批量操作)。
 */
export function BatchActionBar({ selection, onDownloadSelected, onMoveSelected, onDeleteSelected, onAskAssistant }: {
  selection: FileSelection;
  onDownloadSelected?: () => void;
  onMoveSelected?: () => void;
  onDeleteSelected: () => void;
  /** 交给助手(M2-02):选中文件作为结构化引用带入对话页 */
  onAskAssistant?: () => void;
}) {
  if (!selection.hasSelection) return null;
  return (
    <div className="mb-4 p-2 sm:p-3 bg-blue-50 dark:bg-blue-950/40 border border-blue-100 dark:border-blue-900 rounded-lg flex flex-col sm:flex-row sm:items-center justify-between gap-3 animate-in fade-in slide-in-from-top-2">
      <div className="text-xs sm:text-sm font-medium text-blue-800 dark:text-blue-300">
        已选择 {selection.selectedIds.length} 个文件
      </div>
      <div className="flex items-center gap-2">
        {onAskAssistant && (
          <Button size="sm" className="h-7 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={onAskAssistant}>
            <MessageSquarePlus className="w-3.5 h-3.5 mr-1" /> 交给助手
          </Button>
        )}
        {onDownloadSelected && (
          <Button variant="outline" size="sm" className="h-7 text-xs bg-card text-foreground" onClick={onDownloadSelected}>
            <Download className="w-3.5 h-3.5 mr-1" /> 下载{selection.selectedIds.length > 1 ? "（zip）" : ""}
          </Button>
        )}
        {onMoveSelected && (
          <Button variant="outline" size="sm" className="h-7 text-xs bg-card text-foreground" onClick={onMoveSelected}>
            <FolderInput className="w-3.5 h-3.5 mr-1" /> 移动到…
          </Button>
        )}
        <Button variant="outline" size="sm" className="h-7 text-xs bg-card text-red-600 dark:text-red-400 border-red-200 dark:border-red-800 hover:bg-red-50 dark:hover:bg-red-950/40" onClick={onDeleteSelected}>
          <Trash2 className="w-3.5 h-3.5 mr-1" /> 删除
        </Button>
        <Button variant="ghost" size="sm" className="h-7 text-xs text-blue-600 dark:text-blue-400 hover:bg-blue-100 dark:hover:bg-blue-900/50" onClick={selection.clearSelection}>取消选择</Button>
      </div>
    </div>
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
