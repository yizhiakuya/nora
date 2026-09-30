import { useMemo, useState } from "react";
import { Check, Copy, WrapText } from "lucide-react";
import { FilePreview } from "@/types";

/**
 * 文本预览(2026-09-17 增强):行号 + 复制 + 自动换行开关。
 *
 * 行号让定位(报错行/引用行)有参照;复制整段是最常用的动作(此前只能
 * 手动框选);换行开关兼顾代码(不折行、横向滚动)与日志(折行)两类内容。
 */
export function TextPreview({ preview }: { preview: FilePreview }) {
  const [wrap, setWrap] = useState(true);
  const [copied, setCopied] = useState(false);
  const text = preview.text ?? "";
  const lines = useMemo(() => text.split("\n"), [text]);
  const visible = lines.slice(0, 2000);

  const copyAll = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      // 剪贴板权限被拒(非 https 等):静默
    }
  };

  return (
    <div className="bg-card text-foreground h-full flex flex-col overflow-hidden">
      {/* 工具条:行数 / 换行开关 / 复制 */}
      <div className="shrink-0 flex flex-wrap items-center gap-2 px-3 py-2 border-b border-border text-[10px] text-muted-foreground">
        <span className="tabular-nums">{lines.length} 行 · {text.length} 字符</span>
        <div className="ml-auto flex items-center gap-1">
          <button
            type="button"
            title={wrap ? "关闭自动换行（横向滚动）" : "开启自动换行"}
            onClick={() => setWrap((v) => !v)}
            className={`p-1 rounded hover:bg-muted cursor-pointer ${wrap ? "text-foreground" : "text-muted-foreground"}`}
          >
            <WrapText className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            title="复制全文"
            onClick={() => void copyAll()}
            className="p-1 rounded hover:bg-muted cursor-pointer text-muted-foreground hover:text-foreground"
          >
            {copied ? <Check className="w-3.5 h-3.5 text-green-400" /> : <Copy className="w-3.5 h-3.5" />}
          </button>
        </div>
      </div>
      <div className="p-4 flex-1 overflow-auto custom-scroll font-mono text-xs leading-relaxed">
        {visible.map((line, index) => <div key={index} className={`flex ${wrap ? "" : "min-w-fit"}`}>
          <span className="w-10 text-right pr-3 mr-3 select-none text-muted-foreground/60 tabular-nums shrink-0 border-r border-border">{index + 1}</span>
          <pre className={`flex-1 min-w-0 ${wrap ? "whitespace-pre-wrap break-words" : "whitespace-pre"}`}>{line || "\n"}</pre>
        </div>)}
        {visible.length < lines.length && <p className="mt-3 text-muted-foreground">源码显示前 2000 行；复制或下载可查看完整内容。</p>}
      </div>
    </div>
  );
}
