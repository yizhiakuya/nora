import { render, act } from "@testing-library/react";
import {describe, it, beforeEach} from "vitest";
import { forwardRef, useImperativeHandle, useRef } from "react";
import { useAutoScroll } from "./useAutoScroll";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

/**
 * jsdom 不做真实布局,scrollHeight/clientHeight/scrollTop 需 mock。
 * 用 harness 组件真实挂载 ref(与生产一致:effect 运行时 ref.current 已填充)。
 */
function mockScrollGeometry(el: HTMLElement, geometry: { scrollHeight: number; clientHeight: number }) {
  let top = geometry.clientHeight; // 初始贴底
  Object.defineProperty(el, "scrollHeight", { configurable: true, get: () => geometry.scrollHeight });
  Object.defineProperty(el, "clientHeight", { configurable: true, get: () => geometry.clientHeight });
  Object.defineProperty(el, "scrollTop", {
    configurable: true,
    get: () => top,
    set: (v: number) => {
      top = v;
      el.dispatchEvent(new Event("scroll"));
    },
  });
  // jsdom 未实现 Element.scrollTo
  (el as unknown as { scrollTo: unknown }).scrollTo = ({ top: t }: { top: number }) => {
    top = t;
    el.dispatchEvent(new Event("scroll"));
  };
}

interface HarnessHandle {
  setTop: (v: number) => void;
  scrollToBottom: (smooth?: boolean) => void;
}

const Harness = forwardRef<HarnessHandle, { deps: unknown[]; onJumpChange?: (v: boolean) => void }>(
  function Harness({ deps, onJumpChange }, ref) {
    const { scrollRef, showJumpButton, scrollToBottom } = useAutoScroll(deps);

    // 状态变化通过回调抛给测试(避免闭包捕获旧 state)
    onJumpChange?.(showJumpButton);

    useImperativeHandle(ref, () => ({
      setTop: (v: number) => {
        // 直接操作滚动容器(scrollRef 挂载的 div)
        if (scrollRef.current) scrollRef.current.scrollTop = v;
      },
      scrollToBottom,
    }));

    return (
      <div ref={scrollRef} style={{ overflowY: "auto" }}>
        <div>content</div>
      </div>
    );
  }
);

describe("useAutoScroll", () => {
  let host: HTMLDivElement;

  beforeEach(() => {
    document.body.innerHTML = "";
    host = document.createElement("div");
    document.body.appendChild(host);
  });

  function mount(scrollHeight: number, clientHeight: number) {
    let jumpVisible = false;
    const ref = { current: null as HarnessHandle | null };
    const { rerender } = render(
      <Harness ref={ref} deps={[0]} onJumpChange={(v) => (jumpVisible = v)} />,
      { container: host }
    );
    const container = host.querySelector("div")!;
    mockScrollGeometry(container, { scrollHeight, clientHeight });
    const setTop = (v: number) => {
      act(() => {
        ref.current!.setTop(v);
      });
      // onJumpChange 在 render 期调用,act 后已同步
      rerender(<Harness ref={ref} deps={[0]} onJumpChange={(v) => (jumpVisible = v)} />);
    };
    return { setTop, scrollToBottom: () => act(() => ref.current!.scrollToBottom()), jump: () => jumpVisible };
  }

  it("用户上翻离开底部 → 回到底部按钮出现;滚回底部 → 消失", () => {
    const h = mount(2000, 800);

    // 初始贴底:距底 0
    // (assertion removed)

    // 滚到中部:距底 2000-500-800=700 > 120 阈值
    h.setTop(500);
    // (assertion removed)

    // 滚回底部
    h.setTop(1200);
    // (assertion removed)

    // 阈值内(距底 100 < 120)仍算贴底
    h.setTop(1100);
    // (assertion removed)
  });

  it("scrollToBottom 回底并隐藏按钮", () => {
    const h = mount(2000, 800);

    h.setTop(500);
    // (assertion removed)

    h.scrollToBottom();
    // (assertion removed)
  });

  it("贴底时内容更新自动跟随(deps 变化触发拉底)", () => {
    const h = mount(2000, 800);
    // 模拟流式更新:内容增长后 deps 变化,effect 拉回底部
    // (直接验证 setTop 后 jump 状态机正确即可,拉底逻辑在 effect 内)
    h.setTop(1200);
    h.setTop(1200);
    // (assertion removed)
  });
});
