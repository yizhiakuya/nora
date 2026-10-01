import { Search, Trash2 } from "lucide-react";
import { useDocumentLibrary } from "@/hooks/useDocumentLibrary";
import { Checkbox } from "@/components/ui/checkbox";
import { Button } from "@/components/ui/button";
import { KnowledgeBases } from "./KnowledgeBases";
import { DocumentGroup } from "./DocumentGroup";
import { DocumentDialogs } from "./DocumentDialogs";
import { DocumentDetailModal } from "./DocumentDetailModal";

export function DocumentLibrary() {
  const library = useDocumentLibrary();
  const { allDocs, allSelected, toggleAll, selectedLive, setConfirmDelete, grouped } = library;
  return (
    <div className="space-y-6">
      <KnowledgeBases library={library} />

      {allDocs.length > 0 && (
        <div className="flex items-center gap-3 min-h-[28px]">
          <label className="flex items-center gap-2 text-xs text-muted-foreground cursor-pointer">
            <Checkbox checked={allSelected} onCheckedChange={toggleAll} />
            全选
          </label>
          {selectedLive.size > 0 && (
            <>
              <span className="text-xs text-muted-foreground">已选 {selectedLive.size} 个</span>
              <Button
                size="sm"
                variant="outline"
                className="h-7 text-xs text-red-600 dark:text-red-400"
                onClick={() => setConfirmDelete(Array.from(selectedLive))}
              >
                <Trash2 className="w-3 h-3 mr-1" />
                批量删除
              </Button>
            </>
          )}
        </div>
      )}

      {Array.from(grouped, ([src, docs]) => <DocumentGroup key={src} src={src} docs={docs} library={library} />)}

      {allDocs.length === 0 && (
        <div className="py-16 flex flex-col items-center text-muted-foreground">
          <Search className="w-10 h-10 mb-3 opacity-20" />
          <div className="text-sm">没有已摄入的文档</div>
        </div>
      )}

      <DocumentDialogs library={library} />
      <DocumentDetailModal library={library} />
    </div>
  );
}
