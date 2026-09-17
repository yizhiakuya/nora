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
    <div className="bg-[#1e1e1e] rounded-lg border border-gray-800 overflow-hidden">
      {/* 工具条:行数 / 换行开关 / 复制 */}
      <div className="flex items-center gap-2 px-3 py-1.5 border-b border-gray-800 text-[10px] text-gray-500">
        <span className="tabular-nums">{lines.length} 行 · {text.length} 字符</span>
        <div className="ml-auto flex items-center gap-1">
          <button
            type="button"
            title={wrap ? "关闭自动换行（横向滚动）" : "开启自动换行"}
            onClick={() => setWrap((v) => !v)}
            className={`p-1 rounded hover:bg-gray-800 transition-colors cursor-pointer ${wrap ? "text-gray-300" : "text-gray-600"}`}
          >
            <WrapText className="w-3.5 h-3.5" />
          </button>
          <button
            type="button"
            title="复制全文"
            onClick={() => void copyAll()}
            className="p-1 rounded hover:bg-gray-800 transition-colors cursor-pointer text-gray-400 hover:text-gray-200"
          >
            {copied ? <Check className="w-3.5 h-3.5 text-green-400" /> : <Copy className="w-3.5 h-3.5" />}
          </button>
        </div>
      </div>
      <div className="p-4 max-h-[56vh] overflow-auto custom-scroll">
        <div className="flex font-mono text-xs leading-relaxed min-w-fit">
          {/* 行号列 */}
          <div className="select-none text-right pr-3 text-gray-600 tabular-nums shrink-0 border-r border-gray-800 mr-3">
            {lines.map((_, i) => (
              <div key={i}>{i + 1}</div>
            ))}
          </div>
          <pre className={`text-gray-300 ${wrap ? "whitespace-pre-wrap break-words" : "whitespace-pre"} flex-1`}>
            {text}
          </pre>
        </div>
      </div>
    </div>
  );
}
