'use client';

import { useState, useEffect } from "react";
import { Header } from "@/components/layout/Header";
import { Zap, Search, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { SkillCard } from "@/components/skills/SkillCard";
import { SkillTabs } from "@/components/skills/SkillTabs";
import { SkillFormModal, SkillFormValues } from "@/components/skills/SkillFormModal";
import { SkillDetailModal } from "@/components/skills/SkillDetailModal";
import { useSkills } from "@/hooks/useSkills";
import { Skill } from "@/types";
import { toast } from "sonner";
import { Braces } from "lucide-react";

export default function SkillsPage() {
  const [activeTab, setActiveTab] = useState("全部");
  const [searchQuery, setSearchQuery] = useState("");
  const [formOpen, setFormOpen] = useState(false);
  const [editing, setEditing] = useState<Skill | null>(null);
  const [detail, setDetail] = useState<Skill | null>(null);
  const { skills, addSkill, updateSkill, removeSkill, toggleSkill } = useSkills();
  const syncFromBackend = useSkills((s) => s.syncFromBackend);

  // 后端模式:进入页面拉取服务端技能(agent 也可能在对话中创建技能)
  useEffect(() => {
    void syncFromBackend();
  }, [syncFromBackend]);

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
      updateSkill(editing.id, {
        name: values.name,
        desc: values.description,
        instructions: values.instructions,
        category: values.category,
      });
      toast.success(`技能「${values.name}」已更新`);
    } else {
      const newSkill: Skill = {
        id: Date.now(),
        name: values.name,
        desc: values.description || "自定义技能",
        icon: Braces,
        color: "text-blue-500 dark:text-blue-400",
        bg: "bg-blue-100 dark:bg-blue-900/50",
        category: values.category,
        enabled: true,
        isOfficial: false,
        createdAt: new Date().toISOString().slice(0, 16).replace("T", " "),
        instructions: values.instructions,
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
        breadcrumbs={[{ label: "工作台", isCurrent: false }, { label: "AI 能力", isCurrent: true }]}
        actions={
          <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={openCreate}>
            <Plus className="w-3.5 h-3.5 mr-1.5" /> 新建技能
          </Button>
        }
      />

      <div className="flex-1 overflow-y-auto custom-scroll p-6 bg-background">
        <div className="max-w-6xl mx-auto space-y-6 pb-20">
          <div className="flex flex-col mb-6 space-y-4 animate-in fade-in slide-in-from-top-4">
            <div>
              <h1 className="text-xl font-bold text-foreground flex items-center gap-2">
                <Zap className="w-5 h-5 text-yellow-500 dark:text-yellow-400" /> AI 能力
              </h1>
              <p className="text-xs text-muted-foreground mt-1">定义 AI 在本工作台能做什么：启停技能（可复用的任务指令）与内置工具。对话中也能让 AI 把流程沉淀成技能。</p>
            </div>

            <SkillTabs
              activeTab={activeTab}
              onTabChange={setActiveTab}
              searchQuery={searchQuery}
              onSearchChange={setSearchQuery}
            />
          </div>

          <div className="grid grid-cols-3 gap-5 animate-in fade-in slide-in-from-bottom-4 duration-500">
            {filteredSkills.map((skill) => (
              <SkillCard key={skill.id} skill={skill} onToggle={toggleSkill} onOpen={setDetail} />
            ))}
          </div>

          {filteredSkills.length === 0 && (
            <div className="py-20 flex flex-col items-center justify-center text-muted-foreground animate-in fade-in">
              <Search className="w-10 h-10 mb-4 opacity-20" />
              <div className="text-sm">没有找到匹配的工具</div>
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
