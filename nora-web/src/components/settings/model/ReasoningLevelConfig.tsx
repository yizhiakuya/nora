'use client';

import { useEffect, useRef, useState } from "react";
import { Check, ChevronDown, Search } from "lucide-react";
import { REASONING_LEVELS, REASONING_LEVELS_DISABLED } from "@/hooks/useModelProviders";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";

const ALL_LEVELS: string[] = [...REASONING_LEVELS];

interface ReasoningLevelPickerProps {
  /** 已选等级白名单;空数组 = 全部等级 */
  levels: string[];
  /** 默认等级;null = 自动 */
  defaultLevel: string | null;
  /** 白名单变化(面板关闭时提交) */
  onLevelsChange: (levels: string[]) => void;
  /** 默认等级变化(立即提交) */
  onDefaultChange: (level: string | null) => void;
}

/**
 * 思考等级选择器(设置 · 模型列表 · 每行):
 * 可搜索多选等级,面板底部为默认等级下拉。
 * 勾选集 = 该模型在对话框可选的等级;默认等级 = 对话框不选时生效的档位。
 */
export function ReasoningLevelPicker({ levels, defaultLevel, onLevelsChange, onDefaultChange }: ReasoningLevelPickerProps) {
  const [open, setOpen] = useState(false);
  const [query, setQuery] = useState("");
  const [draft, setDraft] = useState<string[]>(levels.length ? levels : ALL_LEVELS);
  const boxRef = useRef<HTMLDivElement>(null);

  // 外部值变化时同步草稿(仅面板关闭时,避免编辑中被覆盖)
  useEffect(() => {
    if (!open) setDraft(levels.length ? levels : ALL_LEVELS);
  }, [levels, open]);

  const close = () => {
    setOpen(false);
    setQuery("");
    const finalLevels = draft.length ? draft : ALL_LEVELS;
    const finalDefault = defaultLevel && finalLevels.includes(defaultLevel) ? defaultLevel : null;
    if (finalDefault !== defaultLevel) onDefaultChange(finalDefault);
    const changed = finalLevels.length !== levels.length || !finalLevels.every((l) => levels.includes(l));
    if (changed) onLevelsChange(finalLevels);
  };

  // 点击面板外关闭并提交
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (boxRef.current && !boxRef.current.contains(e.target as Node)) {
        close();
      }
    };
    document.addEventListener("mousedown", onDown);
    return () => document.removeEventListener("mousedown", onDown);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, draft, levels, defaultLevel]);

  const toggle = (level: string) => {
    setDraft((prev) => (prev.includes(level) ? prev.filter((l) => l !== level) : [...prev, level]));
  };

  const filtered = [...ALL_LEVELS, ...REASONING_LEVELS_DISABLED].filter((l) =>
    l.toLowerCase().includes(query.trim().toLowerCase())
  );

  const triggerText = levels.length === 0 || levels.length === ALL_LEVELS.length
    ? ALL_LEVELS.join(", ")
    : levels.join(", ");

  return (
    <div className="relative" ref={boxRef}>
      <button
        type="button"
        onClick={() => (open ? close() : setOpen(true))}
        className="w-full flex items-center justify-between gap-2 h-8 rounded-md border border-border bg-card px-2.5 text-xs text-foreground hover:border-blue-500 focus:outline-none focus:border-blue-500 transition-colors"
      >
        <span className="truncate font-mono">{triggerText}</span>
        <ChevronDown className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
      </button>

      {open && (
        <div className="absolute right-0 z-50 mt-1 w-60 rounded-lg border border-border bg-card shadow-lg overflow-hidden">
          <div className="flex items-center gap-1.5 px-2.5 py-2 border-b border-border/60">
            <Search className="w-3.5 h-3.5 text-muted-foreground shrink-0" />
            <input
              autoFocus
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="搜索思考等级..."
              className="w-full bg-transparent text-xs outline-none placeholder:text-muted-foreground"
            />
          </div>
          <div className="max-h-56 overflow-y-auto custom-scroll py-1">
            {filtered.map((level) => {
              const disabled = (REASONING_LEVELS_DISABLED as readonly string[]).includes(level);
              const checked = !disabled && draft.includes(level);
              return (
                <button
                  key={level}
                  type="button"
                  disabled={disabled}
                  onClick={() => toggle(level)}
                  className={`w-full flex items-center gap-2 px-2.5 py-1.5 text-left text-xs transition-colors ${
                    disabled
                      ? "text-muted-foreground/50 cursor-not-allowed"
                      : "text-foreground hover:bg-muted/60 cursor-pointer"
                  }`}
                >
                  <span className="w-3.5 flex justify-center shrink-0">
                    {checked && <Check className="w-3.5 h-3.5 text-blue-600 dark:text-blue-400" />}
                  </span>
                  <span className="font-mono">{level}</span>
                  {disabled && <span className="ml-auto text-[10px] text-muted-foreground">暂不支持</span>}
                </button>
              );
            })}
            {filtered.length === 0 && (
              <div className="px-2.5 py-3 text-xs text-muted-foreground text-center">无匹配等级</div>
            )}
          </div>
          <div className="border-t border-border/60 px-2.5 py-2">
            <div className="text-[10px] text-muted-foreground mb-1">默认等级</div>
            <Select
              value={defaultLevel ?? "auto"}
              onValueChange={(v) => onDefaultChange(v === "auto" ? null : v)}
            >
              <SelectTrigger className="h-7 text-xs">
                <SelectValue />
              </SelectTrigger>
              <SelectContent className="max-h-48" position="popper">
                <SelectItem value="auto" className="text-xs">自动</SelectItem>
                {(draft.length ? draft : ALL_LEVELS).map((level) => (
                  <SelectItem key={level} value={level} className="text-xs font-mono">
                    {level}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>
      )}
    </div>
  );
}
