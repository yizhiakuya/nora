import { FilePreview } from "@/types";

/**
 * 视频预览(2026-09-17):<video> 直连 file-service raw 端点流式播放。
 * 依赖服务端 Range 请求支持(Spring 对 ResponseEntity<byte[]> 自动处理
 * 单区间 Range;不支持时退化为整体下载后播放,小文件无感知)。
 */
export function VideoPreview({ preview }: { preview: FilePreview }) {
  return (
    <div className="bg-[#f0f2f5] dark:bg-gray-900 rounded-lg border border-border p-4 flex items-center justify-center min-h-[380px]">
      <video
        src={preview.mediaUrl}
        controls
        playsInline
        preload="metadata"
        className="max-w-full max-h-[460px] rounded-lg shadow-md bg-black"
      >
        您的浏览器不支持视频播放。
      </video>
    </div>
  );
}

/** 音频预览:同上,<audio> 播放。 */
export function AudioPreview({ preview }: { preview: FilePreview }) {
  return (
    <div className="bg-[#f0f2f5] dark:bg-gray-900 rounded-lg border border-border p-6 flex flex-col items-center justify-center min-h-[200px] gap-4">
      <audio src={preview.mediaUrl} controls preload="metadata" className="w-full max-w-md">
        您的浏览器不支持音频播放。
      </audio>
    </div>
  );
}
