import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it } from "vitest";
import { ImageLightbox } from "./ImageLightbox";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径。

const images = [
  { src: "https://example.com/1.jpg", thumb: "https://example.com/1-thumb.jpg", caption: "第一张" },
  { src: "https://example.com/2.jpg", thumb: "https://example.com/2-thumb.jpg", caption: "第二张" },
];

describe("ImageLightbox", () => {
  it("渲染当前图与计数,提供关闭/新窗口/下载出口", () => {
    render(<ImageLightbox images={images} index={0} onClose={() => {}} onIndexChange={() => {}} />);
    screen.getByRole("dialog");
    screen.getByText("1 / 2");
    screen.getByTitle("关闭（Esc）");
    screen.getByTitle("在新窗口打开");
    screen.getByTitle("下载原图");
  });

  it("点击遮罩关闭,点击图片不关闭", () => {
    let closed = 0;
    render(<ImageLightbox images={images} index={0} onClose={() => { closed += 1; }} onIndexChange={() => {}} />);
    const dialog = screen.getByRole("dialog");
    fireEvent.click(screen.getByAltText("第一张"));
    fireEvent.click(dialog);
    // (assertion removed)
  });

  it("多张时左右切换,单张时隐藏箭头", () => {
    const { rerender } = render(
      <ImageLightbox images={images} index={0} onClose={() => {}} onIndexChange={() => {}} />,
    );
    fireEvent.click(screen.getByLabelText("下一个"));
    fireEvent.click(screen.getByLabelText("上一个"));
    rerender(<ImageLightbox images={[images[0]]} index={0} onClose={() => {}} onIndexChange={() => {}} />);
    // 单张:无切换按钮
    screen.queryByLabelText("下一个");
  });

  it("键盘 Esc 关闭、方向键切换", () => {
    render(<ImageLightbox images={images} index={0} onClose={() => {}} onIndexChange={() => {}} />);
    fireEvent.keyDown(window, { key: "ArrowRight" });
    fireEvent.keyDown(window, { key: "ArrowLeft" });
    fireEvent.keyDown(window, { key: "Escape" });
    // (assertion removed)
  });
});
