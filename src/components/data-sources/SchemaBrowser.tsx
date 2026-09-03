'use client';

import { useState } from "react";
import { ChevronRight, Key, Table2 } from "lucide-react";
import { MOCK_TABLES } from "@/lib/devData";

export function SchemaBrowser({ database }: { database: string }) {
  const tables = MOCK_TABLES[database] ?? MOCK_TABLES.myapp_dev;
  const [expanded, setExpanded] = useState<string | null>(tables[0]?.name ?? null);

  return (
    <div className="bg-white dark:bg-gray-900 border border-gray-200 dark:border-gray-800 rounded-xl overflow-hidden">
      <div className="p-3 border-b border-gray-100 dark:border-gray-800 bg-gray-50/50 dark:bg-gray-950/50">
        <span className="text-xs font-bold text-gray-700 dark:text-gray-200">
          {database} · {tables.length} 张表
        </span>
      </div>
      <div className="divide-y divide-gray-100 dark:divide-gray-800">
        {tables.map((table) => {
          const isOpen = expanded === table.name;
          return (
            <div key={table.name}>
              <button
                type="button"
                onClick={() => setExpanded(isOpen ? null : table.name)}
                className="w-full flex items-center gap-2.5 px-4 py-2.5 hover:bg-gray-50 dark:hover:bg-gray-800/50 transition-colors cursor-pointer text-left"
              >
                <ChevronRight className={`w-3.5 h-3.5 text-gray-400 dark:text-gray-500 transition-transform ${isOpen ? "rotate-90" : ""}`} />
                <Table2 className="w-4 h-4 text-blue-600 dark:text-blue-400 shrink-0" />
                <span className="text-sm font-medium text-gray-800 dark:text-gray-100 font-mono">{table.name}</span>
                <span className="ml-auto text-[10px] text-gray-400 dark:text-gray-500 tabular-nums">
                  {table.rows.toLocaleString()} 行 · {table.size}
                </span>
              </button>
              {isOpen && (
                <div className="bg-gray-50 dark:bg-gray-950/50 px-4 pb-3">
                  <table className="w-full text-xs">
                    <thead>
                      <tr className="text-gray-400 dark:text-gray-500 border-b border-gray-200 dark:border-gray-800">
                        <th className="py-1.5 pr-3 font-medium text-left">字段</th>
                        <th className="py-1.5 pr-3 font-medium text-left">类型</th>
                        <th className="py-1.5 pr-3 font-medium text-left">说明</th>
                      </tr>
                    </thead>
                    <tbody>
                      {table.columns.map((col) => (
                        <tr key={col.name} className="border-b border-gray-100 dark:border-gray-800 last:border-0">
                          <td className="py-1.5 pr-3 font-mono text-gray-800 dark:text-gray-100">
                            <span className="inline-flex items-center gap-1">
                              {col.isPrimary && <Key className="w-3 h-3 text-yellow-500" />}
                              {col.name}
                            </span>
                          </td>
                          <td className="py-1.5 pr-3 text-gray-500 dark:text-gray-400 font-mono">
                            {col.type}{col.nullable ? "" : " NOT NULL"}
                          </td>
                          <td className="py-1.5 text-gray-400 dark:text-gray-500">{col.comment ?? "—"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
