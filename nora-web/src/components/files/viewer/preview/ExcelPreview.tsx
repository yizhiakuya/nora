import { FilePreview } from "@/types";

export function ExcelPreview({ preview }: { preview: FilePreview }) {
  const { columns, rows } = preview.table ?? { columns: [], rows: [] };
  return (
    <div className="bg-card border border-border rounded-lg shadow-sm overflow-hidden overflow-x-auto">
      <table className="w-full text-left border-collapse min-w-[520px]">
        <thead>
          <tr className="bg-muted border-b border-border text-xs text-muted-foreground font-medium">
            <th className="p-3 pl-4 w-10 text-muted-foreground/60">#</th>
            {columns.map((col) => (
              <th key={col} className="p-3 whitespace-nowrap">{col}</th>
            ))}
          </tr>
        </thead>
        <tbody className="text-sm">
          {rows.map((row, ri) => (
            <tr key={ri} className="border-b border-gray-50 dark:border-gray-800 hover:bg-gray-50/60 dark:hover:bg-gray-800/60 transition-colors">
              <td className="p-3 pl-4 text-muted-foreground/60 text-xs">{ri + 1}</td>
              {row.map((cell, ci) => (
                <td key={ci} className={`p-3 whitespace-nowrap ${ci === 1 ? "font-medium text-foreground" : "text-muted-foreground"}`}>{cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
