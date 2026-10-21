import { fireEvent, render, screen } from "@testing-library/react";
import { it } from "vitest";
import { ContextUsageMeter } from "./ContextUsageMeter";

it("renders empty, small, warning and over-budget usage and opens details", () => {
  const base = { limit: 128_000, windowConfigured: false, estimated: true };
  const view = render(<ContextUsageMeter meter={{ ...base, tokens: 0 }} />);
  for (const tokens of [30, 100_000, 140_000]) {
    view.rerender(<ContextUsageMeter meter={{ ...base, tokens }} />);
  }
  fireEvent.click(screen.getByRole("button", { name: /查看上下文用量/ }));
  fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
  view.rerender(<ContextUsageMeter meter={{ ...base, tokens: 12_000, windowConfigured: true, estimated: false,
    usage: { promptTokens: 12_000, outputTokens: 500, contextWindow: 128_000, roundIndex: 1, source: "upstream" } }} />);
  view.unmount();
});
