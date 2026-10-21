import { fireEvent, render, screen } from "@testing-library/react";
import { it } from "vitest";
import type { ChatStep } from "@/lib/api/chatApi";
import { ContextRow } from "./ContextStepRow";
import { CompactionMessage } from "./CompactionMessage";

it("直接展开整理正文，兼顾摘要、在途状态和旧记录", () => {
  const step: ChatStep = { id: "s-compaction", type: "context", title: "整理上下文", status: "completed",
    detail: "已缩短早期工具输出", context: { form: "compaction", kind: "microcompaction", content: "保留的实际正文" } };
  const view = render(<ContextRow step={step} />);
  fireEvent.click(screen.getByRole("button", { name: "整理上下文" }));
  view.rerender(<ContextRow step={{ ...step, context: { ...step.context, kind: "compaction", content: "模型生成的工作摘要" } }} />);
  view.rerender(<ContextRow step={{ ...step, status: "running", context: undefined }} />);
  view.unmount();
  const message = render(<CompactionMessage msg={{ id: "compact", role: "assistant", sender: "compaction", content: "/compact", timestamp: "", steps: [step] }} />);
  fireEvent.click(screen.getByRole("button", { name: "上下文已压缩" }));
  message.unmount();
});
