import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, vi, afterEach } from "vitest";
import { useFileViewer } from "./useFileViewer";
import { FileItem } from "@/types";
import { FileText } from "lucide-react";

const pdfFile: FileItem = {
  id: 1,
  name: "NestJS部署手册.pdf",
  type: "PDF 文档",
  size: "2.4 MB",
  date: "2024-06-02 14:30",
  icon: FileText,
  color: "text-red-500",
  indexed: false,
};

describe("useFileViewer", () => {
  afterEach(() => {
    vi.useRealTimers();
  });

  it("open 进入 loading，拉取后 ready，close 复位", async () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useFileViewer());
    expect(result.current.status).toBe("idle");

    act(() => {
      void result.current.open(pdfFile);
    });
    expect(result.current.status).toBe("loading");
    expect(result.current.activeFile?.id).toBe(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });
    expect(result.current.status).toBe("ready");
    expect(result.current.preview?.kind).toBe("pdf");
    expect(result.current.preview?.pages).toBeGreaterThan(0);

    act(() => result.current.close());
    expect(result.current.status).toBe("idle");
    expect(result.current.activeFile).toBeNull();
  });

  it("快速连续打开不同文件时仅保留最新响应", async () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useFileViewer());
    const wordFile: FileItem = { ...pdfFile, id: 2, name: "API接口设计规范.docx", type: "Word 文档" };

    act(() => {
      void result.current.open(pdfFile);
    });
    act(() => {
      void result.current.open(wordFile);
    });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(600);
    });

    expect(result.current.activeFile?.id).toBe(2);
    expect(result.current.preview?.kind).toBe("word");
  });
});
