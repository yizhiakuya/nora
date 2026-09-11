import { render, screen, fireEvent } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { AgentThoughtBlock } from "./AgentThoughtBlock";
import type { ChatStep } from "@/lib/api/chatApi";

/** running 的推理步骤:流式期间每个 reasoning_delta 都会触发重渲染 */
const runningThink: ChatStep = {
  id: "s-reasoning-0",
  type: "think",
  title: "推理过程",
  detail: "用户想要检查服务器状态…",
  status: "running",
};

/** 注入上下文步骤(instructions 形态:工作区引导文件) */
const memoryContext: ChatStep = {
  id: "s-context-memory",
  type: "context",
  title: "加载长期记忆",
  detail: "注入 4 个文件 + 2 篇日记清单",
  status: "completed",
  roundIndex: 0,
  context: {
    form: "instructions",
    kind: "workspace-bootstrap",
    files: [
      { path: "SOUL.md", bytes: 342, content: "# 我的身份\n\n我是 Nora——这个工作台里的 AI 助手。" },
      { path: "AGENTS.md", bytes: 822, content: "# 使用约定\n\n## 记忆维护" },
      { path: "USER.md", bytes: 118, content: "# 用户画像\n\n- 偏好中文回答" },
      { path: "MEMORY.md", bytes: 1020, content: "# 长期记忆\n\n## SSH MCP 服务器 = megumin 本机" },
      { path: "STYLE.md", bytes: 0, missing: true },
    ],
    dailyNotes: ["memory/2026-09-10.md", "memory/2026-09-08.md"],
  },
};

/** 注入上下文步骤(catalog 形态:技能目录) */
const skillCatalog: ChatStep = {
  id: "s-context-skills",
  type: "context",
  title: "加载技能目录",
  detail: "启用 1 个技能（正文按需读取）",
  status: "completed",
  roundIndex: 0,
  context: {
    form: "catalog",
    kind: "skill-catalog",
    entries: [{ name: "周报生成", description: "按模板生成周报", category: "计算" }],
  },
};

describe("AgentThoughtBlock ContextRow", () => {
  it("默认折叠为一行,展开前正文不可见", () => {
    render(<AgentThoughtBlock steps={[memoryContext]} />);
    expect(screen.getByText("加载长期记忆")).toBeTruthy();
    expect(screen.queryByText("注入文件（每轮自动带入模型上下文）")).toBeNull();
  });

  it("展开 instructions 形态显示文件清单与日记清单", () => {
    render(<AgentThoughtBlock steps={[memoryContext]} />);
    fireEvent.click(screen.getByRole("button", { name: /加载长期记忆/ }));
    expect(screen.getByText("SOUL.md")).toBeTruthy();
    expect(screen.getByText("MEMORY.md")).toBeTruthy();
    // 缺失文件与日记清单如实标注(完整性诚实)
    expect(screen.getByText(/缺失（占位注入）/)).toBeTruthy();
    expect(screen.getByText(/memory\/2026-09-10\.md/)).toBeTruthy();
  });

  it("点击文件行展开模型读到的注入正文", () => {
    render(<AgentThoughtBlock steps={[memoryContext]} />);
    fireEvent.click(screen.getByRole("button", { name: /加载长期记忆/ }));
    // 展开前正文不可见
    expect(screen.queryByText(/我是 Nora——这个工作台里的 AI 助手/)).toBeNull();
    // 点击文件行展开正文(dsh 的 instructions 形态:清单 + 原文);
    // 外层摘要也含文件名,用 code 元素定位到具体文件行
    fireEvent.click(screen.getByText("MEMORY.md").closest("button")!);
    expect(screen.getByText(/SSH MCP 服务器 = megumin 本机/)).toBeTruthy();
    // 再点收起
    fireEvent.click(screen.getByText("MEMORY.md").closest("button")!);
    expect(screen.queryByText(/SSH MCP 服务器 = megumin 本机/)).toBeNull();
  });

  it("缺失文件无正文,不可展开", () => {
    render(<AgentThoughtBlock steps={[memoryContext]} />);
    fireEvent.click(screen.getByRole("button", { name: /加载长期记忆/ }));
    // STYLE.md 缺失 → 行内无 aria-expanded(不可点击展开)
    const missingRow = screen.getByText("STYLE.md").closest("button");
    expect(missingRow?.getAttribute("aria-expanded")).toBeNull();
  });

  it("展开 catalog 形态显示技能条目", () => {
    render(<AgentThoughtBlock steps={[skillCatalog]} />);
    fireEvent.click(screen.getByRole("button", { name: /加载技能目录/ }));
    // 名称在折叠摘要与展开列表各出现一次(都是预期展示位)
    expect(screen.getAllByText("周报生成").length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText(/按模板生成周报/)).toBeTruthy();
    expect(screen.getByText("[计算]")).toBeTruthy();
  });

  it("未知 form 不丢行:仍渲染标题与折叠摘要", () => {
    const unknown: ChatStep = {
      ...memoryContext,
      id: "s-context-unknown",
      title: "注入外部上下文",
      context: { form: "future-form", kind: "some-producer" },
    };
    render(<AgentThoughtBlock steps={[unknown]} />);
    expect(screen.getByText("注入外部上下文")).toBeTruthy();
  });
});

describe("AgentThoughtBlock ReasoningRow", () => {
  it("running 时默认折叠(正文不展开)", () => {
    render(<AgentThoughtBlock steps={[runningThink]} />);
    expect(screen.getByText("思考中…")).toBeTruthy();
    expect(screen.queryByText(/用户想要检查服务器状态/)).toBeNull();
  });

  it("running 时点击展开正文,再点收起", () => {
    render(<AgentThoughtBlock steps={[runningThink]} />);
    const toggle = screen.getByRole("button", { name: /思考中/ });
    fireEvent.click(toggle);
    expect(screen.getByText(/用户想要检查服务器状态/)).toBeTruthy();
    fireEvent.click(toggle);
    expect(screen.queryByText(/用户想要检查服务器状态/)).toBeNull();
  });

  it("结束后默认折叠为一行摘要", () => {
    const done: ChatStep = { ...runningThink, status: "completed", duration: "3.20s" };
    render(<AgentThoughtBlock steps={[done]} />);
    expect(screen.getByText("已深度思考")).toBeTruthy();
    expect(screen.queryByText(/用户想要检查服务器状态/)).toBeNull();
  });

  it("结束后点击可回看正文", () => {
    const done: ChatStep = { ...runningThink, status: "completed", duration: "3.20s" };
    render(<AgentThoughtBlock steps={[done]} />);
    fireEvent.click(screen.getByRole("button", { name: /已深度思考/ }));
    expect(screen.getByText(/用户想要检查服务器状态/)).toBeTruthy();
  });

  it("流式中折叠后 detail 仍持续累积,展开可见全文", () => {
    const { rerender } = render(<AgentThoughtBlock steps={[runningThink]} />);
    const toggle = screen.getByRole("button", { name: /思考中/ });
    fireEvent.click(toggle); // 展开
    fireEvent.click(toggle); // 收起
    // 流式推进:detail 增长、仍为 running
    const grown: ChatStep = {
      ...runningThink,
      detail: runningThink.detail + "后来又想到了新的线索。",
    };
    rerender(<AgentThoughtBlock steps={[grown]} />);
    // 默认仍折叠;点开后能看到累积的完整内容
    fireEvent.click(screen.getByRole("button", { name: /思考中/ }));
    expect(screen.getByText(/后来又想到了新的线索/)).toBeTruthy();
  });
});
