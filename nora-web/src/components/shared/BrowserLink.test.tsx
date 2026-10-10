import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, it } from "vitest";
import { BrowserLink } from "./BrowserLink";
import { usePreferences } from "@/hooks/usePreferences";

afterEach(() => usePreferences.setState({ linkOpenMode: "internal" }));

it("网页、文件、锚点链接渲染和偏好切换冒烟", () => {
  render(<>
    <BrowserLink href="https://github.com/">GitHub</BrowserLink>
    <BrowserLink href="//github.com/">协议相对链接</BrowserLink>
    <BrowserLink href="?viewer=workspace%3Areports%2Freport.md">工作区文件</BrowserLink>
    <BrowserLink href="#section" onClick={event => event.preventDefault()}>页面锚点</BrowserLink>
  </>);
  fireEvent.click(screen.getByRole("link", { name: "GitHub" }), { ctrlKey: true });
  fireEvent.click(screen.getByRole("link", { name: "页面锚点" }));
  act(() => usePreferences.getState().setLinkOpenMode("external"));
  act(() => usePreferences.getState().setLinkOpenMode("internal"));
});
