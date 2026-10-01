import { useCallback, useEffect, useState } from "react";
import { HardDrive, Play, Trash2, BookmarkPlus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { useFileViewer } from "@/hooks/useFileViewer";
import {
  mediaCacheApi,
  cachedItemLabel,
  isVideoItem,
  thumbUrlFor,
  type CachedMediaItem,
  type CachedMediaList,
} from "@/lib/services/mediaCacheApi";
import { toast } from "sonner";
import { mdHm } from "@/lib/format";

interface MediaCacheBrowserProps {
  /** 退出媒体缓存,回到文件中心根视图 */
  onExit: () => void;
}

/** 人类可读大小。 */
function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

/**
 * 媒体缓存文件夹——作为「文件」页里的一个普通文件夹出现。
 *
 * 相册等远程媒体的本地副本(缩略图/播放流/原片):跨会话秒开、手机离线也能看。
 * 这里让用户看得见缓存了什么、各占多大,并能删除单个条目或清空。
 */
export function MediaCacheBrowser({ onExit }: MediaCacheBrowserProps) {
  const [data, setData] = useState<CachedMediaList | null>(null);
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(async () => {
    setLoading(true);
    try {
      setData(await mediaCacheApi.list());
    } catch {
      setData(null);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const handleDelete = async (item: CachedMediaItem) => {
    if (!window.confirm(`确认删除缓存「${cachedItemLabel(item)}」？\n下次查看会重新从手机拉取。`)) return;
    try {
      await mediaCacheApi.remove(item.key);
      toast.success("已删除");
      // 缓存删除后查看器标签即时失效(缓存可再生,但当前打开的内容已不可读)
      useFileViewer.getState().markMissing([`media:${item.key}`]);
      void refresh();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "删除失败");
    }
  };

  /**
   * 保存到文件中心:缓存是自动派生层,这里一键转为正式知识资产——
   * 保存后可在文件中心索引入知识库、被对话 @ 引用、被 AI manage_file 读取。
   */
  const handleSaveToFiles = async (item: CachedMediaItem) => {
    try {
      const res = await mediaCacheApi.saveToFiles(item.key);
      toast.success(`已保存到资料:「${res.name}」`, {
        description: "可在「资料」根目录查看，并加入知识库",
      });
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "保存失败");
    }
  };

  const handleClear = async () => {
    if (!window.confirm(`确认清空全部媒体缓存？\n共 ${data?.count ?? 0} 项 ${humanSize(data?.totalBytes ?? 0)}，清空后再次查看会重新从手机拉取。`)) return;
    try {
      const n = await mediaCacheApi.clear();
      toast.success(`已清空 ${n} 项`);
      void refresh();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "清空失败");
    }
  };

  return (
    <>
      {/* 面包屑 */}
      <div className="flex items-center gap-1.5 mb-4 text-xs animate-in fade-in flex-wrap">
        <button type="button" className="text-muted-foreground hover:text-foreground transition-colors" onClick={onExit}>
          资料
        </button>
        <span className="text-muted-foreground/50">/</span>
        <span className="text-foreground font-medium">媒体缓存</span>
      </div>

      <>
          {/* 概览条 */}
          <div className="flex items-center justify-between mb-4 gap-3 flex-wrap">
            <div className="text-xs text-muted-foreground">
              {loading ? "加载中…" : `共 ${data?.count ?? 0} 项 · ${humanSize(data?.totalBytes ?? 0)}`}
              <span className="ml-2 text-muted-foreground/60">相册媒体的本地副本:查看秒开、手机离线可看</span>
            </div>
            {(data?.count ?? 0) > 0 && (
              <Button variant="outline" size="sm" className="h-7 text-xs text-red-600 dark:text-red-400 hover:text-red-700" onClick={handleClear}>
                <Trash2 className="w-3.5 h-3.5 mr-1" /> 清空全部
              </Button>
            )}
          </div>

          {/* 网格:缩略图铺底,视频/图片可点开预览 */}
          {loading ? (
            <div className="bg-card rounded-xl border border-border shadow-sm py-16 text-center text-xs text-muted-foreground">
              加载中…
            </div>
          ) : !data || data.items.length === 0 ? (
            <div className="bg-card rounded-xl border border-border shadow-sm py-16 text-center text-xs text-muted-foreground">
              （暂无缓存）在对话中查看相册图片/视频后,媒体会自动缓存到这里
            </div>
          ) : (
            <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-6 gap-2">
              {data.items.map((item) => (
                <div
                  key={item.key}
                  className="group relative rounded-lg overflow-hidden border border-border bg-muted/40 aspect-square"
                >
                  <button
                    type="button"
                    className="absolute inset-0 cursor-zoom-in"
                    title={`${cachedItemLabel(item)}\n${humanSize(item.size)} · ${mdHm(item.savedAt)}\n${item.url}`}
                    onClick={() => void useFileViewer.getState().openTargets([item.url], undefined, { collection: data.items.map(entry => entry.url) })}
                  >
                    {/* 统一用缩略图铺底:视频条目(播放流/原片)也改写为手机 /thumb
                        端点(封面帧)——不再只有播放角标,一眼能认出是哪张 */}
                    <img
                      src={mediaCacheApi.previewUrl(thumbUrlFor(item))}
                      alt={cachedItemLabel(item)}
                      loading="lazy"
                      className="w-full h-full object-cover transition-transform duration-200 group-hover:scale-[1.03]"
                    />
                    {isVideoItem(item) && (
                      // 视频角标:叠在缩略图右上角
                      <span className="absolute top-1 left-1 w-5 h-5 rounded-full bg-black/55 flex items-center justify-center pointer-events-none">
                        <Play className="w-3 h-3 text-white fill-white" />
                      </span>
                    )}
                  </button>
                  {/* 底部信息条:名称 + 大小/档位 */}
                  <span className="absolute inset-x-0 bottom-0 px-1.5 py-1 text-[10px] leading-tight text-white bg-gradient-to-t from-black/75 to-transparent pointer-events-none">
                    <span className="block truncate">{cachedItemLabel(item)}</span>
                    <span className="block truncate text-white/70">
                      {humanSize(item.size)}
                      {item.quality === "low" ? " · 省流量档" : ""}
                    </span>
                  </span>
                  {/* 操作按钮:悬停显示(保存到文件中心 / 删除) */}
                  <div className="absolute top-1 right-1 flex gap-1 opacity-0 group-hover:opacity-100 transition-opacity">
                    <button
                      type="button"
                      title="保存到资料（转为正式文件,可索引/引用）"
                      onClick={(e) => {
                        e.stopPropagation();
                        void handleSaveToFiles(item);
                      }}
                      className="p-1 rounded bg-black/50 text-white/80 hover:text-white hover:bg-blue-600/80 cursor-pointer"
                    >
                      <BookmarkPlus className="w-3 h-3" />
                    </button>
                    <button
                      type="button"
                      title="删除此缓存"
                      onClick={(e) => {
                        e.stopPropagation();
                        void handleDelete(item);
                      }}
                      className="p-1 rounded bg-black/50 text-white/80 hover:text-white hover:bg-red-600/80 cursor-pointer"
                    >
                      <Trash2 className="w-3 h-3" />
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}

          {/* 真实磁盘路径(缓存就是文件系统上的一个真实目录) */}
          {data?.dir && (
            <div className="mt-3 text-[10px] text-muted-foreground/70 font-mono truncate flex items-center gap-1" title={data.dir}>
              <HardDrive className="w-3 h-3 shrink-0" />
              真实目录:{data.dir}
            </div>
          )}
      </>

    </>
  );
}
