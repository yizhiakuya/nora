import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, it, vi } from "vitest";
import { fetchProjectDirectories } from "@/lib/api/agentApi";
import { SessionProjectDialog } from "./SessionProjectDialog";

vi.mock("@/lib/api/agentApi", () => ({ fetchProjectDirectories: vi.fn() }));
afterEach(() => vi.resetAllMocks());

it("browses, filters, selects and recovers from a directory error", async () => {
  vi.mocked(fetchProjectDirectories).mockResolvedValue({ path: "/work", parent: "/", defaultRoot: "/work", roots: ["/"],
    directories: [{ name: "project", path: "/work/project" }], truncated: false });
  const view = render(<SessionProjectDialog sessionId="smoke" project={{ projectRoot: "/work", platform: "Linux", environment: "Agent", rules: [] }}
    disabled={false} saving={false} onClose={() => undefined} onSave={() => undefined} />);
  await act(async () => {});
  fireEvent.change(screen.getByRole("textbox", { name: "筛选当前目录" }), { target: { value: "project" } });
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "打开目录 project" })));
  fireEvent.click(screen.getByRole("button", { name: "使用此目录" }));
  vi.mocked(fetchProjectDirectories).mockRejectedValueOnce(new Error("目录不可读"));
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "返回上级目录" })));
  await act(async () => fireEvent.click(screen.getByRole("button", { name: "重试" })));
  fireEvent.click(screen.getByRole("button", { name: "取消" }));
  view.unmount();
});
