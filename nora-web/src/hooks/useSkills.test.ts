import { renderHook, act } from "@testing-library/react";
import { describe, it, expect, beforeEach } from "vitest";
import { Braces } from "lucide-react";
import { useSkills } from "./useSkills";
import type { Skill } from "@/types";

const fixtureSkill = (over: Partial<Skill>): Skill => ({
  id: 1,
  name: "SQL 查询器",
  desc: "执行只读 SQL",
  category: "数据",
  enabled: true,
  isOfficial: true,
  icon: Braces,
  color: "text-blue-500",
  bg: "bg-blue-100",
  ...over,
});

describe("useSkills", () => {
  beforeEach(() => {
    useSkills.setState({
      skills: [
        fixtureSkill({ id: 1, name: "SQL 查询器", category: "数据" }),
        fixtureSkill({ id: 2, name: "日志诊断", category: "运维" }),
        fixtureSkill({ id: 3, name: "邮件通知", category: "通知", isOfficial: false, createdAt: "2026-09-06" }),
      ],
    });
  });

  it("初始状态为空(后端/用户数据是唯一来源)", () => {
    useSkills.setState({ skills: [] });
    const { result } = renderHook(() => useSkills());
    expect(result.current.skills).toEqual([]);
  });

  it("addSkill 追加自定义技能", () => {
    const { result } = renderHook(() => useSkills());
    act(() => result.current.addSkill({ ...fixtureSkill({ id: 991, name: "汇率查询器", isOfficial: false }) }));
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
    act(() => result.current.updateSkill(3, { name: "邮件通知 Pro", desc: "更新描述" }));
    expect(result.current.skills.find((s) => s.id === 3)?.name).toBe("邮件通知 Pro");
    act(() => result.current.removeSkill(3));
    expect(result.current.skills.find((s) => s.id === 3)).toBeUndefined();
  });

  it("persist 不序列化组件引用（icon/color/bg 被剔除，防水合崩溃）", () => {
    const options = useSkills.persist.getOptions();
    const persisted = options.partialize?.(useSkills.getState()) as {
      skills: Record<string, unknown>[];
    };

    expect(persisted.skills.length).toBe(useSkills.getState().skills.length);
    for (const skill of persisted.skills) {
      expect(skill.icon).toBeUndefined();
      expect(skill.color).toBeUndefined();
      expect(skill.bg).toBeUndefined();
    }
  });
});
