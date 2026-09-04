import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi, afterEach } from "vitest";
import { Modal } from "./Modal";

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
    expect(screen.getByText("测试标题")).toBeInTheDocument();
    expect(screen.getByText("测试内容")).toBeInTheDocument();
  });

  it("isOpen=false 时渲染 null", () => {
    const { container } = render(
      <Modal isOpen={false} onClose={() => {}} title="标题">
        <p>内容</p>
      </Modal>
    );
    expect(container.innerHTML).toBe("");
  });

  it("按 Escape 关闭", () => {
    const onClose = vi.fn();
    render(
      <Modal isOpen onClose={onClose}>
        <p>内容</p>
      </Modal>
    );
    fireEvent.keyDown(document, { key: "Escape" });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("closeOnEscape=false 时按 Escape 不关闭", () => {
    const onClose = vi.fn();
    render(
      <Modal isOpen onClose={onClose} closeOnEscape={false}>
        <p>内容</p>
      </Modal>
    );
    fireEvent.keyDown(document, { key: "Escape" });
    expect(onClose).not.toHaveBeenCalled();
  });

  it("点击遮罩关闭，点击内部不关闭", () => {
    const onClose = vi.fn();
    render(
      <Modal isOpen onClose={onClose} title="标题">
        <div data-testid="inner">内容</div>
      </Modal>
    );
    fireEvent.mouseDown(screen.getByTestId("inner"));
    expect(onClose).not.toHaveBeenCalled();

    // 点击遮罩（最外层 fixed overlay）
    fireEvent.mouseDown(document.querySelector(".fixed.inset-0")!);
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it("closeOnOutsideClick=false 时点击遮罩不关闭", () => {
    const onClose = vi.fn();
    render(
      <Modal isOpen onClose={onClose} closeOnOutsideClick={false}>
        <p>内容</p>
      </Modal>
    );
    fireEvent.mouseDown(document.querySelector(".fixed.inset-0")!);
    expect(onClose).not.toHaveBeenCalled();
  });

  it("打开时设置 body overflow hidden，关闭时恢复", () => {
    const { unmount } = render(
      <Modal isOpen onClose={() => {}}>
        <p>内容</p>
      </Modal>
    );
    expect(document.body.style.overflow).toBe("hidden");
    unmount();
    expect(document.body.style.overflow).not.toBe("hidden");
  });

  it("渲染 footer 按钮区", () => {
    render(
      <Modal isOpen footer={<button>确认</button>} onClose={() => {}}>
        <p>内容</p>
      </Modal>
    );
    expect(screen.getByText("确认")).toBeInTheDocument();
  });
});
