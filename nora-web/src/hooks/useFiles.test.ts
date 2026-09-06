import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, beforeEach } from "vitest";
import { useFiles } from "./useFiles";
import { FileText } from "lucide-react";
import type { FileItem } from "@/types";

/** 测试夹具:替代已删除的 MOCK_FILES(store 初始为空,后端是唯一数据源) */
const FIXTURE_FILES: FileItem[] = [
  { id: 1, name: "测试开发报告.docx", type: "Word 文档", size: "2.1 MB", date: "2026-09-06 10:00", icon: FileText, color: "text-blue-500", indexed: false },
  { id: 2, name: "sales.xlsx", type: "Excel 表格", size: "88 KB", date: "2026-09-06 11:00", icon: FileText, color: "text-green-600", indexed: true },
];

describe("useFiles", () => {
  beforeEach(() => {
    useFiles.setState({ files: FIXTURE_FILES });
  });

  it("初始状态为空(后端是唯一数据源)", () => {
    useFiles.setState({ files: [] });
    const { result } = renderHook(() => useFiles());
    expect(result.current.files).toEqual([]);
  });

  it("addFile 能够正确新增文件并推入列表顶部", () => {
    const { result } = renderHook(() => useFiles());
    act(() => {
      result.current.addFile("测试开发报告.docx", "2.1 MB");
    });
    expect(result.current.files[0].name).toBe("测试开发报告.docx");
    expect(result.current.files[0].type).toBe("Word 文档");
    expect(result.current.files[0].indexed).toBe(false);
  });

  it("markIndexed 将指定文件标记为已索引", () => {
    const { result } = renderHook(() => useFiles());
    const unindexed = result.current.files.find((f) => !f.indexed);
    if (unindexed) {
      act(() => {
        result.current.markIndexed(unindexed.id);
      });
      const updated = result.current.files.find((f) => f.id === unindexed.id);
      expect(updated?.indexed).toBe(true);
    }
  });

  it("deleteFiles 能够批量删除指定 ID 的文件", () => {
    const { result } = renderHook(() => useFiles());
    const targetId = result.current.files[0].id;
    act(() => {
      result.current.deleteFiles([targetId]);
    });
    expect(result.current.files.some((f) => f.id === targetId)).toBe(false);
  });
});
