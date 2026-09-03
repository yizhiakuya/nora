import { renderHook, act } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { useSelection } from "./useSelection";

const items = [
  { id: 1, name: "a" },
  { id: 2, name: "b" },
  { id: 3, name: "c" },
];

describe("useSelection", () => {
  it("初始状态为空选择", () => {
    const { result } = renderHook(() => useSelection(items, "id"));
    expect(result.current.selectedIds).toEqual([]);
    expect(result.current.hasSelection).toBe(false);
    expect(result.current.isAllSelected).toBe(false);
  });

  it("toggleSelect 勾选与取消单个项", () => {
    const { result } = renderHook(() => useSelection(items, "id"));
    act(() => result.current.toggleSelect(1));
    expect(result.current.selectedIds).toEqual([1]);
    expect(result.current.hasSelection).toBe(true);
    act(() => result.current.toggleSelect(1));
    expect(result.current.selectedIds).toEqual([]);
  });

  it("toggleSelectAll 全选后再次点击清空", () => {
    const { result } = renderHook(() => useSelection(items, "id"));
    act(() => result.current.toggleSelectAll());
    expect(result.current.selectedIds).toEqual([1, 2, 3]);
    expect(result.current.isAllSelected).toBe(true);
    act(() => result.current.toggleSelectAll());
    expect(result.current.selectedIds).toEqual([]);
  });

  it("clearSelection 重置选择", () => {
    const { result } = renderHook(() => useSelection(items, "id"));
    act(() => result.current.toggleSelect(2));
    act(() => result.current.clearSelection());
    expect(result.current.selectedIds).toEqual([]);
  });

  it("列表过滤后自动剔除失效选中项", () => {
    const { result, rerender } = renderHook(
      ({ nextItems }: { nextItems: typeof items }) => useSelection(nextItems, "id"),
      { initialProps: { nextItems: items } }
    );

    act(() => result.current.toggleSelect(1));
    act(() => result.current.toggleSelect(3));
    expect(result.current.selectedIds).toEqual([1, 3]);

    // 模拟搜索过滤：只剩 id=1 可见
    rerender({ nextItems: [items[0]] });
    expect(result.current.selectedIds).toEqual([1]);
    expect(result.current.hasSelection).toBe(true);
    expect(result.current.isAllSelected).toBe(true);

    // 恢复完整列表后，被剔除的选择不会“复活”
    rerender({ nextItems: items });
    expect(result.current.selectedIds).toEqual([1]);
  });
});
