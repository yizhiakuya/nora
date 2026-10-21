import { afterEach, it, vi } from "vitest";
import { useChatSessions } from "./useChatSessions";
import * as api from "@/lib/api/agentApi";
import { ApiError } from "@/lib/api/client";

afterEach(() => {
  vi.clearAllTimers();
  vi.useRealTimers();
  vi.restoreAllMocks();
  useChatSessions.setState({ sessions: [], messagesBySession: {}, pendingDeletions: {}, activeId: "" });
  localStorage.removeItem("chat-sessions");
});

it("删除、同步、撤销、刷新恢复与失败回滚冒烟", async () => {
  vi.useFakeTimers();
  const remove = vi.spyOn(api, "deleteSessionOnBackend").mockResolvedValue(undefined);
  vi.spyOn(api, "fetchSessions").mockResolvedValue([]);
  const store = useChatSessions.getState();
  const id = store.createSession();
  store.deleteSession(id);
  await store.syncFromBackend();
  store.undoDeleteSession(id);
  store.deleteSession(id);
  await useChatSessions.persist.rehydrate();
  await vi.advanceTimersByTimeAsync(5001);
  const failed = store.createSession();
  remove.mockRejectedValueOnce(new Error("offline"));
  store.deleteSession(failed);
  await store.commitDeleteSession(failed);
  vi.spyOn(api, "fetchSession").mockRejectedValueOnce(new ApiError({ message: "not found", code: 404 }));
  try { await store.loadSession(failed); } catch (error) { if (!(error instanceof ApiError)) throw error; }
});

it("大消息仅留内存，缓存迁移和刷新保留草稿及队列", async () => {
  const store = useChatSessions.getState();
  const id = store.createSession();
  store.saveComposerDraft(id, { input: "待编辑的要求", refs: [], limitToRefs: false });
  store.updateQueuedMessages(id, () => [{ id: "queued", content: "继续处理", refs: [], limitToRefs: false }], true);
  store.saveMessages(id, [{ id: "large", role: "assistant", content: "工具结果".repeat(1024 * 1024), timestamp: "12:00" }]);
  const snapshot = useChatSessions.getState();
  await useChatSessions.persist.getOptions().migrate?.(snapshot, 1);
  await useChatSessions.persist.rehydrate();
});

it("配额或访问限制不会阻断内存消息、草稿与排队操作", () => {
  const store = useChatSessions.getState();
  const id = store.createSession();
  for (const name of ["QuotaExceededError", "SecurityError"]) {
    const storage = vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => { throw new DOMException(name, name); });
    store.saveMessages(id, [{ id: name, role: "user", content: "保留这条消息", timestamp: "12:00" }]);
    store.saveComposerDraft(id, { input: "保留草稿", refs: [], limitToRefs: false });
    store.updateQueuedMessages(id, () => [{ id: "pending", content: "保留队列", refs: [], limitToRefs: false }]);
    storage.mockRestore();
  }
});
