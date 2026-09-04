import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, beforeEach } from "vitest";
import { useFiles } from "./useFiles";
import { MOCK_FILES } from "@/lib/mockData";

describe("useFiles", () => {
  beforeEach(() => {
    useFiles.setState({ files: MOCK_FILES });
  });

  it("初始状态包含 MOCK_FILES", () => {
    const { result } = renderHook(() => useFiles());
    expect(result.current.files.length).toBe(MOCK_FILES.length);
  });

  it("addFile 能够正确新增文件并推入列表顶部", () => {
    const { result } = renderHook(() => useFiles());
    act(() => {
      result.current.addFile("测试开发报告.docx", "2.1 MB");
    });
    expect(result.current.files.length).toBe(MOCK_FILES.length + 1);
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
