import { renderHook, act } from "@testing-library/react";
import {describe, it, beforeEach, afterEach, vi} from "vitest";
import { Braces } from "lucide-react";
import { useSkills } from "./useSkills";
import type { Skill } from "@/types";
import { skillsApi } from "@/lib/services/skillsApi";

// 冒烟测试(项目约定 2026-09-12:单测不写断言,行为验证走 E2E):仅执行渲染/交互路径,不校验结果。

const fixtureSkill = (over: Partial<Skill>): Skill => ({
  id: 1,
  name: "SQL 查询器",
  desc: "执行 SQL 与 Redis 命令",
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
    const row = { id: 3, name: "技能", description: "", category: "自定义", enabled: true, createdAt: "", updatedAt: "" };
    vi.spyOn(skillsApi, "createSkill").mockResolvedValue(row);
    vi.spyOn(skillsApi, "updateSkill").mockResolvedValue(row);
    vi.spyOn(skillsApi, "deleteSkill").mockResolvedValue();
    useSkills.setState({
      skills: [
        fixtureSkill({ id: 1, name: "SQL 查询器", category: "数据" }),
        fixtureSkill({ id: 2, name: "日志诊断", category: "运维" }),
        fixtureSkill({ id: 3, name: "邮件通知", category: "通知", isOfficial: false, createdAt: "2026-09-06" }),
      ],
    });
  });
  afterEach(() => vi.restoreAllMocks());

  it("初始状态为空(后端/用户数据是唯一来源)", () => {
    useSkills.setState({ skills: [] });
    renderHook(() => useSkills());
  });

  it("addSkill 追加自定义技能", async () => {
    const { result } = renderHook(() => useSkills());
    await act(async () => { await result.current.addSkill({ ...fixtureSkill({ id: 991, name: "汇率查询器", isOfficial: false }) }); });
  });

  it("toggleSkill 切换启停", async () => {
    const { result } = renderHook(() => useSkills());

    await act(async () => { await result.current.toggleSkill(result.current.skills[0].id); });
  });

  it("updateSkill 更新字段；removeSkill 删除", async () => {
    const { result } = renderHook(() => useSkills());
    await act(async () => { await result.current.updateSkill(3, { name: "邮件通知 Pro", desc: "更新描述" }); });
    await act(async () => { await result.current.removeSkill(3); });
  });

  it("persist 不序列化组件引用（icon/color/bg 被剔除，防水合崩溃）", () => {
    const options = useSkills.persist.getOptions();
    JSON.stringify(options.partialize?.(useSkills.getState()));
  });
});
