'use client';

import { LayoutGrid, List } from "lucide-react";
import { FileTable, BatchActionBar } from "@/components/files/FileTable";
import { FileGrid } from "@/components/files/FileGrid";

import type { FileLibraryState } from "@/hooks/useFileLibrary";

export function FileListing({ library }: { library: FileLibraryState }) {
  const { currentFolder, setCurrentFolder, viewMode, switchView, sortBy, setSortAsc, setSortBy, sortAsc, viewFiles, folders, selection, handleDownloadSelected, handleMoveSelected, handleDeleteSelected, handleAskAssistant, effectiveView, filteredFiles, viewer, handleIndexFile, handleDownloadOne, handleRenameFile, handleMoveOne, handleDeleteOne, folderRowsData } = library;
  return (<>
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
                <h2 className="text-sm font-bold text-foreground">{currentFolder ? currentFolder.name : "所有文件"}</h2>
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
                    共 {viewFiles.length} 个文件{!currentFolder && folders.length > 0 ? ` · ${folders.length} 个文件夹` : ""}
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

              {effectiveView === "list" ? (
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
                  folderRows={folderRowsData}
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
                  folderRows={folderRowsData}
                />
              )}
  </>);
}
