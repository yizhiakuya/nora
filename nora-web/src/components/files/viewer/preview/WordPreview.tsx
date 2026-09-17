import { useMemo, useState } from "react";
import { Check, Copy } from "lucide-react";
import { FilePreview } from "@/types";

/**
 * Word 预览(2026-09-17 打磨):文档页样式 + 标题/列表/引用结构化渲染。
 *
 * Tika 提取的是纯文本(拿不到真实版式),这里把常见的文档记号
 * (#/## 标题、- 列表、> 引用、**加粗**)还原成排版,让内容更像
 * 文档而不是一堆行;白纸页容器与真实 Word 视觉一致。
 */
export function WordPreview({ preview }: { preview: FilePreview }) {
  const [copied, setCopied] = useState(false);
  const text = preview.text ?? "";
  const blocks = useMemo(() => parseBlocks(text), [text]);

  const copyAll = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch { /* 剪贴板不可用时静默 */ }
  };

  return (
    <div className="bg-muted/40 dark:bg-gray-900/60 rounded-lg border border-border p-4 sm:p-6">
      {/* 工具条 */}
      <div className="flex items-center justify-end mb-3 text-[10px] text-muted-foreground">
        <button
          type="button"
          onClick={() => void copyAll()}
          className="inline-flex items-center gap-1 px-2 py-1 rounded hover:bg-muted transition-colors cursor-pointer"
          title="复制全文"
        >
          {copied ? <Check className="w-3 h-3 text-green-500" /> : <Copy className="w-3 h-3" />}
          {copied ? "已复制" : "复制全文"}
        </button>
      </div>
      {/* 白纸页 */}
      <div className="bg-white dark:bg-gray-950 rounded shadow-sm border border-border/60 mx-auto max-w-[640px] px-8 py-10 sm:px-12 sm:py-14 max-h-[54vh] overflow-y-auto custom-scroll">
        <div className="text-[13px] text-gray-700 dark:text-gray-300 leading-[1.9]">
          {blocks.map((b, i) => {
            switch (b.type) {
              case "h1":
                return <h1 key={i} className="text-xl font-bold text-gray-900 dark:text-gray-100 mb-4 mt-6 first:mt-0">{b.text}</h1>;
              case "h2":
                return <h2 key={i} className="text-base font-bold text-gray-900 dark:text-gray-100 mb-3 mt-5 first:mt-0">{b.text}</h2>;
              case "h3":
                return <h3 key={i} className="text-sm font-bold text-gray-800 dark:text-gray-200 mb-2 mt-4 first:mt-0">{b.text}</h3>;
              case "li":
                return (
                  <div key={i} className="flex gap-2 mb-1">
                    <span className="text-gray-400 shrink-0">•</span>
                    <span>{b.text}</span>
                  </div>
                );
              case "quote":
                return (
                  <blockquote key={i} className="border-l-2 border-gray-300 dark:border-gray-700 pl-3 my-2 text-gray-500 dark:text-gray-400 italic">
                    {b.text}
                  </blockquote>
                );
              case "empty":
                return <div key={i} className="h-3" />;
              default:
                return <p key={i} className="mb-2">{renderInline(b.text)}</p>;
            }
          })}
        </div>
      </div>
    </div>
  );
}

interface Block {
  type: "h1" | "h2" | "h3" | "li" | "quote" | "p" | "empty";
  text: string;
}

/** 纯文本 → 结构化块(标题/列表/引用/段落)。 */
function parseBlocks(text: string): Block[] {
  return text.split("\n").map((line): Block => {
    const t = line.trimEnd();
    if (t.trim() === "") return { type: "empty", text: "" };
    if (t.startsWith("### ")) return { type: "h3", text: t.slice(4) };
    if (t.startsWith("## ")) return { type: "h2", text: t.slice(3) };
    if (t.startsWith("# ")) return { type: "h1", text: t.slice(2) };
    if (/^[-*•]\s+/.test(t)) return { type: "li", text: t.replace(/^[-*•]\s+/, "") };
    if (/^\d+[.、)]\s+/.test(t)) return { type: "li", text: t };
    if (t.startsWith("> ")) return { type: "quote", text: t.slice(2) };
    return { type: "p", text: t };
  });
}

/** 行内 **加粗** 渲染。 */
function renderInline(text: string): React.ReactNode {
  const parts = text.split(/(\*\*[^*]+\*\*)/g);
  return parts.map((p, i) =>
    p.startsWith("**") && p.endsWith("**")
      ? <strong key={i} className="font-semibold text-gray-900 dark:text-gray-100">{p.slice(2, -2)}</strong>
      : <span key={i}>{p}</span>
  );
}
