import React from "react";

/**
 * 响应式列表原语(2026-09-17):同一份行数据,宽屏渲染表格、窄屏渲染卡片。
 *
 * 为什么需要:此前 5 个表格都用 `min-w-[500~560px]` + 外层 overflow-x-auto
 * 兜底——移动端"能横滚但看不见关键列"(操作列滚出屏幕),体验差。
 * 本原语按**容器宽度**(CSS 媒体查询 md=768px)自动切换:
 * - 桌面:语义化 table(表头对齐、行 hover、密度高);
 * - 移动:卡片堆叠(每张卡片是 label:value 列表,操作在卡片底部)。
 *
 * 用法:只需提供列定义(表头 + 单元格渲染 + 移动端 label),两个形态自动生成。
 */

export interface ResponsiveColumn<T> {
  /** 表头文字(桌面) */
  header: React.ReactNode;
  /** 单元格渲染(桌面与移动共用;移动端卡片里是 value) */
  cell: (row: T) => React.ReactNode;
  /** 移动端卡片里的字段名(label:value);不传则该列不出现在卡片里 */
  mobileLabel?: string;
  /** 列宽类(桌面;如 "w-24") */
  widthClass?: string;
  /** 单元格额外类(桌面) */
  cellClass?: string;
  /** 是否右对齐(桌面) */
  align?: "left" | "right";
}

interface ResponsiveListProps<T> {
  rows: T[];
  columns: ResponsiveColumn<T>[];
  rowKey: (row: T) => string | number;
  /** 移动端卡片的主标题(通常是文件名;卡片顶部大字) */
  mobileTitle: (row: T) => React.ReactNode;
  /** 移动端卡片的副标题(可选;标题下方小字) */
  mobileSubtitle?: (row: T) => React.ReactNode;
  /** 移动端卡片底部操作区(按钮组) */
  mobileActions?: (row: T) => React.ReactNode;
  /** 桌面行点击(可选;如选中) */
  onRowClick?: (row: T) => void;
  /** 行额外类(桌面) */
  rowClass?: (row: T) => string;
  /** 行额外属性(桌面;如 draggable/onDragStart) */
  rowProps?: (row: T) => React.HTMLAttributes<HTMLTableRowElement>;
  /** 空态/加载态(整块替换) */
  empty?: React.ReactNode;
  /** 行数据为空时是否渲染 empty(否则渲染表头 + 空体) */
  className?: string;
}

export function ResponsiveList<T>({
  rows,
  columns,
  rowKey,
  mobileTitle,
  mobileSubtitle,
  mobileActions,
  onRowClick,
  rowClass,
  rowProps,
  empty,
  className = "",
}: ResponsiveListProps<T>) {
  if (rows.length === 0 && empty) {
    return <>{empty}</>;
  }

  return (
    <div className={className}>
      {/* 桌面:表格(md 及以上) */}
      <div className="hidden md:block bg-card border border-border rounded-xl overflow-hidden overflow-x-auto">
        <table className="w-full text-left border-collapse">
          <thead>
            <tr className="bg-muted/60 border-b border-border text-xs text-muted-foreground font-medium select-none">
              {columns.map((col, i) => (
                <th
                  key={i}
                  className={`p-3 font-medium ${i === 0 ? "pl-4" : ""} ${i === columns.length - 1 ? "text-right pr-4" : ""} ${col.align === "right" ? "text-right" : ""} ${col.widthClass ?? ""}`}
                >
                  {col.header}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="text-sm">
            {rows.map((row) => (
              <tr
                key={rowKey(row)}
                className={`border-b border-border last:border-0 transition-colors hover:bg-muted/60 ${onRowClick ? "cursor-pointer" : ""} ${rowClass?.(row) ?? ""}`}
                onClick={onRowClick ? () => onRowClick(row) : undefined}
                {...rowProps?.(row)}
              >
                {columns.map((col, i) => (
                  <td
                    key={i}
                    className={`p-3 ${i === 0 ? "pl-4" : ""} ${i === columns.length - 1 ? "text-right pr-4" : ""} ${col.align === "right" ? "text-right" : ""} ${col.cellClass ?? ""}`}
                  >
                    {col.cell(row)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* 移动:卡片堆叠(md 以下) */}
      <div className="md:hidden space-y-2">
        {rows.map((row) => (
          <div
            key={rowKey(row)}
            className={`bg-card border border-border rounded-xl p-3.5 ${onRowClick ? "cursor-pointer active:bg-muted/60" : ""} ${rowClass?.(row) ?? ""}`}
            onClick={onRowClick ? () => onRowClick(row) : undefined}
          >
            {/* 标题区 */}
            <div className="flex items-start gap-3 min-w-0">
              <div className="min-w-0 flex-1">
                <div className="text-sm font-medium text-foreground truncate">{mobileTitle(row)}</div>
                {mobileSubtitle && (
                  <div className="text-[11px] text-muted-foreground mt-0.5 truncate">{mobileSubtitle(row)}</div>
                )}
              </div>
            </div>
            {/* 字段列表(label:value) */}
            {columns.some((c) => c.mobileLabel) && (
              <div className="mt-2.5 space-y-1.5">
                {columns.map((col, i) =>
                  col.mobileLabel ? (
                    <div key={i} className="flex items-center justify-between gap-3 text-xs">
                      <span className="text-muted-foreground shrink-0">{col.mobileLabel}</span>
                      <span className="text-foreground text-right min-w-0 truncate">{col.cell(row)}</span>
                    </div>
                  ) : null
                )}
              </div>
            )}
            {/* 操作区 */}
            {mobileActions && (
              <div className="mt-3 pt-2.5 border-t border-border flex items-center justify-end gap-1.5 flex-wrap">
                {mobileActions(row)}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
