import { FilePreview } from "@/types";

/**
 * 表格预览(2026-09-17 接通真实数据):Tika 提取的文本在 filesApi.toPreview
 * 里解析成二维表,这里做展示。此前读的 preview.table 后端从不填充——
 * Excel 打开永远空白,现在数据通了。
 *
 * 行数上限 500(解析层已截断),超出提示下载看全量。
 */
export function ExcelPreview({ preview }: { preview: FilePreview }) {
  const { columns, rows } = preview.table ?? { columns: [], rows: [] };
  if (columns.length === 0) {
    return <div className="py-16 text-center text-xs text-muted-foreground">（表格内容为空或无法解析）</div>;
  }
  return (
    <div className="flex flex-col gap-2">
      <div className="text-[10px] text-muted-foreground px-1">
        {rows.length} 行 × {columns.length} 列{rows.length >= 500 ? "（仅显示前 500 行,完整内容请下载）" : ""}
      </div>
      <div className="bg-card border border-border rounded-lg shadow-sm overflow-hidden overflow-x-auto">
        <table className="w-full text-left border-collapse min-w-[520px]">
          <thead>
            <tr className="bg-muted border-b border-border text-xs text-muted-foreground font-medium sticky top-0">
              <th className="p-3 pl-4 w-10 text-muted-foreground/60 bg-muted">#</th>
              {columns.map((col, i) => (
                <th key={`${col}-${i}`} className="p-3 whitespace-nowrap bg-muted">{col}</th>
              ))}
            </tr>
          </thead>
          <tbody className="text-sm">
            {rows.map((row, ri) => (
              <tr key={ri} className="border-b border-gray-50 dark:border-gray-800 hover:bg-gray-50/60 dark:hover:bg-gray-800/60 transition-colors">
                <td className="p-3 pl-4 text-muted-foreground/60 text-xs tabular-nums">{ri + 1}</td>
                {columns.map((_, ci) => (
                  <td key={ci} className={`p-3 whitespace-nowrap ${ci === 0 ? "font-medium text-foreground" : "text-muted-foreground"}`}>
                    {row[ci] ?? ""}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

