'use client';

import { useState } from "react";
import { Header } from "@/components/layout/Header";
import { Zap, Search, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { SkillCard } from "@/components/skills/SkillCard";
import { SkillFormModal, SkillFormValues } from "@/components/skills/SkillFormModal";
import { SkillDetailModal } from "@/components/skills/SkillDetailModal";
import { useSkills } from "@/hooks/useSkills";
import { Skill } from "@/types";
import { toast } from "sonner";
import { Braces } from "lucide-react";

const TABS = ["全部", "计算", "数据", "搜索", "集成", "自定义"];

export default function SkillsPage() {
  const [activeTab, setActiveTab] = useState("全部");
  const [searchQuery, setSearchQuery] = useState("");
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Skill | null>(null);
  const [detail, setDetail] = useState<Skill | null>(null);
  const { skills, addSkill, updateSkill, removeSkill, toggleSkill } = useSkills();

  const filteredSkills = skills.filter((skill) => {
    const matchesTab = activeTab === "全部" || skill.category === activeTab;
    const q = searchQuery.toLowerCase();
    const matchesSearch = skill.name.toLowerCase().includes(q) || skill.desc.toLowerCase().includes(q);
    return matchesTab && matchesSearch;
  });

  const openCreate = () => {
    setEditing(null);
    setFormOpen(true);
  };

  const openEdit = (skill: Skill) => {
    setDetail(null);
    setEditing(skill);
    setFormOpen(true);
  };

  const handleSubmit = (values: SkillFormValues) => {
    if (editing) {
      updateSkill(editing.id, values);
      toast.success(`技能「${values.name}」已更新`);
    } else {
      const newSkill: Skill = {
        id: Date.now(),
        name: values.name,
        desc: "通过 OpenAPI Schema 接入的自定义技能",
        icon: Braces,
        color: "text-blue-500",
        bg: "bg-blue-100",
        category: values.category,
        enabled: true,
        isOfficial: false,
        createdAt: new Date().toISOString().slice(0, 16).replace("T", " "),
        schema: values.schema,
        authType: values.authType,
      };
      addSkill(newSkill);
      toast.success(`技能「${values.name}」创建成功`);
    }
    setFormOpen(false);
    setEditing(null);
  };

  return (
    <>
      <Header
        breadcrumbs={[{ label: "AI 工作台", isCurrent: false }, { label: "技能中心", isCurrent: true }]}
        actions={
          <Button size="sm" className="h-8 text-xs bg-blue-600 hover:bg-blue-700" onClick={openCreate}>
            <Plus className="w-3.5 h-3.5 mr-1.5" /> 创建自定义技能
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-6 bg-[#f4f5f7]">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="flex flex-col mb-6 space-y-4 animate-in fade-in slide-in-from-top-4">
            <div>
              <h1 className="text-xl font-bold text-gray-800 flex items-center gap-2">
                <Zap className="w-5 h-5 text-yellow-500" /> 技能中心 (Skills/Tools)
              </h1>
              <p className="text-xs text-gray-500 mt-1">管理并创建工具集，为你的 AI 智能体赋予行动能力。</p>
            </div>

            <div className="flex items-center justify-between">
              <div className="flex gap-1 p-1 bg-gray-200/50 rounded-lg">
                {TABS.map((tab) => (
                  <div
                    key={tab}
                    onClick={() => setActiveTab(tab)}
                    className={`px-4 py-1.5 text-xs font-medium rounded-md cursor-pointer transition-all ${activeTab === tab ? "bg-white text-gray-800 shadow-sm" : "text-gray-500 hover:text-gray-700 hover:bg-gray-200/50"}`}
                  >
                    {tab}
                  </div>
                ))}
              </div>

              <div className="relative">
                <Search className="absolute left-2.5 top-1/2 transform -translate-y-1/2 text-gray-400 w-3 h-3" />
                <Input
                  placeholder="搜索技能名称或描述..."
                  className="pl-7 pr-3 py-1.5 h-8 bg-white border-gray-200 text-xs shadow-sm w-64 focus-visible:ring-1 focus-visible:ring-blue-500"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                />
              </div>
            </div>
          </div>

          <div className="grid grid-cols-3 gap-5 animate-in fade-in slide-in-from-bottom-4 duration-500">
            {filteredSkills.map((skill) => (
              <SkillCard key={skill.id} skill={skill} onToggle={toggleSkill} onOpen={setDetail} />
            ))}
          </div>

          {filteredSkills.length === 0 && (
            <div className="py-20 flex flex-col items-center justify-center text-gray-400 animate-in fade-in">
              <Search className="w-10 h-10 mb-4 opacity-20" />
              <div className="text-sm">没有找到匹配的技能</div>
            </div>
          )}
        </div>
      </div>

      <SkillFormModal
        isOpen={formOpen}
        onClose={() => { setFormOpen(false); setEditing(null); }}
        initial={editing}
        onSubmit={handleSubmit}
      />
      <SkillDetailModal
        skill={detail}
        onClose={() => setDetail(null)}
        onEdit={openEdit}
        onDelete={(skill) => removeSkill(skill.id)}
        onToggle={toggleSkill}
      />
    </>
  );
}
