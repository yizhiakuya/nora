import { act, fireEvent, render, screen, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, it, vi } from "vitest";
import { Sidebar } from "./Sidebar";
import { NavigationToggle } from "./NavigationToggle";
import { useSidebarStore } from "@/hooks/useSidebar";

vi.mock("@/hooks/useChatSessions", () => {
  const state = { sessions: [], activeId: "", setActive: vi.fn(), createSession: vi.fn(), syncFromBackend: async () => {} };
  return { useChatSessions: (select: (value: typeof state) => unknown) => select(state) };
});

afterEach(() => {
  useSidebarStore.setState({ isOpen: false, isCollapsed: false });
  vi.unstubAllGlobals();
});

it("执行手机导航打开、Escape、路由关闭与切回桌面的路径", async () => {
  const media = new EventTarget();
  const query = Object.assign(media, { matches: false });
  vi.stubGlobal("matchMedia", () => query);
  render(<MemoryRouter><Sidebar /><NavigationToggle /></MemoryRouter>);
  const toggle = screen.getByRole("button", { name: "切换导航" });
  toggle.focus();
  fireEvent.click(toggle);
  fireEvent.keyDown(await screen.findByRole("dialog"), { key: "Escape" });
  await act(async () => {});
  fireEvent.click(toggle);
  fireEvent.click(within(await screen.findByRole("dialog")).getByRole("link", { name: "助手" }));
  await act(async () => {});
  fireEvent.click(toggle);
  fireEvent.click(within(await screen.findByRole("dialog")).getByRole("link", { name: "资料" }));
  await act(async () => {});
  fireEvent.click(toggle);
  await screen.findByRole("dialog");
  act(() => { query.matches = true; media.dispatchEvent(new Event("change")); });
  await act(async () => {});
});
