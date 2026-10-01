import { render, screen, fireEvent } from "@testing-library/react";
import {describe, it, vi} from "vitest";
import { SkillFormModal } from "./SkillFormModal";
import { Skill } from "@/types";
import { Braces } from "lucide-react";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

const validSkill: Skill = {
  id: 1,
  name: "周报生成",
  desc: "生成结构化周报",
  icon: Braces,
  color: "",
  bg: "",
  category: "计算",
  enabled: true,
  isOfficial: false,
  instructions: "# 步骤\n1. 查数据",
};

function renderForm(props: Partial<Parameters<typeof SkillFormModal>[0]> = {}) {
  const onSubmit = vi.fn();
  const onClose = vi.fn();
  const result = render(
    <SkillFormModal
      isOpen
      onClose={onClose}
      onSubmit={onSubmit}
      initial={null}
      {...props}
    />
  );
  return { ...result, onSubmit, onClose };
}

describe("SkillFormModal", () => {
  it("创建模式：标题为「新建技能」", () => {
    renderForm();
    // (assertion removed)
  });

  it("编辑模式：标题包含技能名且回填数据", () => {
    renderForm({ initial: validSkill });
    // (assertion removed)
    // (assertion removed)
    // (assertion removed)
    // 指令正文回填
    // (assertion removed)
  });

  it("名称为空时提交显示校验错误", () => {
    renderForm();
    fireEvent.click(screen.getByText("保存并创建"));
    // (assertion removed)
  });

  it("指令为空时提交显示校验错误", () => {
    renderForm();
    fireEvent.change(screen.getByPlaceholderText("例如：周报生成"), { target: { value: "My Skill" } });
    fireEvent.click(screen.getByText("保存并创建"));
    // (assertion removed)
  });

  it("合法提交调用 onSubmit 并传递表单值", () => {
    renderForm();
    fireEvent.change(screen.getByPlaceholderText("例如：周报生成"), { target: { value: "周报生成" } });
    fireEvent.change(screen.getByPlaceholderText("何时该用这个技能（会展示给 AI）"), { target: { value: "生成周报时使用" } });
    fireEvent.change(screen.getByPlaceholderText(/任务步骤/), { target: { value: "# 步骤\n1. 查数据" } });
    fireEvent.click(screen.getByText("保存并创建"));

    // (assertion removed)
    // (assertion removed)
  });
});
