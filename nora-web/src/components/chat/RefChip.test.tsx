import { fireEvent, render, screen } from "@testing-library/react";
import { it } from "vitest";
import { RefChip } from "./RefChip";

it("图片在居中弹窗预览，关闭后可重新打开及移除", () => {
  const view = render(<RefChip chatRef={{ kind: "file", id: 1, name: "截图.png" }} onRemove={() => {}} />);
  fireEvent.click(screen.getByRole("button", { name: "查看图片 截图.png" }));
  fireEvent.click(screen.getByRole("button", { name: "关闭对话框" }));
  fireEvent.click(screen.getByRole("button", { name: "查看图片 截图.png" }));
  fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
  fireEvent.click(screen.getByRole("button", { name: "移除图片 截图.png" }));
  view.unmount();
});
