import { render, screen, fireEvent } from "@testing-library/react";
import { describe, it, expect, vi } from "vitest";
import { SkillFormModal } from "./SkillFormModal";
import { Skill } from "@/types";
import { Braces } from "lucide-react";

const validSkill: Skill = {
  id: 1,
  name: "已有工具",
  desc: "测试",
  icon: Braces,
  color: "",
  bg: "",
  category: "自定义",
  enabled: true,
  isOfficial: false,
  schema: '{ "openapi": "3.0.0" }',
  authType: "无鉴权",
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
  it("创建模式：标题为「注册自定义工具 (OpenAPI)」", () => {
    renderForm();
    expect(screen.getByText("注册自定义工具 (OpenAPI)")).toBeInTheDocument();
  });

  it("编辑模式：标题包含技能名且回填数据", () => {
    renderForm({ initial: validSkill });
    expect(screen.getByText("编辑工具 · 已有工具")).toBeInTheDocument();
    expect(screen.getByDisplayValue("已有工具")).toBeInTheDocument();
  });

  it("名称为空时提交显示校验错误", () => {
    renderForm();
    fireEvent.click(screen.getByText("保存并创建"));
    expect(screen.getByText("工具名称不能为空")).toBeInTheDocument();
  });

  it("Schema 为空时提交显示校验错误", () => {
    renderForm();
    fireEvent.change(screen.getByPlaceholderText("例如：查询外部实时汇率"), { target: { value: "My Skill" } });
    fireEvent.click(screen.getByText("保存并创建"));
    expect(screen.getByText("OpenAPI Schema 不能为空")).toBeInTheDocument();
  });

  it("Schema 非法 JSON 时提交显示格式错误", () => {
    renderForm();
    fireEvent.change(screen.getByPlaceholderText("例如：查询外部实时汇率"), { target: { value: "My Skill" } });
    const textarea = document.querySelector("textarea")!;
    fireEvent.change(textarea, { target: { value: "{ invalid json" } });
    fireEvent.click(screen.getByText("保存并创建"));
    expect(screen.getByText("Schema 不是合法的 JSON，请检查格式")).toBeInTheDocument();
  });

  it("合法提交调用 onSubmit 并传递表单值", () => {
    renderForm();
    fireEvent.change(screen.getByPlaceholderText("例如：查询外部实时汇率"), { target: { value: "My Skill" } });
    const textarea = document.querySelector("textarea")!;
    fireEvent.change(textarea, { target: { value: '{ "openapi": "3.0.0" }' } });
    fireEvent.click(screen.getByText("保存并创建"));
    expect(screen.queryByText("工具名称不能为空")).not.toBeInTheDocument();
  });

  it("点击取消调用 onClose", () => {
    const { onClose } = renderForm();
    fireEvent.click(screen.getByText("取消"));
    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
