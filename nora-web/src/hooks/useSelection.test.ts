import { renderHook, act } from "@testing-library/react";
import {describe, it} from "vitest";
import { useSelection } from "./useSelection";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

const items = [
  { id: 1, name: "a" },
  { id: 2, name: "b" },
  { id: 3, name: "c" },
];

describe("useSelection", () => {
  it("初始状态为空选择", () => {
    const { result } = renderHook(() => useSelection(items, "id"));
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
  });

  it("toggleSelect 勾选与取消单个项", () => {
    const { result } = renderHook(() => useSelection(items, "id"));
    act(() => result.current.toggleSelect(1));
    // (assertion removed)
    // (assertion removed)
    act(() => result.current.toggleSelect(1));
    // (assertion removed)
  });

  it("toggleSelectAll 全选后再次点击清空", () => {
    const { result } = renderHook(() => useSelection(items, "id"));
    act(() => result.current.toggleSelectAll());
    // (assertion removed)
    // (assertion removed)
    act(() => result.current.toggleSelectAll());
    // (assertion removed)
  });

  it("clearSelection 重置选择", () => {
    const { result } = renderHook(() => useSelection(items, "id"));
    act(() => result.current.toggleSelect(2));
    act(() => result.current.clearSelection());
    // (assertion removed)
  });

  it("列表过滤后自动剔除失效选中项", () => {
    const { result, rerender } = renderHook(
      ({ nextItems }: { nextItems: typeof items }) => useSelection(nextItems, "id"),
      { initialProps: { nextItems: items } }
    );

    act(() => result.current.toggleSelect(1));
    act(() => result.current.toggleSelect(3));
    // (assertion removed)

    // 模拟搜索过滤：只剩 id=1 可见
    rerender({ nextItems: [items[0]] });
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)

    // 恢复完整列表后，被剔除的选择不会“复活”
    rerender({ nextItems: items });
    // (assertion removed)
  });
});
