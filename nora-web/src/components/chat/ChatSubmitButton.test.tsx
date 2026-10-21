import { fireEvent, render, screen } from "@testing-library/react";
import { it, vi } from "vitest";
import { ChatSubmitButton } from "./ChatSubmitButton";

it("renders send, upload, running and stopping states", () => {
  const props = { running: false, stopping: false, uploading: false, hasDraft: false,
    onSend: vi.fn(), onStop: vi.fn() };
  const view = render(<ChatSubmitButton {...props} />);
  view.rerender(<ChatSubmitButton {...props} hasDraft />);
  fireEvent.click(screen.getByRole("button", { name: "发送" }));
  view.rerender(<ChatSubmitButton {...props} uploading />);
  view.rerender(<ChatSubmitButton {...props} running uploading />);
  fireEvent.click(screen.getByRole("button", { name: "停止生成" }));
  view.rerender(<ChatSubmitButton {...props} running stopping />);
  view.rerender(<ChatSubmitButton {...props} running onStop={undefined} />);
  view.unmount();
});
