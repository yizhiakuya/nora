'use client';

import { Trash2, Pencil, RefreshCw, Loader2, Eye, EyeOff, FolderInput, MoreHorizontal } from "lucide-react";
import { KnowledgeDoc } from "@/types";
import { Checkbox } from "@/components/ui/checkbox";
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu";

import type { DocumentLibraryState } from "@/hooks/useDocumentLibrary";
import { DocIcon, QualityBar, StatusBadge } from "./DocumentBadges";

export function DocumentRow({ doc, library }: { doc: KnowledgeDoc; library: DocumentLibraryState }) {
  const { selectedLive, toggleOne, busyId, setRenaming, setRenameValue, detailLoading, setConfirmDelete, bases, setMoving, openDetail, handleReindex, handleToggleEnabled } = library;
  return (<>
                    <tr key={doc.id} className="border-b border-border last:border-0 hover:bg-muted/50 transition-colors text-sm">
                      <td className="p-3 pl-4">
                        <Checkbox
                          checked={selectedLive.has(doc.id)}
                          onCheckedChange={() => toggleOne(doc.id)}
                          aria-label={`选择 ${doc.name}`}
                        />
                      </td>
                      <td className="p-3">
                        <div className="flex items-center gap-2.5 min-w-0">
                          <DocIcon source={doc.source} />
                          <div className="min-w-0">
                            <div className="flex items-center gap-2">
                              <button
                                type="button"
                                onClick={() => openDetail(doc)}
                                className="font-medium text-foreground hover:underline cursor-pointer text-left truncate"
                                title="查看分块详情"
                              >
                                {doc.name}
                              </button>
                              {detailLoading === doc.id && (
                                <Loader2 className="w-3 h-3 animate-spin text-muted-foreground shrink-0" />
                              )}
                            </div>
                            {/* 来源文件:跳转文件中心(预览原文件)——知识库与文件系统一体。
                                名称下方小字(窄屏不挤状态列) */}
                            {doc.source === "file" && doc.sourceId != null && (
                              <a
                                href={`/files?open=${doc.sourceId}`}
                                className="text-[10px] text-blue-500 dark:text-blue-400 hover:underline"
                                title="在「资料」中查看原文件"
                                onClick={(e) => e.stopPropagation()}
                              >
                                原文件
                              </a>
                            )}
                            {/* 构建失败原因(阶段 A:失败可见可重试)——此前只有「失败」标签,原因不可见 */}
                            {doc.status === "failed" && doc.error && (
                              <div className="text-[10px] text-red-600 dark:text-red-400 mt-0.5 max-w-[360px] truncate" title={doc.error}>
                                {doc.error}
                              </div>
                            )}
                            {/* 解析告警(如超限截断;文档仍可检索) */}
                            {doc.status !== "failed" && doc.warning && (
                              <div className="text-[10px] text-amber-600 dark:text-amber-400 mt-0.5 max-w-[360px] truncate" title={doc.warning}>
                                ⚠ {doc.warning}
                              </div>
                            )}
                          </div>
                        </div>
                      </td>
                      <td className="p-3 text-muted-foreground text-xs tabular-nums hidden md:table-cell">{doc.chunks}</td>
                      <td className="p-3 text-muted-foreground text-xs hidden lg:table-cell">{doc.size}</td>
                      <td className="p-3 hidden lg:table-cell"><QualityBar score={doc.quality} /></td>
                      <td className="p-3"><StatusBadge status={doc.status} enabled={doc.enabled} /></td>
                      <td className="p-3 text-right text-muted-foreground text-xs whitespace-nowrap hidden md:table-cell">{doc.updatedAt}</td>
                      <td className="p-3 pr-4">
                        <div className="flex items-center justify-end gap-1">
                          {/* 停用/启用(阶段 B):停用=退出检索,数据保留——比删除轻的操作。
                              §7 评审:高频动作留明面,低频(移库/重建/重命名)收进「更多操作」 */}
                          <button
                            type="button"
                            onClick={() => void handleToggleEnabled(doc)}
                            disabled={busyId === doc.id}
                            className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground cursor-pointer disabled:opacity-50"
                            title={doc.enabled === false ? "重新启用（参与检索）" : "停用（退出检索，数据保留）"}
                          >
                            {doc.enabled === false ? <Eye className="w-3.5 h-3.5" /> : <EyeOff className="w-3.5 h-3.5" />}
                          </button>
                          <DropdownMenu>
                            <DropdownMenuTrigger asChild>
                              <button
                                type="button"
                                disabled={busyId === doc.id}
                                className="p-1.5 rounded hover:bg-muted text-muted-foreground hover:text-foreground cursor-pointer disabled:opacity-50"
                                title="更多操作"
                              >
                                <MoreHorizontal className="w-3.5 h-3.5" />
                              </button>
                            </DropdownMenuTrigger>
                            <DropdownMenuContent align="end" className="w-44">
                              {/* 移动到资料库(阶段 B 闭环;仅后端模式且已有多个库时显示) */}
                              {bases.length > 1 && (
                                <DropdownMenuItem onClick={() => setMoving(doc)}>
                                  <FolderInput className="w-3.5 h-3.5 mr-2" /> 移动到资料库…
                                </DropdownMenuItem>
                              )}
                              <DropdownMenuItem onClick={() => handleReindex(doc)}>
                                <RefreshCw className={`w-3.5 h-3.5 mr-2 ${busyId === doc.id ? "animate-spin" : ""}`} /> 重建向量
                              </DropdownMenuItem>
                              <DropdownMenuItem onClick={() => { setRenaming(doc); setRenameValue(doc.name); }}>
                                <Pencil className="w-3.5 h-3.5 mr-2" /> 重命名
                              </DropdownMenuItem>
                              <DropdownMenuSeparator />
                              <DropdownMenuItem className="text-red-600 dark:text-red-400" onClick={() => setConfirmDelete([doc.id])}>
                                <Trash2 className="w-3.5 h-3.5 mr-2" /> 删除
                              </DropdownMenuItem>
                            </DropdownMenuContent>
                          </DropdownMenu>
                        </div>
                      </td>
                    </tr>
  </>);
}
