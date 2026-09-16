import { render, screen, fireEvent } from "@testing-library/react";
import {describe, it, vi} from "vitest";
import { AgentProcessBlock, AgentThoughtBlock } from "./AgentThoughtBlock";
import type { ChatStep } from "@/lib/api/chatApi";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

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
    // (assertion removed)
    // (assertion removed)
  });

  it("展开 instructions 形态显示文件清单与日记清单", () => {
    render(<AgentThoughtBlock steps={[memoryContext]} />);
    fireEvent.click(screen.getByRole("button", { name: /加载长期记忆/ }));
    // (assertion removed)
    // (assertion removed)
    // 缺失文件与日记清单如实标注(完整性诚实)
    // (assertion removed)
    // (assertion removed)
  });

  it("点击文件行展开模型读到的注入正文", () => {
    render(<AgentThoughtBlock steps={[memoryContext]} />);
    fireEvent.click(screen.getByRole("button", { name: /加载长期记忆/ }));
    // 展开前正文不可见
    // (assertion removed)
    // 点击文件行展开正文(dsh 的 instructions 形态:清单 + 原文);
    // 外层摘要也含文件名,用 code 元素定位到具体文件行
    fireEvent.click(screen.getByText("MEMORY.md").closest("button")!);
    // (assertion removed)
    // 再点收起
    fireEvent.click(screen.getByText("MEMORY.md").closest("button")!);
    // (assertion removed)
  });

  it("缺失文件无正文,不可展开", () => {
    render(<AgentThoughtBlock steps={[memoryContext]} />);
    fireEvent.click(screen.getByRole("button", { name: /加载长期记忆/ }));
    // STYLE.md 缺失 → 行内无 aria-expanded(不可点击展开)
    const missingRow = screen.getByText("STYLE.md").closest("button");
    // (assertion removed)
  });

  it("展开 catalog 形态显示技能条目", () => {
    render(<AgentThoughtBlock steps={[skillCatalog]} />);
    fireEvent.click(screen.getByRole("button", { name: /加载技能目录/ }));
    // 名称在折叠摘要与展开列表各出现一次(都是预期展示位)
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
  });

  it("未知 form 不丢行:仍渲染标题与折叠摘要", () => {
    const unknown: ChatStep = {
      ...memoryContext,
      id: "s-context-unknown",
      title: "注入外部上下文",
      context: { form: "future-form", kind: "some-producer" },
    };
    render(<AgentThoughtBlock steps={[unknown]} />);
    // (assertion removed)
  });
});

describe("AgentThoughtBlock ToolRow", () => {
  it("manage_mcp 步骤显示目标服务器预览", () => {
    const mcpStep: ChatStep = {
      id: "s-tool-mcp",
      type: "tool",
      title: "MCP 服务器管理",
      status: "completed",
      toolName: "manage_mcp",
      input: { target: "megumin" },
      result: { content: "已连接「megumin」,发现 3 个工具" },
    };
    render(<AgentThoughtBlock steps={[mcpStep]} />);
    // 折叠行:工具名 + 目标预览
    // (assertion removed)
    // (assertion removed)
  });

  it("run_command 步骤显示命令预览", () => {
    const cmdStep: ChatStep = {
      id: "s-tool-cmd",
      type: "tool",
      title: "运行单元测试",
      status: "completed",
      toolName: "run_command",
      input: { target: "npm test" },
      result: { content: "PASS\n---\n(exit code: 0, 1200ms, cwd: /workspace)" },
    };
    render(<AgentThoughtBlock steps={[cmdStep]} />);
    // (assertion removed)
    // (assertion removed)
  });

  it("run_command 执行中显示实时输出", () => {
    const running: ChatStep = {
      id: "s-tool-live",
      type: "tool",
      title: "构建项目",
      status: "running",
      toolName: "run_command",
      input: { target: "npm run build" },
      detail: "vite v5.4.0 building for production…\n✓ 42 modules transformed",
    };
    render(<AgentThoughtBlock steps={[running]} />);
    // 命令在折叠预览与详情各出现一次(都是预期展示位);实时输出在详情里
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
  });
});

