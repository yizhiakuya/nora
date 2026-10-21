import { useState, type ClipboardEvent } from "react";
import { toast } from "sonner";
import { filesApi } from "@/lib/services/filesApi";
import { useFiles } from "./useFiles";
import type { ChatRef } from "@/lib/chatRefs";

export function useChatAttachments(onAddRef?: (ref: ChatRef) => void) {
  const [pending, setPending] = useState(0);

  async function uploadFiles(files: File[]) {
    if (!files.length) return;
    setPending(count => count + 1);
    try {
      for (const file of files) {
        try {
          const item = await filesApi.uploadFile(file);
          useFiles.getState().syncFile(item);
          onAddRef?.({ kind: "file", id: item.id, name: item.name, size: item.size });
        } catch (error) {
          toast.error(`「${file.name}」上传失败：${error instanceof Error ? error.message : String(error)}`);
        }
      }
    } finally {
      setPending(count => count - 1);
    }
  }

  function handlePaste(event: ClipboardEvent<HTMLTextAreaElement>) {
    const images = Array.from(event.clipboardData.files).filter(file => file.type.startsWith("image/"));
    if (!images.length) return;
    event.preventDefault();
    const stamp = Date.now();
    void uploadFiles(images.map((file, index) => new File([file],
      `粘贴图片-${stamp}-${index + 1}.${file.type === "image/jpeg" ? "jpg" : file.type.slice(6).split("+")[0]}`,
      { type: file.type })));
  }

  return { uploading: pending > 0, uploadFiles, handlePaste };
}
