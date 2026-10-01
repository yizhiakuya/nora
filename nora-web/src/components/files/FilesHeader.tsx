'use client';

import { Header } from "@/components/layout/Header";
import { Search, FolderPlus, CloudUpload } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";

import type { FileLibraryState } from "@/hooks/useFileLibrary";

export function FilesHeader({ library }: { library: FileLibraryState }) {
  const { workspaceDir, setWorkspaceDir, mediaCacheOpen, setMediaCacheOpen, trashOpen, setTrashOpen, currentFolder, setCurrentFolder, inRootView, breadcrumbTail, searchQuery, setSearchQuery, setFolderNameInput, setFolderDialog, upload } = library;
  return (<>
      <Header
        breadcrumbs={[
          { label: "Nora", href: "/", isCurrent: false },
          {
            // §7 评审:导航叫「资料」,面包屑原叫「文件中心」——名称随导航统一
            label: "资料",
            isCurrent: inRootView,
            // 非根视图(文件夹/工作区/媒体缓存/回收站内)时点击回退到资料根
            onClick: inRootView ? undefined : () => {
              setCurrentFolder(null);
              setWorkspaceDir(null);
              setMediaCacheOpen(false);
              setTrashOpen(false);
            },
          },
          ...(workspaceDir !== null
            ? [{ label: "Agent 工作区", isCurrent: true }]
            : []),
          ...(mediaCacheOpen
            ? [{ label: "媒体缓存", isCurrent: true }]
            : []),
          ...(trashOpen
            ? [{ label: "回收站", isCurrent: true }]
            : []),
          ...breadcrumbTail,
        ]}
        actions={
          <div className="flex items-center gap-1 [@container(min-width:600px)]:gap-2 shrink-0">
            {workspaceDir === null && !mediaCacheOpen && !trashOpen && (
            <>
            <div className="relative w-[88px] [@container(min-width:800px)]:w-[180px] shrink-0">
              <Search className="absolute left-2.5 top-1/2 transform -translate-y-1/2 text-muted-foreground w-3.5 h-3.5" />
              <Input
                placeholder="搜索..."
                className="pl-7 pr-3 py-1.5 h-8 bg-muted border-border text-xs focus-visible:ring-1 focus-visible:ring-blue-500 transition-all w-full"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
              />
            </div>
            <div className="w-px h-5 bg-gray-200 dark:bg-gray-800 mx-1 shrink-0 hidden [@container(min-width:600px)]:block"></div>
            <Button
              variant="outline"
              size="sm"
              className="h-8 gap-1 px-2 text-xs bg-card text-foreground hover:bg-muted shrink-0"
              title="新建文件夹"
              aria-label="新建文件夹"
              onClick={() => {
                setFolderNameInput("");
                setFolderDialog({ mode: "create" });
              }}
            >
              <FolderPlus className="w-3.5 h-3.5" /> <span className="hidden [@container(min-width:600px)]:inline">新建文件夹</span>
            </Button>
            <Button size="sm" className="h-8 gap-1 px-2 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600 shrink-0" onClick={upload.open}
              title={currentFolder ? `上传到「${currentFolder.name}」` : "上传文件"} aria-label={currentFolder ? `上传到「${currentFolder.name}」` : "上传文件"}>
              <CloudUpload className="w-3.5 h-3.5" /> <span className="hidden max-w-40 truncate [@container(min-width:600px)]:inline">{currentFolder ? `上传到「${currentFolder.name}」` : "上传文件"}</span>
            </Button>
            </>
            )}
          </div>
        }
      />
  </>);
}
