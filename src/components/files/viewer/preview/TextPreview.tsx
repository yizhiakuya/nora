import { FilePreview } from "@/types";

export function TextPreview({ preview }: { preview: FilePreview }) {
  return (
    <div className="bg-[#1e1e1e] rounded-lg border border-gray-800 p-5 min-h-[380px] overflow-x-auto">
      <pre className="text-gray-300 font-mono text-xs leading-relaxed whitespace-pre-wrap">{preview.text}</pre>
    </div>
  );
}
