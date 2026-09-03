import { FilePreview } from "@/types";

export function WordPreview({ preview }: { preview: FilePreview }) {
  return (
    <div className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-lg shadow-sm p-8 min-h-[380px]">
      <div className="prose prose-sm prose-gray max-w-none">
        {preview.text?.split("\n").map((line, i) =>
          line.startsWith("## ") ? (
            <h3 key={i} className="text-sm font-bold text-gray-800 dark:text-gray-100 mt-5 mb-2">{line.replace("## ", "")}</h3>
          ) : line.startsWith("# ") ? (
            <h2 key={i} className="text-lg font-bold text-gray-900 dark:text-gray-50 mb-3">{line.replace("# ", "")}</h2>
          ) : line === "" ? (
            <div key={i} className="h-2"></div>
          ) : (
            <p key={i} className="text-sm text-gray-600 dark:text-gray-300 leading-relaxed mb-1.5">{line}</p>
          )
        )}
      </div>
    </div>
  );
}
