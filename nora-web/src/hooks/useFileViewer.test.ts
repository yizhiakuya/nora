import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { useFileViewer } from "./useFileViewer";
import { FileItem } from "@/types";
import { FileText } from "lucide-react";

// 后端是唯一数据源:预览走 filesApi,测试中打桩控制时序与返回
const fetchPreviewMock = vi.fn();
vi.mock("@/lib/services/filesApi", () => ({
  filesApi: { fetchPreview: (...args: unknown[]) => fetchPreviewMock(...args) },
}));

function makePreview(kind: string) {
  return { kind, pages: kind === "pdf" ? 3 : undefined } as never;
}

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
  beforeEach(() => {
    fetchPreviewMock.mockReset();
    fetchPreviewMock.mockImplementation(async () => makePreview("pdf"));
  });
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
      await Promise.resolve();
    });
    expect(result.current.status).toBe("ready");
    expect(result.current.preview?.kind).toBe("pdf");

    act(() => result.current.close());
    expect(result.current.status).toBe("idle");
    expect(result.current.activeFile).toBeNull();
  });

  it("快速连续打开不同文件时仅保留最新响应", async () => {
    vi.useFakeTimers();
    const { result } = renderHook(() => useFileViewer());
    const wordFile: FileItem = { ...pdfFile, id: 2, name: "API接口设计规范.docx", type: "Word 文档" };

    fetchPreviewMock.mockImplementationOnce(async () => makePreview("pdf"));
    fetchPreviewMock.mockImplementationOnce(async () => makePreview("word"));
    act(() => {
      void result.current.open(pdfFile);
    });
    act(() => {
      void result.current.open(wordFile);
    });
    await act(async () => {
      await Promise.resolve();
    });

    expect(result.current.activeFile?.id).toBe(2);
    expect(result.current.preview?.kind).toBe("word");
  });
});
