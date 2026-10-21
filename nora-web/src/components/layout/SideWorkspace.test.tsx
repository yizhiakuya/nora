import { act, fireEvent, render, screen } from "@testing-library/react";
import { StrictMode } from "react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, it, vi } from "vitest";
import { SideWorkspace } from "./SideWorkspace";
import { useSideWorkspace } from "@/hooks/useSideWorkspace";

vi.mock("@/hooks/useBrowser", () => ({ useBrowser: () => ({ state: null }) }));
vi.mock("@/hooks/useCommandExecutions", () => ({ useCommandExecutionPolling: () => {} }));

afterEach(() => {
  useSideWorkspace.setState({ isOpen: false, wide: false });
  vi.unstubAllGlobals();
});

it("执行工作区首次懒加载、返回、重复打开与 Escape 关闭路径", async () => {
  vi.stubGlobal("ResizeObserver", class { observe() {} disconnect() {} });
  render(<StrictMode><MemoryRouter><SideWorkspace sessionId="">
    <button onClick={() => useSideWorkspace.getState().show("file")}>打开工作区</button>
  </SideWorkspace></MemoryRouter></StrictMode>);
  const opener = screen.getByRole("button", { name: "打开工作区" });
  opener.focus();
  fireEvent.click(opener);
  fireEvent.click(await screen.findByRole("button", { name: "返回原页面" }));
  await act(async () => { await new Promise<void>(resolve => requestAnimationFrame(() => resolve())); });
  opener.focus();
  fireEvent.click(opener);
  fireEvent.keyDown(await screen.findByRole("complementary", { name: "侧面工作区" }), { key: "Escape" });
  await act(async () => { await new Promise<void>(resolve => requestAnimationFrame(() => resolve())); });
});
