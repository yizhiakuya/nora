import { useMemo } from "react";
import { Markdown } from "@/components/shared/Markdown";
import type { FilePreview, ViewerFile } from "@/types";
import { PdfPreview } from "./preview/PdfPreview";
import { WordPreview } from "./preview/WordPreview";
import { ExcelPreview } from "./preview/ExcelPreview";
import { ImagePreview } from "./preview/ImagePreview";
import { VideoPreview, AudioPreview } from "./preview/VideoPreview";
import { TextPreview } from "./preview/TextPreview";

function staticHtml(source: string): string {
  const template = document.createElement("template");
  template.innerHTML = source;
  template.content.querySelectorAll("base, meta[http-equiv='refresh' i], meta[http-equiv='content-security-policy' i]").forEach(node => node.remove());
  template.content.querySelectorAll("[href], [action], [formaction]").forEach(node => {
    node.removeAttribute("href"); node.removeAttribute("action"); node.removeAttribute("formaction");
  });
  return `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data: blob:; style-src 'unsafe-inline'; font-src data:; form-action 'none'; base-uri 'none'">${template.innerHTML}`;
}

export function ViewerContent({ file, preview, source }: { file: ViewerFile; preview: FilePreview; source: boolean }) {
  const html = useMemo(() => preview.kind === "html" ? staticHtml(preview.text ?? "") : "", [preview.kind, preview.text]);
  if (source) return <TextPreview preview={preview} />;
  switch (preview.kind) {
    case "markdown": return <div className="max-w-[760px] mx-auto px-5 sm:px-7 py-7"><Markdown className="prose prose-sm sm:prose-base dark:prose-invert" fileTarget={file.target}>{preview.text ?? ""}</Markdown></div>;
    case "html": return <iframe title={file.name} sandbox="" srcDoc={html} className="w-full h-full min-h-[480px] bg-white" />;
    case "json": {
      let formatted = preview.text ?? "";
      try { formatted = JSON.stringify(JSON.parse(formatted.replace(/^\uFEFF/, "")), null, 2); } catch { /* 无效 JSON 仍可查看原文。 */ }
      return <TextPreview preview={{ ...preview, text: formatted }} />;
    }
    case "csv": return (
      <div className="p-5">
        <div className="overflow-x-auto custom-scroll">
          <table className="w-full text-xs border-collapse text-left">
            <thead><tr>{preview.table?.columns.map((column, index) => <th key={index} className="bg-muted px-3 py-3 font-medium text-muted-foreground border-b border-border whitespace-nowrap">{column || `列 ${index + 1}`}</th>)}</tr></thead>
            <tbody>{preview.table?.rows.map((row, index) => <tr key={index}>{row.map((cell, cellIndex) => <td key={cellIndex} className="px-3 py-3 border-b border-border whitespace-pre-wrap min-w-[80px] max-w-[400px] break-words">{cell}</td>)}</tr>)}</tbody>
          </table>
        </div>
        <p className="mt-4 text-xs text-muted-foreground">已显示 {preview.table?.rows.length ?? 0} 行{preview.tableTruncated ? "，预览最多 200 行、100 列，下载查看完整数据" : ""}</p>
      </div>
    );
    case "pdf": return <PdfPreview preview={preview} />;
    case "word": return <div className="p-5"><p className="text-xs text-muted-foreground mb-3">提取内容预览 · 原始版式请下载查看</p><WordPreview preview={preview} /></div>;
    case "excel": return <div className="p-5"><p className="text-xs text-muted-foreground mb-3">提取表格预览 · 原始版式请下载查看</p><ExcelPreview preview={preview} /></div>;
    case "image": return <ImagePreview preview={preview} />;
    case "video": return <VideoPreview preview={preview} />;
    case "audio": return <AudioPreview preview={preview} />;
    case "text": return <TextPreview preview={preview} />;
    default: return <div className="p-10 text-center text-sm text-muted-foreground">此格式暂不支持预览，可下载原文件查看。</div>;
  }
}
