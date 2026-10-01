'use client';

import { useRef, useState } from "react";
import {
  ChevronRight, Download, Eye, FileText, FolderInput, MoreHorizontal, Pencil, Trash2,
} from "lucide-react";
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
import { Search } from "lucide-react";
import { withAuthToken } from "@/lib/auth";
import type { FolderRow } from "./FileTable";

type FileSelection = SelectionResult<number>;

interface FileGridProps {
  files: FileItem[];
  selection: FileSelection;
  onOpen: (file: FileItem) => void;
  /** 置顶文件夹(与列表视图同一数据;网格里也是卡片) */
  folderRows?: FolderRow[];
  onDownload?: (file: FileItem) => void;
  onRename?: (file: FileItem) => void;
  onMove?: (file: FileItem) => void;
  onDelete?: (file: FileItem) => void;
}

/** 是否图片文件(网格显示真实缩略图)。 */
function isImageFile(name: string): boolean {
  return /\.(png|jpe?g|gif|webp|bmp|svg)$/i.test(name);
}

/** 是否视频文件(显示播放角标;缩略图仍走 raw——浏览器对视频无法直接 <img>,用类型图标兜底)。 */
function isVideoFile(name: string): boolean {
  return /\.(mp4|webm|mov|m4v|mkv|ogv)$/i.test(name);
}

/**
 * 文件网格视图(2026-09-17):缩略图优先的卡片布局。
 *
 * 与列表视图同一数据源/选择模型(切换不丢选中);图片显示真实缩略图,
 * 其他类型显示大号类型图标;文件夹排在最前(与列表一致的置顶逻辑)。
 * 卡片:悬停显示操作(打开/更多),拖拽到文件夹卡片可移动。
 */
