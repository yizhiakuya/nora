'use client';

import { useState, useEffect } from "react";
import { Search, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Tabs, TabsContent } from "@/components/ui/tabs";
import { SkillCard } from "@/components/skills/SkillCard";
import { SkillTabs } from "@/components/skills/SkillTabs";
import { SkillFormModal, SkillFormValues } from "@/components/skills/SkillFormModal";
import { SkillDetailModal } from "@/components/skills/SkillDetailModal";
import { useSkills } from "@/hooks/useSkills";
import { Skill } from "@/types";
import { toast } from "sonner";

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
    const matchesTab = activeTab === "全部"
      || (activeTab === "内置" ? skill.isOfficial : activeTab === "自定义" ? !skill.isOfficial : skill.category === activeTab);
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

  const handleSubmit = async (values: SkillFormValues) => {
    if (editing) {
      await updateSkill(editing.id, {
        name: values.name,
        desc: values.description,
        instructions: values.instructions,
        category: values.category,
      });
      toast.success(`技能「${values.name}」已更新`);
    } else {
      await addSkill({
        name: values.name,
        desc: values.description,
        category: values.category,
        instructions: values.instructions,
      });
      toast.success(`技能「${values.name}」创建成功`);
    }
    setFormOpen(false);
    setEditing(null);
  };

  return (
    <>
      <Tabs value={activeTab} onValueChange={setActiveTab} className="space-y-6">
        <div className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-3">
            <p className="text-sm leading-relaxed text-muted-foreground max-w-xl">
              把常用处理方法保存成技能，供 Nora 在任务中使用。启用技能不会改变工具权限。
            </p>
            <Button size="sm" className="shrink-0" onClick={openCreate}>
              <Plus className="w-3.5 h-3.5 mr-1.5" /> 新建技能
            </Button>
          </div>

          <SkillTabs searchQuery={searchQuery} onSearchChange={setSearchQuery} />
        </div>
        <TabsContent value={activeTab}>
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(100%,250px),1fr))] gap-4">
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
        </TabsContent>
      </Tabs>

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
