import { act, renderHook } from "@testing-library/react";
import { afterEach, describe, it, vi } from "vitest";
import { FileImage } from "lucide-react";
import type { ClipboardEvent } from "react";
import { filesApi } from "@/lib/services/filesApi";
import { useChatAttachments } from "./useChatAttachments";
import { useFiles } from "./useFiles";

function paste(files: File[]) {
  return { clipboardData: { files }, preventDefault: vi.fn() } as unknown as ClipboardEvent<HTMLTextAreaElement>;
}

describe("chat attachments smoke", () => {
  afterEach(() => {
    vi.restoreAllMocks();
    useFiles.setState({ files: [] });
  });

  it("leaves text paste to the textarea", () => {
    const { result } = renderHook(() => useChatAttachments());
    result.current.handlePaste(paste([]));
  });

  it("uploads consecutive image pastes and a picked file through the same path", async () => {
    let nextId = 1;
    vi.spyOn(filesApi, "uploadFile").mockImplementation(async file => ({
      id: nextId++, name: file.name, type: "图片", size: "1 KB", date: "2026-10-10",
      icon: FileImage, color: "text-blue-500", indexed: false,
    }));
    const { result } = renderHook(() => useChatAttachments(vi.fn()));
    await act(async () => {
      result.current.handlePaste(paste([new File(["png"], "image.png", { type: "image/png" })]));
      result.current.handlePaste(paste([new File(["jpeg"], "image.jpeg", { type: "image/jpeg" })]));
      await result.current.uploadFiles([new File(["text"], "notes.txt", { type: "text/plain" })]);
    });
  });

  it("reports a failed upload and continues the remaining files", async () => {
    vi.spyOn(filesApi, "uploadFile").mockRejectedValueOnce(new Error("upload unavailable"))
      .mockResolvedValue({ id: 1, name: "second.png", type: "图片", size: "1 KB", date: "2026-10-10",
        icon: FileImage, color: "text-blue-500", indexed: false });
    const { result } = renderHook(() => useChatAttachments(vi.fn()));
    await act(async () => {
      await result.current.uploadFiles([new File(["first"], "first.png"), new File(["second"], "second.png")]);
    });
  });
});
