import { ExternalLink } from "lucide-react";
import { Checkbox } from "@/components/ui/checkbox";
import { Button } from "@/components/ui/button";
import { BrowserLink } from "@/components/shared/BrowserLink";
import type { BrowserResultPage } from "@/types";

interface Props {
  page: BrowserResultPage;
  selected: Set<string>;
  editable: boolean;
  disabled: boolean;
  toggle: (id: string, value: boolean) => void;
  setOffset: (offset: number) => void;
}

export function BrowserResultTable({ page, selected, editable, disabled, toggle, setOffset }: Props) {
  const duplicates = new Set(page.duplicateIds), missing = new Set(page.missingIds);
  return <div>
    <div className="overflow-auto max-h-[48vh] border rounded-lg">
      <table className="w-full text-xs text-left">
        <thead className="sticky top-0 bg-muted z-10"><tr>
          {editable && <th className="p-3 whitespace-nowrap">保留</th>}<th className="p-3">序号</th>
          {page.columns.map(column => <th key={column} className="p-3 whitespace-nowrap font-medium">{column}</th>)}
          <th className="p-3 whitespace-nowrap font-medium">来源</th><th className="p-3 whitespace-nowrap font-medium">检查</th>
        </tr></thead>
        <tbody>{page.rows.map((row, index) => <tr key={row.id} className={`border-t hover:bg-muted/40 ${editable && !selected.has(row.id) ? "opacity-40" : ""}`}>
          {editable && <td className="p-3"><Checkbox aria-label={`保留第 ${page.offset + index + 1} 行`} checked={selected.has(row.id)} disabled={disabled}
            onCheckedChange={value => toggle(row.id, value === true)} /></td>}
          <td className="p-3 text-muted-foreground tabular-nums">{page.offset + index + 1}</td>
          {page.columns.map(column => <td key={column} className="p-3 min-w-[140px] max-w-[340px] align-top">
            {/^https?:\/\//i.test(row.values[column]) ? <BrowserLink href={row.values[column]} title={row.values[column]} className="text-blue-600 break-all line-clamp-2 hover:underline">{row.values[column]}</BrowserLink>
              : <span title={row.values[column]} className="line-clamp-2 break-words">{row.values[column] || <span className="text-amber-600">缺失</span>}</span>}
          </td>)}
          <td className="p-3"><BrowserLink href={row.sourceUrl} title={`${row.sourceUrl}\n采集于 ${new Date(row.capturedAt).toLocaleString("zh-CN")}`} className="inline-flex gap-1 items-center text-blue-600 whitespace-nowrap hover:underline">原网页<ExternalLink className="w-3 h-3" /></BrowserLink></td>
          <td className="p-3 whitespace-nowrap text-amber-600">{missing.has(row.id) ? "字段缺失" : duplicates.has(row.id) ? "重复项" : <span className="text-muted-foreground">完整</span>}</td>
        </tr>)}</tbody>
      </table>
    </div>
    {page.total > 50 && <div className="flex items-center justify-end gap-3 mt-3 text-xs text-muted-foreground">
      <span>{page.offset + 1}–{Math.min(page.offset + 50, page.total)} / {page.total} 条</span>
      <Button size="sm" variant="outline" disabled={disabled || page.offset === 0} onClick={() => setOffset(Math.max(0, page.offset - 50))}>上一页</Button>
      <Button size="sm" variant="outline" disabled={disabled || page.offset + 50 >= page.total} onClick={() => setOffset(page.offset + 50)}>下一页</Button>
    </div>}
  </div>;
}
