import { useState } from "react";
import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import { FilePreview } from "@/types";

export function PdfPreview({ preview }: { preview: FilePreview }) {
  const [page, setPage] = useState(1);
  const pages = preview.pages ?? 1;

  return (
    <div className="flex flex-col items-center">
      <div className="relative w-full max-w-[560px] bg-white border border-gray-200 rounded-lg shadow-sm p-8 min-h-[420px]">
        <div className="border-b-2 border-blue-100 pb-3 mb-6">
          <div className="h-4 w-1/3 bg-blue-100 rounded-full"></div>
        </div>
        <div className="space-y-3">
          {Array.from({ length: 9 }).map((_, i) => (
            <div key={i} className="h-2.5 bg-gray-100 rounded-full" style={{ width: `${88 - ((i * 13) % 36)}%` }}></div>
          ))}
        </div>
        <div className="mt-8 flex items-end justify-between">
          <div className="w-1/3 h-20 bg-blue-50 rounded-lg border border-blue-100"></div>
          <div className="w-1/2 space-y-2">
            <div className="h-2.5 w-full bg-gray-100 rounded-full"></div>
            <div className="h-2.5 w-4/5 bg-gray-100 rounded-full"></div>
            <div className="h-2.5 w-3/5 bg-gray-100 rounded-full"></div>
          </div>
        </div>
        <div className="absolute bottom-3 right-4 text-[9px] text-gray-300">第 {page} 页 · 共 {pages} 页</div>
      </div>
      <div className="flex items-center gap-3 mt-4">
        <Button variant="outline" size="sm" className="h-7 text-xs bg-white" disabled={page <= 1} onClick={() => setPage((p) => p - 1)}>
          <ChevronLeft className="w-3.5 h-3.5" /> 上一页
        </Button>
        <span className="text-xs text-gray-500">{page} / {pages}</span>
        <Button variant="outline" size="sm" className="h-7 text-xs bg-white" disabled={page >= pages} onClick={() => setPage((p) => p + 1)}>
          下一页 <ChevronRight className="w-3.5 h-3.5" />
        </Button>
      </div>
    </div>
  );
}
