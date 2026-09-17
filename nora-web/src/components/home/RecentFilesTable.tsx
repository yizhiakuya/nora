'use client';

import { useNavigate } from "react-router-dom";
import { Clock, FileText, FileSpreadsheet, FileImage, BookOpen, CloudOff } from "lucide-react";
import { useFileViewer } from "@/hooks/useFileViewer";
import { useRecentFiles } from "@/hooks/useRecentFiles";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { useBackendOnline } from "@/hooks/useBackendHealth";
import { FileViewerModal } from "@/components/files/viewer/FileViewerModal";
import { ResponsiveList } from "@/components/shared/ResponsiveList";
import { FileItem } from "@/types";

function iconFor(name: string) {
  const ext = name.split(".").pop()?.toLowerCase() ?? "";
  if (["xlsx", "csv"].includes(ext)) return { Icon: FileSpreadsheet, color: "text-green-600 dark:text-green-400", type: "Excel 表格" };
  if (["png", "jpg", "jpeg", "gif", "webp"].includes(ext)) return { Icon: FileImage, color: "text-purple-500 dark:text-purple-400", type: "图像" };
  return { Icon: FileText, color: "text-red-500 dark:text-red-400", type: "PDF 文档" };
}

export function RecentFilesTable() {
  const navigate = useNavigate();
  const viewer = useFileViewer();
  const recent = useRecentFiles((s) => s.recent);
  const docs = useKnowledgeDocs((s) => s.docs);
  const online = useBackendOnline();
  // 离线:如实显示本机记录为待同步,而不是把它们装作最新数据
  const stale = online === false;

  const openRecent = (name: string, type: string) => {
    // 列表内打开:支持弹窗内 ←/→ 切换(最近使用之间的连续浏览)
    const list: FileItem[] = recent.map((r) => {
      const { Icon, color } = iconFor(r.name);
      return {
        id: r.name.length * 7 + r.name.charCodeAt(0),
        name: r.name,
        type: r.type,
        size: "—",
        date: r.time,
        icon: Icon,
        color,
        indexed: false,
      };
    });
    const target = list.find((f) => f.name === name) ?? list[0];
    if (target) void viewer.open(target, list);
  };

  return (
    <div className="w-full lg:w-[65%] flex flex-col space-y-4">
      <div className="flex items-center justify-between">
        <h2 className="text-sm font-bold text-foreground flex items-center gap-2">
          <Clock className="w-4 h-4 text-blue-500 dark:text-blue-400" /> 最近使用
          {stale && (
            <span className="inline-flex items-center gap-1 text-[10px] font-normal text-muted-foreground border border-border rounded px-1.5 py-0.5">
              <CloudOff className="w-3 h-3" /> 同步中断
            </span>
          )}
        </h2>
        <span className="text-xs text-blue-600 dark:text-blue-400 cursor-pointer hover:underline" onClick={() => navigate("/files")}>查看全部</span>
      </div>

      <ResponsiveList
        rows={recent}
        rowKey={({ name }) => name}
        mobileTitle={({ name }) => name}
        mobileSubtitle={({ type, time }) => `${type} · ${time}`}
        onRowClick={({ name, type }) => openRecent(name, type)}
        mobileActions={({ name }) => (
          <span className="text-[10px] text-muted-foreground">
            {docs.some((d) => d.name === name) ? "已索引" : "未索引"}
          </span>
        )}
        columns={[
          {
            header: "文件名",
            cell: ({ name }) => {
              const { Icon, color } = iconFor(name);
              return (
                <div className="flex items-center gap-3 min-w-0">
                  <Icon className={`${color} w-5 h-5 flex-shrink-0`} />
                  <span className="font-medium text-foreground truncate" title={name}>{name}</span>
                </div>
              );
            },
          },
          {
            header: "索引状态",
            cell: ({ name }) => {
              const indexed = docs.some((d) => d.name === name);
              return (
                <span
                  onClick={(e) => { e.stopPropagation(); navigate("/knowledge"); }}
                  className={`inline-flex items-center gap-1.5 px-2 py-0.5 rounded text-[10px] border cursor-pointer hover:opacity-80 transition-colors whitespace-nowrap ${indexed ? "bg-green-50 dark:bg-green-950/40 text-green-700 dark:text-green-400 border-green-100 dark:border-green-900" : "bg-gray-50 dark:bg-gray-800 text-muted-foreground border-border"}`}
                >
                  <BookOpen className="w-3 h-3" /> {indexed ? "已索引" : "未索引"}
                </span>
              );
            },
          },
          { header: "打开时间", cell: ({ time }) => <span className="text-[11px] text-muted-foreground whitespace-nowrap">{time}</span> },
        ]}
        empty={
          <div className="bg-card border border-border rounded-xl shadow-sm p-6 text-center text-xs text-muted-foreground">
            {stale ? "暂时拿不到最近使用记录,连接恢复后自动显示" : "还没有打开过文件,去文件中心看看吧"}
          </div>
        }
      />

      <FileViewerModal
        file={viewer.activeFile}
        preview={viewer.preview}
        status={viewer.status}
        onClose={viewer.close}
        onNavigate={viewer.navigate}
        hasPrev={viewer.hasPrev}
        hasNext={viewer.hasNext}
        position={viewer.position}
      />
    </div>
  );
}
