'use client';

import { useState, useEffect } from "react";
import { Search, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { SkillCard } from "@/components/skills/SkillCard";
import { SkillTabs } from "@/components/skills/SkillTabs";
import { SkillFormModal, SkillFormValues } from "@/components/skills/SkillFormModal";
import { SkillDetailModal } from "@/components/skills/SkillDetailModal";
import { useSkills } from "@/hooks/useSkills";
import { Skill } from "@/types";
import { toast } from "sonner";
import { Braces } from "lucide-react";

/**
 * 技能管理主体(M1-04,2026-09-20):原 AI 能力页主体,抽为组件供
 * 设置页「技能」分组与原 /skills 兼容路由共用。
 */
export function SkillsView() {
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
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <p className="text-xs text-muted-foreground">
            可复用的处理方法：启用后 AI 可按需读取正文执行。技能不授予工具权限（连接与权限在「连接与工具」/「安全」中管理）。
          </p>
          <Button size="sm" className="h-8 text-xs bg-blue-600 dark:bg-blue-500 hover:bg-blue-700 dark:hover:bg-blue-600" onClick={openCreate}>
            <Plus className="w-3.5 h-3.5 mr-1.5" /> 新建技能
          </Button>
        </div>

        <SkillTabs
          activeTab={activeTab}
          onTabChange={setActiveTab}
          searchQuery={searchQuery}
          onSearchChange={setSearchQuery}
        />
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4 animate-in fade-in slide-in-from-bottom-4 duration-500">
        {filteredSkills.map((skill) => (
          <SkillCard key={skill.id} skill={skill} onToggle={toggleSkill} onOpen={setDetail} />
        ))}
      </div>

      {filteredSkills.length === 0 && (
        <div className="py-16 flex flex-col items-center justify-center text-muted-foreground animate-in fade-in">
          <Search className="w-10 h-10 mb-4 opacity-20" />
          <div className="text-sm">没有找到匹配的技能</div>
        </div>
      )}

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
