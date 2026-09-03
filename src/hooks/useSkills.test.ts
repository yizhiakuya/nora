import { renderHook, act } from "@testing-library/react";
import { describe, it, expect } from "vitest";
import { useSkills } from "./useSkills";
import { Braces } from "lucide-react";
import { Skill } from "@/types";

const customSkill: Skill = {
  id: 991,
  name: "汇率查询器",
  desc: "查询外部实时汇率",
  icon: Braces,
  color: "text-blue-500",
  bg: "bg-blue-100",
  category: "自定义",
  enabled: true,
  isOfficial: false,
  createdAt: "2026-09-03 10:00",
  authType: "API Key",
};

describe("useSkills", () => {
  it("以官方技能播种", () => {
    const { result } = renderHook(() => useSkills());
    expect(result.current.skills.length).toBe(6);
    // 数据设定：6 个内置能力（全部 isOfficial）
    expect(result.current.skills[0].isOfficial).toBe(true);
    expect(result.current.skills.filter((s) => s.isOfficial)).toHaveLength(6);
  });

  it("addSkill 追加自定义技能", () => {
    const { result } = renderHook(() => useSkills());
    act(() => result.current.addSkill(customSkill));
    expect(result.current.skills.length).toBe(7);
    expect(result.current.skills.at(-1)?.name).toBe("汇率查询器");
  });

  it("toggleSkill 切换启停", () => {
    const { result } = renderHook(() => useSkills());
    const before = result.current.skills[0].enabled;
    act(() => result.current.toggleSkill(result.current.skills[0].id));
    expect(result.current.skills[0].enabled).toBe(!before);
  });

  it("updateSkill 更新字段；removeSkill 删除", () => {
    const { result } = renderHook(() => useSkills());
    act(() => result.current.addSkill(customSkill));
    act(() => result.current.updateSkill(991, { name: "汇率查询器 Pro", desc: "更新描述" }));
    expect(result.current.skills.find((s) => s.id === 991)?.name).toBe("汇率查询器 Pro");
    act(() => result.current.removeSkill(991));
    expect(result.current.skills.find((s) => s.id === 991)).toBeUndefined();
  });
});
