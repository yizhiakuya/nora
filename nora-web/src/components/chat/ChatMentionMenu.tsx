import { BookOpen, CornerDownLeft, FileText, Wrench, Zap } from "lucide-react";
import { refKey, type ChatRef } from "@/lib/chatRefs";
import type { ChatCommand } from "@/lib/chatCommands";
import { useEffect, useRef } from "react";

export type MentionItem = (ChatRef & { hint: string }) | { kind: "command"; name: string; hint: string; command: ChatCommand };

export function ChatMentionMenu({ items, selected, title, onSelect, onHover }: {
  items: MentionItem[]; selected: number; title: string;
  onSelect: (item: MentionItem) => void; onHover: (index: number) => void;
}) {
  const list = useRef<HTMLUListElement>(null);
  useEffect(() => { list.current?.querySelector('[aria-selected="true"]')?.scrollIntoView?.({ block: "nearest" }); }, [selected]);
  return <div className="flex min-h-0 flex-col">
    <ul ref={list} id="chat-mention-menu" role="listbox" aria-label={title} className="min-h-0 max-h-[min(320px,40dvh)] overflow-y-auto p-1.5 no-scrollbar">
      {items.map((item, index) => {
        const Icon = item.kind === "doc" ? BookOpen : item.kind === "skill" ? Zap : item.kind === "mcp" ? Wrench : FileText;
        return <li key={item.kind === "command" ? `command-${item.name}` : refKey(item)} role="option" id={`chat-mention-${index}`} aria-selected={index === selected}>
          {index === 0 || items[index - 1].kind === "command" && item.kind !== "command"
            ? <div className="px-2.5 py-1.5 text-[11px] text-muted-foreground">{item.kind === "command" ? "Agent 命令" : "技能与资料引用"}</div> : null}
          <button type="button" tabIndex={-1} onMouseDown={event => { event.preventDefault(); onSelect(item); }} onMouseEnter={() => onHover(index)}
            title={item.kind === "command" && "argument" in item.command ? `/${item.name} [${item.command.argument}] · ${item.hint}` : `${item.name} · ${item.hint}`}
            className={`flex h-9 w-full cursor-pointer items-center gap-2 rounded-md px-2.5 text-left ${index === selected ? "bg-muted/70" : "hover:bg-muted/50"}`}>
            {item.kind !== "command" && <Icon className="h-3.5 w-3.5 shrink-0 text-muted-foreground" />}
            <span className={`truncate text-[13px] ${item.kind === "command" ? "w-28 shrink-0 font-mono font-medium" : "min-w-0 flex-1"}`}>{item.kind === "command" ? `/${item.name}` : item.name}</span>
            <span className={`truncate text-xs text-muted-foreground ${item.kind === "command" ? "min-w-0 flex-1" : "max-w-[120px]"}`}>{item.hint}</span>
            <CornerDownLeft aria-hidden="true" className={`h-3 w-3 shrink-0 text-muted-foreground ${index === selected ? "opacity-70" : "invisible"}`} />
          </button>
        </li>;
      })}
    </ul>
    <div className="flex shrink-0 items-center justify-between gap-2 border-t border-border/50 px-3 py-1.5 text-[11px] text-muted-foreground">
      <span>↑↓ 选择 · ↵ 补全</span><span>Esc 关闭</span>
    </div>
  </div>;
}
