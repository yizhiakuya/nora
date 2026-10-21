import { act, renderHook } from "@testing-library/react";
import { afterEach, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";
import { toast } from "sonner";
import { useDeleteChatSession } from "./useDeleteChatSession";
import { useChatSessions } from "./useChatSessions";

afterEach(() => {
  for (const id of Object.keys(useChatSessions.getState().pendingDeletions)) useChatSessions.getState().undoDeleteSession(id);
  useChatSessions.setState({ sessions: [], messagesBySession: {}, activeId: "", pendingDeletions: {} });
  vi.restoreAllMocks();
});

it("当前会话删除与撤销共用路由入口，非当前会话独立删除", () => {
  const id = useChatSessions.getState().createSession();
  const success = vi.spyOn(toast, "success");
  const { result, unmount } = renderHook(() => useDeleteChatSession(), {
    wrapper: ({ children }) => <MemoryRouter initialEntries={[`/chat?session=${id}`]}>{children}</MemoryRouter>,
  });
  act(() => result.current(id));
  const action = success.mock.calls.at(-1)?.[1]?.action;
  if (action && typeof action === "object" && "onClick" in action) act(() => action.onClick({} as React.MouseEvent<HTMLButtonElement>));
  useChatSessions.setState(state => ({ sessions: [...state.sessions, { id: "other", title: "other", updatedAt: Date.now() }] }));
  act(() => result.current("other"));
  unmount();
});
