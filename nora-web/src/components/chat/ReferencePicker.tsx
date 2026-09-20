import { useEffect, useState } from "react";
import { FileText, Loader2, Search } from "lucide-react";
import { Modal } from "@/components/ui/custom/Modal";
import { Input } from "@/components/ui/input";
import { useFiles } from "@/hooks/useFiles";
import { useKnowledgeDocs } from "@/hooks/useKnowledgeDocs";
import { USE_BACKEND } from "@/lib/api/client";
import type { ChatRef } from "@/lib/chatRefs";

/**
 * 引用选择器(2026-09-17):对话框 📄(文件中心文件)与 @(知识库文档)共用。
 * - kind=file:列出文件中心的文件(USE_BACKEND 时打开即拉最新,失败回退本地缓存);
 * - kind=doc:列出知识库已索引文档;
 * - 点选即回调 ChatRef(带真实 id),由输入区序列化进消息。
 */
export function ReferencePicker({
  kind,
  isOpen,
  onClose,
  onPick,
}: {
  kind: "file" | "doc";
  isOpen: boolean;
  onClose: () => void;
  onPick: (ref: ChatRef) => void;
}) {
  const files = useFiles((s) => s.files);
  const docs = useKnowledgeDocs((s) => s.docs);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(false);

  // 打开时拉最新列表(后端模式):避免引用到已删除/过期的条目
  useEffect(() => {
    if (!isOpen) return;
    setQuery("");
    if (!USE_BACKEND) return;
    setLoading(true);
    const sync =
      kind === "file"
        ? useFiles.getState().syncFromBackend()
        : useKnowledgeDocs.getState().syncFromBackend();
    sync.catch(() => undefined).finally(() => setLoading(false));
  }, [isOpen, kind]);

  const title = kind === "file" ? "引用文件中心的文件" : "引用知识库文档";
  const emptyHint =
    kind === "file"
      ? "文件中心还没有文件。可以先点 📎 上传，或去「文件」页导入。"
      : "知识库还没有已索引的文档。先去「知识库」页导入并索引。";

  const q = query.trim().toLowerCase();
  const items: ChatRef[] =
    kind === "file"
      ? files
          .filter((f) => !q || f.name.toLowerCase().includes(q))
          .map((f) => ({ kind: "file" as const, id: f.id, name: f.name, size: f.size }))
      : docs
          .filter((d) => d.status === "indexed" && (!q || d.name.toLowerCase().includes(q)))
          .map((d) => ({ kind: "doc" as const, id: d.id, name: d.name }));

  return (
    <Modal isOpen={isOpen} onClose={onClose} title={title} width="w-[92%] sm:w-[520px]">
      <div className="space-y-3">
        <div className="relative">
          <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-muted-foreground" />
          <Input
            autoFocus
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder={kind === "file" ? "按文件名搜索…" : "按文档名搜索…"}
            className="h-8 pl-8 text-xs"
          />
        </div>

        {loading ? (
          <div className="py-10 flex items-center justify-center text-muted-foreground text-xs gap-2">
            <Loader2 className="w-3.5 h-3.5 animate-spin" /> 加载列表…
          </div>
        ) : items.length === 0 ? (
          <div className="py-10 text-center text-xs text-muted-foreground">{emptyHint}</div>
        ) : (
          <ul className="max-h-80 overflow-y-auto custom-scroll rounded-lg border border-border divide-y divide-border/60">
            {items.map((item) => (
              <li key={`${item.kind}-${item.id}`}>
                <button
                  type="button"
                  onClick={() => {
                    onPick(item);
                    onClose();
                  }}
                  className="w-full px-3 py-2.5 flex items-center gap-2.5 text-left hover:bg-muted/70 transition-colors cursor-pointer"
                >
                  <FileText className="w-4 h-4 shrink-0 text-blue-500 dark:text-blue-400" />
                  <span className="flex-1 min-w-0">
                    <span className="block text-xs text-foreground truncate">{item.name}</span>
                    <span className="block text-[10px] text-muted-foreground">
                      {item.kind === "file" ? `文件中心${item.size ? ` · ${item.size}` : ""}` : "知识库文档"}
                    </span>
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}

        <p className="text-[10px] text-muted-foreground leading-relaxed">
          {kind === "file"
            ? "引用后 AI 可用 manage_file 工具读取文件内容。"
            : "引用后回答会优先参考该文档（已在知识库中，检索时命中）。"}
        </p>
      </div>
    </Modal>
  );
}