export function FileGrid({ files, selection, onOpen, folderRows, onDownload, onRename, onMove, onDelete }: FileGridProps) {
  const folders = folderRows ?? [];
  // 拖拽悬停的文件夹名(高亮反馈)
  const [dragOverFolder, setDragOverFolder] = useState<string | null>(null);
  const dragging = useRef(false);

  return (
    <>
      {files.length === 0 && folders.length === 0 ? (
        <div className="bg-card border border-border rounded-xl">
          <EmptyState icon={Search} title="没有找到匹配的文件" description="换个关键词搜索，或者上传一份新文件吧。" />
        </div>
      ) : (
        <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-4 lg:grid-cols-5 xl:grid-cols-6 gap-3">
          {/* 文件夹卡片(置顶) */}
          {folders.map((folder) => {
            const FIcon = folder.icon;
            const isOver = dragOverFolder === folder.name;
            return (
              <div
                key={folder.name}
                className={`group relative bg-card border rounded-xl overflow-hidden cursor-pointer transition-all hover:shadow-md hover:-translate-y-0.5 ${isOver ? "border-blue-400 ring-2 ring-blue-200 dark:ring-blue-900" : "border-border"}`}
                onClick={folder.onOpen}
                title={folder.description}
                onDragOver={folder.onDropFiles ? (e) => {
                  e.preventDefault();
                  setDragOverFolder(folder.name);
                } : undefined}
                onDragLeave={folder.onDropFiles ? () => setDragOverFolder(null) : undefined}
                onDrop={folder.onDropFiles ? (e) => {
                  e.preventDefault();
                  setDragOverFolder(null);
                  const raw = e.dataTransfer.getData("application/x-nora-file-ids");
                  if (!raw) return;
                  try {
                    const ids = JSON.parse(raw) as number[];
                    if (ids.length > 0) folder.onDropFiles?.(ids);
                  } catch { /* 忽略坏数据 */ }
                } : undefined}
              >
                {/* 文件夹图标区 */}
                <div className="aspect-[4/3] flex items-center justify-center bg-gradient-to-br from-amber-50 to-amber-100/50 dark:from-amber-950/30 dark:to-amber-900/10">
                  {FIcon
                    ? <FIcon className="w-12 h-12 text-blue-500 dark:text-blue-400" />
                    : (
                      <svg className="w-12 h-12 text-amber-400" viewBox="0 0 24 24" fill="currentColor">
                        <path d="M20 20a2 2 0 0 0 2-2V8a2 2 0 0 0-2-2h-7.9a2 2 0 0 1-1.69-.9L9.6 3.9A2 2 0 0 0 7.93 3H4a2 2 0 0 0-2 2v13a2 2 0 0 0 2 2Z" />
                      </svg>
                    )}
                  {isOver && (
                    <span className="absolute inset-0 bg-blue-500/10 flex items-center justify-center text-[11px] font-medium text-blue-600 dark:text-blue-300">
                      松开以移动到这里
                    </span>
                  )}
                </div>
                <div className="px-2.5 py-2">
                  <div className="text-xs font-medium text-foreground truncate">{folder.name}</div>
                  <div className="text-[10px] text-muted-foreground truncate mt-0.5">{folder.description ?? "文件夹"}</div>
                </div>
                {/* 悬停操作:重命名/删除(仅用户文件夹) */}
                {(folder.onRename || folder.onDelete) && (
                  <div className="absolute top-1.5 right-1.5 flex gap-0.5 opacity-0 group-hover:opacity-100 transition-opacity">
                    {folder.onRename && (
                      <button
                        type="button"
                        title="重命名"
                        onClick={(e) => { e.stopPropagation(); folder.onRename?.(); }}
                        className="p-1.5 rounded-md bg-black/45 text-white/80 hover:text-white hover:bg-black/65 cursor-pointer backdrop-blur-sm"
                      >
                        <Pencil className="w-3 h-3" />
                      </button>
                    )}
                    {folder.onDelete && (
                      <button
                        type="button"
                        title="删除文件夹（文件回到根目录）"
                        onClick={(e) => { e.stopPropagation(); folder.onDelete?.(); }}
                        className="p-1.5 rounded-md bg-black/45 text-white/80 hover:text-white hover:bg-red-600/80 cursor-pointer backdrop-blur-sm"
                      >
                        <Trash2 className="w-3 h-3" />
                      </button>
                    )}
                  </div>
                )}
              </div>
            );
          })}

          {/* 文件卡片 */}
          {files.map((file) => {
            const Icon = file.icon;
            const isSelected = selection.selectedIds.includes(file.id);
            return (
              <div
                key={file.id}
                draggable
                onDragStart={(e) => {
                  dragging.current = true;
                  const ids = isSelected ? selection.selectedIds : [file.id];
                  e.dataTransfer.setData("application/x-nora-file-ids", JSON.stringify(ids));
                  e.dataTransfer.effectAllowed = "move";
                }}
                onDragEnd={() => { dragging.current = false; }}
                className={`group relative bg-card border rounded-xl overflow-hidden cursor-pointer transition-all hover:shadow-md hover:-translate-y-0.5 ${isSelected ? "border-blue-400 ring-2 ring-blue-200 dark:ring-blue-900" : "border-border"}`}
                onClick={(e) => {
                  // 点击卡片:选中(与列表一致);打开走缩略图/名称或双击
                  if (e.detail === 2) {
                    onOpen(file);
                    return;
                  }
                  selection.toggleSelect(file.id);
                }}
                title={file.name}
              >
                {/* 缩略图区 */}
                <div className="aspect-[4/3] bg-muted/50 flex items-center justify-center overflow-hidden">
                  {isImageFile(file.name) ? (
                    <img
                      src={withAuthToken(`/api/files/${file.id}/raw`)}
                      alt={file.name}
                      loading="lazy"
                      className="w-full h-full object-cover transition-transform duration-200 group-hover:scale-[1.04]"
                    />
                  ) : (
                    <Icon className={`${file.color} w-10 h-10 opacity-80`} />
                  )}
                  {isVideoFile(file.name) && (
                    <span className="absolute top-1.5 left-1.5 w-5 h-5 rounded-full bg-black/55 flex items-center justify-center">
                      <svg className="w-2.5 h-2.5 text-white fill-white" viewBox="0 0 24 24"><path d="M8 5v14l11-7z" /></svg>
                    </span>
                  )}
                  {/* 选中角标 */}
                  <span
                    className={`absolute top-1.5 left-1.5 w-4 h-4 rounded border flex items-center justify-center transition-all ${isSelected ? "bg-blue-500 border-blue-500" : "bg-black/25 border-white/60 opacity-0 group-hover:opacity-100"}`}
                    onClick={(e) => { e.stopPropagation(); selection.toggleSelect(file.id); }}
                  >
                    {isSelected && (
                      <svg className="w-2.5 h-2.5 text-white" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3.5"><path d="M20 6 9 17l-5-5" /></svg>
                    )}
                  </span>
                  {/* 索引绿点 */}
                  {file.indexed && (
                    <span className="absolute top-1.5 right-1.5 w-2 h-2 rounded-full bg-green-500 ring-2 ring-white/70 dark:ring-black/40" title="已索引入知识库" />
                  )}
                </div>
                {/* 信息区 */}
                <div className="px-2.5 py-2">
                  <div className="text-xs font-medium text-foreground truncate">{file.name}</div>
                  <div className="text-[10px] text-muted-foreground truncate mt-0.5 tabular-nums">
                    {file.size} · {file.date}
                  </div>
                </div>
                {/* 操作按钮:桌面悬停显示;触屏(<md,网格是唯一视图)恒显——
                    否则触屏用户没有可用入口(双击被浏览器当缩放) */}
                <div className="absolute top-1.5 right-1.5 flex gap-0.5 transition-opacity md:opacity-0 md:group-hover:opacity-100">
                  <button
                    type="button"
                    title="预览"
                    onClick={(e) => { e.stopPropagation(); onOpen(file); }}
                    className="p-1.5 rounded-md bg-black/45 text-white/80 hover:text-white hover:bg-black/65 cursor-pointer backdrop-blur-sm"
                  >
                    <Eye className="w-3 h-3" />
                  </button>
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <button
                        type="button"
                        title="更多操作"
                        onClick={(e) => e.stopPropagation()}
                        className="p-1.5 rounded-md bg-black/45 text-white/80 hover:text-white hover:bg-black/65 cursor-pointer backdrop-blur-sm"
                      >
                        <MoreHorizontal className="w-3 h-3" />
                      </button>
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
              </div>
            );
          })}
        </div>
      )}
    </>
  );
}
