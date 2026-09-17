import { useCallback, useEffect, useState } from "react";
import { HardDrive, Play, Trash2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Modal } from "@/components/ui/custom/Modal";
import {
  mediaCacheApi,
  cachedItemLabel,
  isVideoItem,
  type CachedMediaItem,
  type CachedMediaList,
} from "@/lib/services/mediaCacheApi";
import { toast } from "sonner";
import { USE_BACKEND } from "@/lib/api/client";

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

/** 时间戳 → "MM-DD HH:mm"。 */
function formatTime(ms: number): string {
  const d = new Date(ms);
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
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
  const [viewing, setViewing] = useState<CachedMediaItem | null>(null);

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
      void refresh();
    } catch (e) {
      toast.error(e instanceof Error ? e.message : "删除失败");
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
          文件中心
        </button>
        <span className="text-muted-foreground/50">/</span>
        <span className="text-foreground font-medium">媒体缓存</span>
      </div>

      {!USE_BACKEND ? (
        <div className="bg-card rounded-xl border border-border shadow-sm py-16 text-center text-xs text-muted-foreground">
          媒体缓存仅在后端模式可用
        </div>
      ) : (
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
                    title={`${cachedItemLabel(item)}\n${humanSize(item.size)} · ${formatTime(item.savedAt)}\n${item.url}`}
                    onClick={() => setViewing(item)}
                  >
                    {isVideoItem(item) ? (
                      // 视频条目:黑底 + 播放角标(不做静帧提取,轻量展示)
                      <span className="absolute inset-0 flex items-center justify-center bg-black/60">
                        <Play className="w-6 h-6 text-white/90 fill-white/90" />
                      </span>
                    ) : (
                      <img
                        src={mediaCacheApi.previewUrl(item.url)}
                        alt={cachedItemLabel(item)}
                        loading="lazy"
                        className="w-full h-full object-cover transition-transform duration-200 group-hover:scale-[1.03]"
                      />
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
                  {/* 删除按钮:悬停显示 */}
                  <button
                    type="button"
                    title="删除此缓存"
                    onClick={(e) => {
                      e.stopPropagation();
                      void handleDelete(item);
                    }}
                    className="absolute top-1 right-1 p-1 rounded bg-black/50 text-white/80 hover:text-white hover:bg-red-600/80 opacity-0 group-hover:opacity-100 transition-opacity cursor-pointer"
                  >
                    <Trash2 className="w-3 h-3" />
                  </button>
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
      )}

      {/* 预览灯箱:图片直接看,视频用原生播放器 */}
      <Modal
        isOpen={viewing != null}
        onClose={() => setViewing(null)}
        title={viewing ? cachedItemLabel(viewing) : ""}
        width="w-[94%] sm:w-[760px]"
      >
        {viewing && (
          <div className="flex items-center justify-center bg-black/80 rounded-lg p-2 min-h-[260px]">
            {isVideoItem(viewing) ? (
              <video
                ref={(el) => {
                  if (el) el.muted = true;
                }}
                src={mediaCacheApi.previewUrl(viewing.url)}
                controls
                autoPlay
                playsInline
                muted
                className="max-w-full max-h-[420px] rounded"
              />
            ) : (
              <img
                src={mediaCacheApi.previewUrl(viewing.url)}
                alt={cachedItemLabel(viewing)}
                className="max-w-full max-h-[420px] rounded"
              />
            )}
          </div>
        )}
      </Modal>
    </>
  );
}
