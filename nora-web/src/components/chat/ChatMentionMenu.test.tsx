import { render, fireEvent, cleanup } from "@testing-library/react";
import { afterEach, it } from "vitest";
import { CHAT_COMMANDS } from "@/lib/chatCommands";
import { ChatMentionMenu, type MentionItem } from "./ChatMentionMenu";

afterEach(cleanup);

it("命令与引用候选的渲染、悬停和补全冒烟", () => {
  const items: MentionItem[] = CHAT_COMMANDS.map(command => ({ kind: "command", name: command.name, hint: command.description, command }));
  items.push({ kind: "skill", id: 1, name: "浏览器资料收集", hint: "技能" }, { kind: "mcp", id: 2, name: "context7", hint: "MCP · 2 个工具" });
  const { container, rerender } = render(<ChatMentionMenu items={items} selected={0} title="Agent 命令" onSelect={() => {}} onHover={() => {}} />);
  for (const button of Array.from(container.querySelectorAll("button"))) {
    fireEvent.mouseEnter(button);
    fireEvent.mouseDown(button);
  }
  rerender(<ChatMentionMenu items={items.slice(-2)} selected={1} title="技能与工具" onSelect={() => {}} onHover={() => {}} />);
});
