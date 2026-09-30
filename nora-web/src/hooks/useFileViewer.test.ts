import { beforeEach, it, vi } from "vitest";
import { useFileViewer } from "./useFileViewer";
import type { ViewerFile } from "@/types";

const file: ViewerFile = {
  target: "workspace:reports/smoke.md", name: "smoke.md", mimeType: "text/markdown", size: 8,
  modifiedAt: null, version: "1", previewKind: "markdown",
  capabilities: { preview: true, source: true, download: true, edit: true, attach: true },
};
vi.mock("@/lib/services/viewerApi", () => ({ viewerApi: {
  resolve: async () => ({ files: [file], errors: [] }),
  preview: async () => ({ kind: "markdown", text: "# smoke", hash: "hash" }),
  save: async () => {},
} }));

beforeEach(() => useFileViewer.setState({
  active: null, tabs: [], preview: null, isOpen: false, editing: false, saving: false,
  pendingAction: null, pendingReference: null, run: null, newFiles: 0, wide: true, missing: new Set<string>(),
}));

// 单测仅执行冒烟路径；行为验收走 scripts/viewer-e2e.ps1 和真实浏览器。
it("打开、编辑、未保存保护、引用和当前轮次文件事件", async () => {
  const viewer = useFileViewer.getState;
  await viewer().openTargets([file.target]);
  viewer().beginEdit();
  viewer().setDraft("# updated");
  viewer().close();
  useFileViewer.setState({ pendingAction: null });
  await viewer().save();
  viewer().setMode("source");
  viewer().attach("smoke-session");
  viewer().beginRun("smoke-run", "smoke-session");
  viewer().receiveFiles("other-run", "step-1", [file]);
  viewer().receiveFiles("smoke-run", "step-1", [file]);
  await viewer().openTargets([file.target]);
  viewer().receiveFiles("smoke-run", "step-1", [file]);
  viewer().beginEdit();
  viewer().setDraft("# discarded");
  viewer().close();
  viewer().discardAndContinue();
  viewer().openText("历史报告", "# history");
  viewer().close();
});
