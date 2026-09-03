'use client';

import { useState } from "react";
import { Bot, Wand2, Plus } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { AgentSkillsModal } from "@/components/agents/AgentSkillsModal";
import { useSkills } from "@/hooks/useSkills";

export function AgentConfigPanel() {
  const { skills, toggleSkill } = useSkills();
  const [skillsModalOpen, setSkillsModalOpen] = useState(false);
  const enabledSkills = skills.filter((s) => s.enabled);

  return (
    <div className="w-[55%] border-r border-gray-200 bg-white flex flex-col h-full">
      <div className="flex-1 overflow-y-auto p-6 custom-scroll space-y-8">
        {/* Basic Info */}
        <div>
          <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider mb-4">基本信息 (Identity)</h3>
          <div className="flex items-start gap-4">
            <div className="w-16 h-16 bg-gradient-to-br from-blue-500 to-indigo-600 rounded-2xl flex items-center justify-center text-white shadow-sm flex-shrink-0 border-4 border-blue-50 cursor-pointer hover:scale-105 transition-transform">
              <Bot className="w-8 h-8" />
            </div>
            <div className="flex-1 space-y-3">
              <input type="text" defaultValue="数据分析专家" className="w-full text-lg font-bold text-gray-800 bg-transparent border-b border-dashed border-gray-300 pb-1 focus:outline-none focus:border-blue-500 transition-colors" />
              <textarea rows={2} className="w-full text-sm text-gray-500 bg-transparent resize-none focus:outline-none focus:text-gray-700 transition-colors" defaultValue="精通 SQL、Python 与商业数据可视化，能够快速从杂乱的数据中提取核心洞察。"></textarea>
            </div>
          </div>
        </div>

        {/* Prompt Editor */}
        <div>
          <div className="flex items-center justify-between mb-2">
            <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">人设与系统指令 (System Prompt)</h3>
            <Button variant="ghost" size="sm" className="h-6 text-[10px] text-blue-600 hover:bg-blue-50"><Wand2 className="w-3 h-3 mr-1" /> AI 润色</Button>
          </div>
          <div className="bg-[#1e1e1e] rounded-xl p-4 border border-gray-800 shadow-inner relative group focus-within:ring-2 focus-within:ring-blue-500 transition-all">
            <textarea rows={6} className="w-full bg-transparent text-gray-300 font-mono text-[13px] leading-relaxed resize-none focus:outline-none custom-scroll" defaultValue={"你是一个顶级的数据分析师。\n你的主要目标是：\n1. 接收用户的自然语言查询\n2. 将其转化为精准的 SQL 语句\n3. 执行查询并用可交互的图表展示结果\n\n注意：在回答结论时，永远使用“总分总”结构。"}></textarea>
          </div>
        </div>

        {/* Skills/Tools —— 与技能中心共用 useSkills 数据源 */}
        <div>
          <div className="flex items-center justify-between mb-3">
            <h3 className="text-xs font-bold text-gray-500 uppercase tracking-wider">技能与工具 (Skills & Tools)</h3>
            <Button variant="outline" size="sm" className="h-6 text-[10px] px-2 hover:bg-gray-50" onClick={() => setSkillsModalOpen(true)}>
              <Plus className="w-3 h-3 mr-1" /> 添加技能
            </Button>
          </div>
          <div className="space-y-2">
            {enabledSkills.map((skill) => {
              const Icon = skill.icon;
              return (
                <div key={skill.id} className="flex items-center justify-between p-3 border border-gray-200 rounded-lg hover:border-blue-300 transition-colors bg-gray-50/50">
                  <div className="flex items-center gap-3">
                    <div className={`w-8 h-8 rounded-lg flex items-center justify-center ${skill.bg} ${skill.color}`}>
                      <Icon className="w-4 h-4" />
                    </div>
                    <div>
                      <div className="text-sm font-medium text-gray-800">{skill.name}</div>
                      <div className="text-[10px] text-gray-500">{skill.desc}</div>
                    </div>
                  </div>
                  <Switch checked={skill.enabled} onCheckedChange={() => toggleSkill(skill.id)} />
                </div>
              );
            })}
            {enabledSkills.length === 0 && (
              <div className="p-6 border border-dashed border-gray-200 rounded-lg text-center text-xs text-gray-400">
                暂无启用中的技能，点击「添加技能」进行配置
              </div>
            )}
          </div>
        </div>
      </div>

      <AgentSkillsModal isOpen={skillsModalOpen} onClose={() => setSkillsModalOpen(false)} />
    </div>
  );
}
