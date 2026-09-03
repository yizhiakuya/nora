import { FilePreview } from "@/types";

export function ExcelPreview({ preview }: { preview: FilePreview }) {
  const { columns, rows } = preview.table ?? { columns: [], rows: [] };
  return (
    <div className="bg-white border border-gray-200 rounded-lg shadow-sm overflow-hidden overflow-x-auto">
      <table className="w-full text-left border-collapse min-w-[520px]">
        <thead>
          <tr className="bg-gray-50 border-b border-gray-200 text-xs text-gray-500 font-medium">
            <th className="p-3 pl-4 w-10 text-gray-300">#</th>
            {columns.map((col) => (
              <th key={col} className="p-3 whitespace-nowrap">{col}</th>
            ))}
          </tr>
        </thead>
        <tbody className="text-sm">
          {rows.map((row, ri) => (
            <tr key={ri} className="border-b border-gray-50 hover:bg-gray-50/60 transition-colors">
              <td className="p-3 pl-4 text-gray-300 text-xs">{ri + 1}</td>
              {row.map((cell, ci) => (
                <td key={ci} className={`p-3 whitespace-nowrap ${ci === 1 ? "font-medium text-gray-800" : "text-gray-600"}`}>{cell}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
