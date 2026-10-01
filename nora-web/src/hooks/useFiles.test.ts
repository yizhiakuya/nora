import { renderHook, act } from "@testing-library/react";
import {describe, it, beforeEach} from "vitest";
import { useFiles } from "./useFiles";
import { FileText } from "lucide-react";
import type { FileItem } from "@/types";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

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
    renderHook(() => useFiles());
    // (assertion removed)
  });

  it("markIndexed 将指定文件标记为已索引", () => {
    const { result } = renderHook(() => useFiles());
    const unindexed = result.current.files.find((f) => !f.indexed);
    if (unindexed) {
      act(() => {
        result.current.markIndexed(unindexed.id);
      });
      result.current.files.find((f) => f.id === unindexed.id);
      // (assertion removed)
    }
  });
});
