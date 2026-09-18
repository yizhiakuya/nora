'use client';

import { Table2 } from "lucide-react";
import { GalleryShell, SectionLabel } from "./GalleryShell";

/**
 * table 画廊:SQL 查询结果/统计对比(数据表格)。
 *
 * 数据字段:
 *   title/summary/note(共享;stats 一般不需要——列头已表达)
 *   columns: [{ key, label, align?(left|right) }](可选;省略则从首行 key 推断)
 *   rows: [{ key: value, ... }]
 */
export function TableGallery({ data }: { data: Record<string, unknown> }) {
  const cols = Array.isArray(data.columns)
    ? (data.columns as Array<{ key: string; label: string; align?: string }>)
        .filter((c) => c && typeof c.key === "string")
    : [];
  const rows = Array.isArray(data.rows) ? (data.rows as Array<Record<string, unknown>>) : [];
  if (rows.length === 0) return null;

  // 无列定义:从首行 key 推断
  const effectiveCols = cols.length > 0
    ? cols
    : Object.keys(rows[0]).map((k) => ({ key: k, label: k, align: undefined as string | undefined }));

  return (
    <GalleryShell
      title={data.title as string | undefined}
      summary={data.summary as string | undefined}
      stats={data.stats as Array<{ label: string; value: string }> | undefined}
      note={data.note as string | undefined}
    >
      <SectionLabel icon={Table2} label={(data.sectionTitle as string) || "数据"} count={rows.length} />
      <div className="overflow-x-auto custom-scroll rounded-md border border-border/60">
        <table className="w-full text-[11px]">
          <thead>
            <tr className="bg-muted/40">
              {effectiveCols.map((c) => (
                <th
                  key={c.key}
                  className={`px-2 py-1.5 font-medium text-muted-foreground whitespace-nowrap ${
                    c.align === "right" ? "text-right" : "text-left"
                  }`}
                >
                  {c.label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={i} className="border-t border-border/50">
                {effectiveCols.map((c) => (
                  <td
                    key={c.key}
                    className={`px-2 py-1 text-foreground whitespace-nowrap ${
                      c.align === "right" ? "text-right tabular-nums" : "text-left"
                    }`}
                  >
                    {String(r[c.key] ?? "")}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </GalleryShell>
  );
}
