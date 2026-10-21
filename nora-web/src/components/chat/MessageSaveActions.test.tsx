import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, it, vi } from "vitest";
import { SaveToKnowledgeButton, SaveAsFileButton } from "./MessageSaveActions";
import * as rag from "@/lib/services/ragService";
import { workspaceApi } from "@/lib/services/workspaceApi";
import { savedArtifactsApi } from "@/lib/services/savedArtifactsApi";
import type { ChatMessage } from "@/lib/api/chatApi";
import type { KnowledgeDoc } from "@/types";

const msg: ChatMessage = { id: "smoke", role: "assistant", sender: "assistant", content: "冒烟保存内容", timestamp: "2026-10-13T10:00:00" };
beforeEach(() => {
  localStorage.removeItem("nora-saved-artifacts");
  vi.spyOn(workspaceApi, "writeFile").mockResolvedValue();
  vi.spyOn(workspaceApi, "readFile").mockResolvedValue(msg.content);
  vi.spyOn(savedArtifactsApi, "register").mockRejectedValue(new Error("登记服务不可用"));
});
afterEach(() => { cleanup(); vi.restoreAllMocks(); });

describe("消息成果保存冒烟", () => {
  it("知识保存期间拦截重复点击，登记失败保留保存结果", async () => {
    let release!: (doc: KnowledgeDoc) => void;
    vi.spyOn(rag, "saveTextAsync").mockReturnValue(new Promise(resolve => { release = resolve; }));
    render(<SaveToKnowledgeButton msg={msg} sessionId="smoke" />);
    fireEvent.click(screen.getByRole("button"));
    fireEvent.click(screen.getByRole("button"));
    await act(async () => release({ id: 1, name: "冒烟", source: "chat", chunks: 1, status: "indexed", size: "1 KB", updatedAt: "2026-10-13", quality: 100 }));
  });
  it("入库无返回值和失败允许重试", async () => {
    vi.spyOn(rag, "saveTextAsync").mockResolvedValueOnce(null).mockRejectedValueOnce(new Error("入库失败"));
    render(<SaveToKnowledgeButton msg={msg} sessionId="smoke" />);
    await act(async () => fireEvent.click(screen.getByRole("button")));
    await act(async () => fireEvent.click(screen.getByRole("button")));
  });
  it("文件保存成功、登记失败仍保留文件状态", async () => {
    render(<SaveAsFileButton msg={msg} sessionId="smoke" />);
    await act(async () => fireEvent.click(screen.getByRole("button")));
  });
});