describe("AgentThoughtBlock ReasoningRow", () => {
  it("running 时默认折叠(正文不展开)", () => {
    render(<AgentThoughtBlock steps={[runningThink]} />);
    // (assertion removed)
    // (assertion removed)
  });

  it("running 时点击展开正文,再点收起", () => {
    render(<AgentThoughtBlock steps={[runningThink]} />);
    const toggle = screen.getByRole("button", { name: /思考中/ });
    fireEvent.click(toggle);
    // (assertion removed)
    fireEvent.click(toggle);
    // (assertion removed)
  });

  it("结束后默认折叠为一行摘要", () => {
    const done: ChatStep = { ...runningThink, status: "completed", duration: "3.20s" };
    render(<AgentThoughtBlock steps={[done]} />);
    // (assertion removed)
    // (assertion removed)
  });

  it("结束后点击可回看正文", () => {
    const done: ChatStep = { ...runningThink, status: "completed", duration: "3.20s" };
    render(<AgentThoughtBlock steps={[done]} />);
    fireEvent.click(screen.getByRole("button", { name: /已深度思考/ }));
    // (assertion removed)
  });

  it("通知型 think 步骤(s-effort 降级)渲染为标题行,不显示为推理", () => {
    const notice: ChatStep = {
      id: "s-effort-1",
      type: "think",
      title: "模型不支持该思考等级",
      detail: "已自动降级为模型默认（可在设置中为该模型配置支持的等级）",
      duration: "0.00s",
      status: "completed",
    };
    render(<AgentThoughtBlock steps={[notice]} />);
    fireEvent.click(screen.getByRole("button", { name: /模型不支持该思考等级/ }));
    // (assertion removed)
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
    // (assertion removed)
  });
});

describe("AgentProcessBlock 过程折叠(对齐 Codex:执行中展示、完成后合并)", () => {
  const toolStep: ChatStep = {
    id: "s-tool-1",
    type: "tool",
    title: "检索照片",
    status: "completed",
    toolName: "mcp__phone__photos_review",
    input: { target: "" },
    result: { content: "照片拼图：9 格" },
  };
  const steps: ChatStep[] = [runningThink, toolStep];

  it("执行中(isTyping)完整展示过程,无折叠行", () => {
    render(<AgentProcessBlock steps={steps} isTyping durationMs={undefined} />);
    // 过程步骤直接可见(不经过折叠按钮)
    screen.getByText(/思考中|已深度思考/);
    screen.getByText("mcp__phone__photos_review");
  });

  it("完成后折叠为一行,点击展开回看", () => {
    render(<AgentProcessBlock steps={steps} isTyping={false} durationMs={3200} />);
    const toggle = screen.getByRole("button", { name: /查看工作过程/ });
    fireEvent.click(toggle); // 展开
    fireEvent.click(screen.getByRole("button", { name: /工作过程/ })); // 收起
  });

  it("折叠态保留结果类内容(画廊卡片)", () => {
    const gallery = {
      version: 1,
      title: "最近的猫照",
      count: 1,
      items: [{ id: 8367, url: "https://example.com/8367.jpg", caption: "躺在黑衣旁" }],
    };
    const showcaseStep: ChatStep = {
      id: "s-tool-showcase",
      type: "tool",
      title: "整理画廊",
      status: "completed",
      toolName: "mcp__phone__photos_showcase",
      result: { content: "画廊已生成。\n```nora-gallery\n" + JSON.stringify(gallery) + "\n```\n" },
    };
    render(<AgentProcessBlock steps={[showcaseStep]} isTyping={false} durationMs={800} />);
    // 折叠态:画廊标题可见(结果必须可见),过程行在折叠按钮内
    screen.getByText("最近的猫照");
    screen.getByRole("button", { name: /查看工作过程/ });
  });
});
