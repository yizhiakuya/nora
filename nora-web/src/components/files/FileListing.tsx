'use client';

import { LayoutGrid, List, Search } from "lucide-react";
import { FileTable, BatchActionBar } from "@/components/files/FileTable";
import { FileGrid } from "@/components/files/FileGrid";

import type { FileLibraryState } from "@/hooks/useFileLibrary";
import { useFiles } from "@/hooks/useFiles";
import { EmptyState, ResourceStatus } from "@/components/ui/custom/States";
import { Button } from "@/components/ui/button";

export function FileListing({ library }: { library: FileLibraryState }) {
  const { loading: filesLoading, syncError, syncFromBackend, files } = useFiles();
  const loading = filesLoading || library.foldersLoading;
  const error = Array.from(new Set([syncError, library.foldersError].filter(Boolean))).join("\n") || null;
  const retry = async () => { await Promise.all([syncFromBackend(), library.refreshFolders()]); };
  if (files.length === 0 && library.folders.length === 0 && (loading || error)) return <ResourceStatus loading={loading} error={error} retry={retry} />;
  const { currentFolder, setCurrentFolder, viewMode, switchView, sortBy, setSortAsc, setSortBy, sortAsc, viewFiles, folders, selection, handleDownloadSelected, handleMoveSelected, handleDeleteSelected, handleAskAssistant, effectiveView, filteredFiles, viewer, handleIndexFile, handleDownloadOne, handleRenameFile, handleMoveOne, handleDeleteOne, folderRowsData } = library;
  const { searchQuery, setSearchQuery } = library;
  return (<>
              <ResourceStatus loading={loading} error={error} retry={retry} />
              {/* 面包屑(文件夹内可点击返回根) */}
              {currentFolder && (
                <div className="flex items-center gap-1.5 mb-4 text-xs animate-in fade-in">
                  <button
                    type="button"
                    className="text-muted-foreground hover:text-foreground transition-colors"
                    onClick={() => setCurrentFolder(null)}
                  >
                    资料
                  </button>
                  <span className="text-muted-foreground/50">/</span>
                  <span className="text-foreground font-medium">{currentFolder.name}</span>
                </div>
              )}

              <div className="flex items-center justify-between mb-4 animate-in fade-in gap-3 flex-wrap">
                <h2 className="text-sm font-semibold text-foreground">{currentFolder ? currentFolder.name : "所有文件"}</h2>
                <div className="flex items-center gap-3">
                  {/* 视图切换:列表 / 网格(窄屏自动网格,切换按钮隐藏) */}
                  <div className="hidden md:flex items-center rounded-lg border border-border overflow-hidden">
                    <button
                      type="button"
                      title="列表视图"
                      onClick={() => switchView("list")}
                      className={`p-1.5 transition-colors cursor-pointer ${viewMode === "list" ? "bg-muted text-foreground" : "text-muted-foreground hover:text-foreground"}`}
                    >
                      <List className="w-3.5 h-3.5" />
                    </button>
                    <button
                      type="button"
                      title="网格视图"
                      onClick={() => switchView("grid")}
                      className={`p-1.5 transition-colors cursor-pointer ${viewMode === "grid" ? "bg-muted text-foreground" : "text-muted-foreground hover:text-foreground"}`}
                    >
                      <LayoutGrid className="w-3.5 h-3.5" />
                    </button>
                  </div>
                  {/* 排序控件:名称/大小/时间,点击切换升降序 */}
                  <div className="flex items-center gap-1 text-xs">
                    {([
                      ["date", "时间"],
                      ["name", "名称"],
                      ["size", "大小"],
                    ] as const).map(([key, label]) => (
                      <button
                        key={key}
                        type="button"
                        className={`px-1.5 py-0.5 rounded transition-colors ${sortBy === key ? "bg-muted text-foreground font-medium" : "text-muted-foreground hover:text-foreground"}`}
                        onClick={() => {
                          if (sortBy === key) setSortAsc((v) => !v);
                          else {
                            setSortBy(key);
                            setSortAsc(false);
                          }
                        }}
                      >
                        {label}
                        {sortBy === key && <span className="ml-0.5">{sortAsc ? "↑" : "↓"}</span>}
                      </button>
                    ))}
                  </div>
                  <div className="text-xs text-muted-foreground">
                    {searchQuery ? `找到 ${filteredFiles.length} 个文件 · 共 ${viewFiles.length} 个` : `共 ${viewFiles.length} 个文件${!currentFolder && folders.length > 0 ? ` · ${folders.length} 个文件夹` : ""}`}
                  </div>
                </div>
              </div>

              {/* 批量操作栏(两视图共用;网格视图此前缺失) */}
              <BatchActionBar
                selection={selection}
                onDownloadSelected={handleDownloadSelected}
                onMoveSelected={handleMoveSelected}
                onDeleteSelected={handleDeleteSelected}
                onAskAssistant={handleAskAssistant}
              />

              {searchQuery && filteredFiles.length === 0 ? (
                !loading && !error && <EmptyState icon={Search} title="未找到匹配的文件" description="试试其他关键词，或清除搜索查看当前目录的全部文件。"
                  action={<Button variant="outline" onClick={() => setSearchQuery("")}>清除搜索</Button>} />
              ) : effectiveView === "list" ? (
                <FileTable
                  files={filteredFiles}
                  selection={selection}
                  onDeleteSelected={handleDeleteSelected}
                  onDownloadSelected={handleDownloadSelected}
                  onMoveSelected={handleMoveSelected}
                  onOpen={(f) => { void viewer.open(f, filteredFiles); }}
                  onIndex={handleIndexFile}
                  onDownload={(f) => handleDownloadOne(f)}
                  onRename={handleRenameFile}
                  onMove={handleMoveOne}
                  onDelete={handleDeleteOne}
                  folderRows={searchQuery ? [] : folderRowsData}
                />
              ) : (
                <FileGrid
                  files={filteredFiles}
                  selection={selection}
                  onOpen={(f) => { void viewer.open(f, filteredFiles); }}
                  onDownload={(f) => handleDownloadOne(f)}
                  onRename={handleRenameFile}
                  onMove={handleMoveOne}
                  onDelete={handleDeleteOne}
                  folderRows={searchQuery ? [] : folderRowsData}
                />
              )}
  </>);
}
