import { FilePreview } from "@/types";

export function WordPreview({ preview }: { preview: FilePreview }) {
  return (
    <div className="bg-card border border-border rounded-lg shadow-sm p-8 min-h-[380px]">
      <div className="prose prose-sm prose-gray max-w-none">
        {preview.text?.split("\n").map((line, i) =>
          line.startsWith("## ") ? (
            <h3 key={i} className="text-sm font-bold text-foreground mt-5 mb-2">{line.replace("## ", "")}</h3>
          ) : line.startsWith("# ") ? (
            <h2 key={i} className="text-lg font-bold text-foreground mb-3">{line.replace("# ", "")}</h2>
          ) : line === "" ? (
            <div key={i} className="h-2"></div>
          ) : (
            <p key={i} className="text-sm text-muted-foreground leading-relaxed mb-1.5">{line}</p>
          )
        )}
      </div>
    </div>
  );
}
