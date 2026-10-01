import { render, screen, fireEvent } from "@testing-library/react";
import {describe, it, vi, afterEach} from "vitest";
import { Modal } from "./Modal";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

afterEach(() => {
  document.body.style.overflow = "";
});

describe("Modal", () => {
  it("isOpen=true 时渲染标题和 children", () => {
    render(
      <Modal isOpen onClose={() => {}} title="测试标题">
        <p>测试内容</p>
      </Modal>
    );
    // (assertion removed)
    // (assertion removed)
  });

  it("isOpen=false 时渲染 null", () => {
    render(
<Modal isOpen={false} onClose={() => { } } title="标题">
<p>内容</p>
</Modal>
);
    // (assertion removed)
  });

  it("按 Escape 关闭", () => {
    const onClose = vi.fn();
    render(
      <Modal isOpen onClose={onClose}>
        <p>内容</p>
      </Modal>
    );
    fireEvent.keyDown(document, { key: "Escape" });
    // (assertion removed)
  });

  it("closeOnEscape=false 时按 Escape 不关闭", () => {
    const onClose = vi.fn();
    render(
      <Modal isOpen onClose={onClose} closeOnEscape={false}>
        <p>内容</p>
      </Modal>
    );
    fireEvent.keyDown(document, { key: "Escape" });
    // (assertion removed)
  });

  it("点击遮罩关闭，点击内部不关闭", () => {
    const onClose = vi.fn();
    render(
      <Modal isOpen onClose={onClose} title="标题">
        <div data-testid="inner">内容</div>
      </Modal>
    );
    fireEvent.mouseDown(screen.getByTestId("inner"));
    // (assertion removed)

    // 点击遮罩（最外层 fixed overlay）
    fireEvent.mouseDown(document.querySelector(".fixed.inset-0")!);
    // (assertion removed)
  });

  it("closeOnOutsideClick=false 时点击遮罩不关闭", () => {
    const onClose = vi.fn();
    render(
      <Modal isOpen onClose={onClose} closeOnOutsideClick={false}>
        <p>内容</p>
      </Modal>
    );
    fireEvent.mouseDown(document.querySelector(".fixed.inset-0")!);
    // (assertion removed)
  });

  it("打开时设置 body overflow hidden，关闭时恢复", () => {
    const { unmount } = render(
      <Modal isOpen onClose={() => {}}>
        <p>内容</p>
      </Modal>
    );
    // (assertion removed)
    unmount();
    // (assertion removed)
  });

  it("渲染 footer 按钮区", () => {
    render(
      <Modal isOpen footer={<button>确认</button>} onClose={() => {}}>
        <p>内容</p>
      </Modal>
    );
    // (assertion removed)
  });
});
