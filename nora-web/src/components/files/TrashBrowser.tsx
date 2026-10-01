import { useCallback, useEffect, useState } from "react";
import { Trash2, RotateCcw, FileText } from "lucide-react";
import { Button } from "@/components/ui/button";
import { filesApi, type TrashedFile, toFileItem, humanSize } from "@/lib/services/filesApi";
import { ResponsiveList } from "@/components/shared/ResponsiveList";
import { toast } from "sonner";
import { useFiles } from "@/hooks/useFiles";
import { useFileViewer } from "@/hooks/useFileViewer";

interface TrashBrowserProps {
  /** 退出回收站,回到文件中心根视图 */
  onExit: () => void;
}

/**
 * 回收站——删除的文件在这里可见可恢复。
 *
 * 删除是软删除(数据不真丢):文件从列表消失但仍在磁盘与数据库中,
 * 这里提供「恢复」(回到根目录)与「彻底删除」(磁盘文件一并清除,不可恢复)。
 */
export function TrashBrowser({ onExit }: TrashBrowserProps) {
  const [items, setItems] = useState<TrashedFile[]>([]);
  const [loading, setLoading] = useState(true);
  // 恢复后必须同步文件中心 store:删除时文件页已把它从 store 剔除,
  // 只刷回收站列表的话,回到文件中心看不到恢复的文件(像恢复失败)
  const syncFromBackend = useFiles((s) => s.syncFromBackend);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setItems(await filesApi.listTrash());
    } catch {
      setItems([]);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const handleRestore = async (ids: number[]) => {
    try {
      const n = await filesApi.restoreTrash(ids);
      toast.success(`已恢复 ${n} 个文件（回到根目录）`);
      void refresh();
      // 同步文件中心(恢复的文件立刻出现在列表里)
      void syncFromBackend();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "恢复失败");
    }
  };

  const handlePurge = async (ids: number[]) => {
    const label = ids.length === 1 ? "此文件" : `${ids.length} 个文件`;
    if (!window.confirm(`彻底删除${label}？\n磁盘文件将一并清除，不可恢复。`)) return;
    try {
      const n = await filesApi.purgeTrash(ids);
      toast.success(`已彻底删除 ${n} 个文件`);
      // 彻底删除后查看器标签即时失效(软删仍在回收站时可恢复,不标记)
      useFileViewer.getState().markMissing(ids.map(id => `file:${id}`));
      void refresh();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "删除失败");
    }
  };

  /** 时间显示:相对时间(刚刚/N 分钟前/N 天前)。 */
  const relativeTime = (iso: string | null): string => {
    if (!iso) return "—";
    const t = new Date(iso).getTime();
    if (Number.isNaN(t)) return "—";
    const diff = Date.now() - t;
    if (diff < 60_000) return "刚刚";
    if (diff < 3600_000) return `${Math.floor(diff / 60_000)} 分钟前`;
    if (diff < 86400_000) return `${Math.floor(diff / 3600_000)} 小时前`;
    if (diff < 30 * 86400_000) return `${Math.floor(diff / 86400_000)} 天前`;
    return iso.slice(0, 10);
  };

  return (
    <>
      {/* 面包屑 */}
      <div className="flex items-center gap-1.5 mb-4 text-xs animate-in fade-in flex-wrap">
        <button type="button" className="text-muted-foreground hover:text-foreground transition-colors" onClick={onExit}>
          资料
        </button>
        <span className="text-muted-foreground/50">/</span>
        <span className="text-foreground font-medium">回收站</span>
      </div>

      <>

          <div className="flex items-center justify-between mb-4 gap-3 flex-wrap">
            <div className="text-xs text-muted-foreground">
              {loading ? "加载中…" : `共 ${items.length} 个已删除文件`}
              <span className="ml-2 text-muted-foreground/60">删除的文件保留在磁盘上，可恢复或彻底清除</span>
            </div>
            {items.length > 0 && (
              <div className="flex items-center gap-2">
                <Button variant="outline" size="sm" className="h-7 text-xs" onClick={() => void handleRestore(items.map((t) => t.item.id))}>
                  <RotateCcw className="w-3.5 h-3.5 mr-1" /> 全部恢复
                </Button>
                <Button variant="outline" size="sm" className="h-7 text-xs text-red-600 dark:text-red-400 hover:text-red-700" onClick={() => void handlePurge(items.map((t) => t.item.id))}>
                  <Trash2 className="w-3.5 h-3.5 mr-1" /> 清空回收站
                </Button>
              </div>
            )}
          </div>

          <ResponsiveList
            className="animate-in fade-in"
            rows={loading ? [] : items}
            rowKey={({ item }) => item.id}
            mobileTitle={({ item }) => toFileItem(item).name}
            mobileSubtitle={({ item, deletedAt }) => `${toFileItem(item).size} · 删除于 ${relativeTime(deletedAt)}`}
            mobileActions={({ item }) => (
              <>
                <Button
                  variant="ghost" size="sm"
                  className="h-7 text-xs text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950/40"
                  onClick={() => void handleRestore([item.id])}
                >
                  <RotateCcw className="w-3.5 h-3.5 mr-1" /> 恢复
                </Button>
                <Button
                  variant="ghost" size="sm"
                  className="h-7 text-xs text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/40"
                  onClick={() => void handlePurge([item.id])}
                >
                  <Trash2 className="w-3.5 h-3.5 mr-1" /> 彻底删除
                </Button>
              </>
            )}
            columns={[
              {
                header: "文件名",
                // 移动端标题已显示文件名,卡片里不重复
                cell: ({ item }) => {
                  const f = toFileItem(item);
                  const Icon = f.icon ?? FileText;
                  return (
                    <div className="flex items-center gap-3 min-w-0">
                      <Icon className={`${f.color} w-5 h-5 shrink-0`} />
                      <span className="text-foreground truncate" title={f.name}>{f.name}</span>
                    </div>
                  );
                },
              },
              { header: "类型", cell: ({ item }) => <span className="text-muted-foreground text-xs whitespace-nowrap">{toFileItem(item).type}</span> },
              { header: "大小", cell: ({ item }) => <span className="text-muted-foreground text-xs whitespace-nowrap tabular-nums">{humanSize(item.sizeBytes)}</span> },
              { header: "删除时间", cell: ({ deletedAt }) => <span className="text-muted-foreground text-xs whitespace-nowrap">{relativeTime(deletedAt)}</span> },
              {
                header: "操作",
                cell: ({ item }) => (
                  <div className="flex justify-end gap-1">
                    <Button
                      variant="ghost" size="sm"
                      className="h-7 text-xs text-blue-600 dark:text-blue-400 hover:bg-blue-50 dark:hover:bg-blue-950/40"
                      onClick={() => void handleRestore([item.id])}
                    >
                      <RotateCcw className="w-3.5 h-3.5 mr-1" /> 恢复
                    </Button>
                    <Button
                      variant="ghost" size="sm"
                      className="h-7 text-xs text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-950/40"
                      onClick={() => void handlePurge([item.id])}
                    >
                      <Trash2 className="w-3.5 h-3.5 mr-1" /> 彻底删除
                    </Button>
                  </div>
                ),
              },
            ]}
            empty={
              <div className="bg-card border border-border rounded-xl py-16 text-center text-xs text-muted-foreground">
                {loading ? "加载中…" : "（回收站为空）删除的文件会出现在这里"}
              </div>
            }
          />
      </>

    </>
  );
}
