import { BookOpen, FileText, Wrench, X, Zap } from "lucide-react";
import { refKey, type ChatRef } from "@/lib/chatRefs";

/**
 * 引用 chip(2026-09-17):输入区待发送区与用户气泡共用。
 * 四类引用统一视觉:文件=蓝 / 知识库=紫 / 技能=绿 / MCP 工具=橙。
 */
export const REF_META: Record<ChatRef["kind"], { cls: string; iconCls: string; label: string; Icon: React.ElementType }> = {
  file: {
    cls: "bg-blue-50 dark:bg-blue-950/40 text-blue-600 dark:text-blue-400 border-blue-200 dark:border-blue-800",
    iconCls: "text-blue-500 dark:text-blue-400",
    label: "文件",
    Icon: FileText,
  },
  doc: {
    cls: "bg-purple-50 dark:bg-purple-950/40 text-purple-600 dark:text-purple-400 border-purple-200 dark:border-purple-800",
    iconCls: "text-purple-500 dark:text-purple-400",
    label: "知识库",
    Icon: BookOpen,
  },
  skill: {
    cls: "bg-green-50 dark:bg-green-950/40 text-green-600 dark:text-green-400 border-green-200 dark:border-green-800",
    iconCls: "text-green-500 dark:text-green-400",
    label: "技能",
    Icon: Zap,
  },
  mcp: {
    cls: "bg-orange-50 dark:bg-orange-950/40 text-orange-600 dark:text-orange-400 border-orange-200 dark:border-orange-800",
    iconCls: "text-orange-500 dark:text-orange-400",
    label: "MCP 工具",
    Icon: Wrench,
  },
};

export function RefChip({ chatRef, onRemove }: { chatRef: ChatRef; onRemove?: (key: string) => void }) {
  const meta = REF_META[chatRef.kind];
  return (
    <span
      className={`inline-flex items-center gap-1 pl-2 pr-1 py-0.5 rounded-full text-[10px] font-medium border ${meta.cls}`}
      title={
        chatRef.kind === "mcp"
          ? `MCP 工具引用 · ${chatRef.tool ?? ""}`
          : `${meta.label}引用 · ${chatRef.kind}_id=${chatRef.id}`
      }
    >
      <meta.Icon className="w-3 h-3" />
      <span className="max-w-[160px] truncate">{chatRef.name}</span>
      {onRemove && (
        <button
          type="button"
          onClick={() => onRemove(refKey(chatRef))}
          className="ml-0.5 w-3.5 h-3.5 rounded-full inline-flex items-center justify-center hover:bg-black/10 dark:hover:bg-white/10 cursor-pointer"
          title="移除引用"
        >
          <X className="w-2.5 h-2.5" />
        </button>
      )}
    </span>
  );
}
