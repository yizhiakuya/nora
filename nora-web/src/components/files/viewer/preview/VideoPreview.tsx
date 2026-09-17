import { FilePreview } from "@/types";

/**
 * 视频预览(2026-09-17):**无边界沉浸展示**——视频按原始比例填满可用
 * 空间(高度撑满、宽度自适应),没有方框/边距限制。
 * raw 端点流式播放(Range 支持 seek)。
 */
export function VideoPreview({ preview }: { preview: FilePreview }) {
  return (
    <div className="w-full h-full flex items-center justify-center">
      <video
        src={preview.mediaUrl}
        controls
        playsInline
        autoPlay
        muted
        preload="metadata"
        className="max-w-full max-h-full rounded-md shadow-2xl bg-black animate-in fade-in"
      >
        您的浏览器不支持视频播放。
      </video>
    </div>
  );
}

/** 音频预览:居中宽条(音频无可视内容,给一个舒展的播放条)。 */
export function AudioPreview({ preview }: { preview: FilePreview }) {
  return (
    <div className="w-full h-full flex flex-col items-center justify-center gap-4">
      <audio src={preview.mediaUrl} controls autoPlay preload="metadata" className="w-full max-w-lg">
        您的浏览器不支持音频播放。
      </audio>
    </div>
  );
}
