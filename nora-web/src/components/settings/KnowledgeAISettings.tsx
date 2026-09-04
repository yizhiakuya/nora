'use client';

import { useState } from "react";
import { CheckCircle2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { Switch } from "@/components/ui/switch";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { useSkills } from "@/hooks/useSkills";
import { useTimedSequence } from "@/hooks/useTimedSequence";
import { usePreferences } from "@/hooks/usePreferences";
import { toast } from "sonner";

export function KnowledgeAISettings() {
  const [saved, setSaved] = useState(false);
  const { schedule, cancelAll } = useTimedSequence();
  const skills = useSkills((s) => s.skills);
  const toggleSkill = useSkills((s) => s.toggleSkill);

  const knowledgeAI = usePreferences((s) => s.knowledgeAI);
  const setKnowledgeAI = usePreferences((s) => s.setKnowledgeAI);

  const handleSave = () => {
    cancelAll();
    setSaved(true);
    toast.success("知识库与 AI 设置已保存");
    schedule(() => setSaved(false), 2000);
  };

  return (
    <div className="bg-card dark:bg-card rounded-xl border border-border shadow-sm overflow-hidden">
      <div className="p-6 space-y-6">
        <div className="space-y-1.5">
          <label className="text-sm font-bold text-foreground">嵌入模型</label>
          <p className="text-xs text-muted-foreground mb-2">文档向量化所用模型，切换后需重新索引全部文档。</p>
          <Select
            value={knowledgeAI.embedding}
            onValueChange={(v) => setKnowledgeAI({ embedding: v })}
          >
            <SelectTrigger className="w-full max-w-sm h-10 rounded-lg"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="text-embedding-3-small (1536维)">text-embedding-3-small (1536维)</SelectItem>
              <SelectItem value="text-embedding-3-large (3072维)">text-embedding-3-large (3072维)</SelectItem>
              <SelectItem value="本地 BGE-M3">本地 BGE-M3</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div className="space-y-1.5">
          <label className="text-sm font-bold text-foreground">分块策略</label>
          <p className="text-xs text-muted-foreground mb-2">文档切分的最大块大小，按语义边界切分，不超过该值。</p>
          <Select
            value={knowledgeAI.chunkSize}
            onValueChange={(v) => setKnowledgeAI({ chunkSize: v })}
          >
            <SelectTrigger className="w-full max-w-sm h-10 rounded-lg"><SelectValue /></SelectTrigger>
            <SelectContent>
              <SelectItem value="256 token">256 token（更精细）</SelectItem>
              <SelectItem value="512 token（推荐）">512 token（推荐）</SelectItem>
              <SelectItem value="1024 token">1024 token（更粗略）</SelectItem>
            </SelectContent>
          </Select>
        </div>

        <div className="w-full h-px bg-border"></div>

        <div className="flex items-center justify-between">
          <div>
            <div className="text-sm font-medium text-foreground">上传后自动加入知识库</div>
            <div className="text-xs text-muted-foreground mt-0.5">新文件完成上传后自动解析、清洗并索引入库</div>
          </div>
          <Switch
            checked={knowledgeAI.autoIndex}
            onCheckedChange={(v) => setKnowledgeAI({ autoIndex: v })}
          />
        </div>

        <div className="w-full h-px bg-border"></div>

        <div className="space-y-3">
          <div>
            <label className="text-sm font-bold text-foreground">AI 能力默认开关</label>
            <p className="text-xs text-muted-foreground mt-0.5">与「AI 能力」页共享同一状态，控制对话中 AI 可使用的工具。</p>
          </div>
          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
            {skills.map((skill) => (
              <div key={skill.id} className="flex items-center justify-between px-3 py-2 rounded-lg bg-muted/40 border border-border">
                <div className="min-w-0">
                  <div className="text-xs font-medium text-foreground truncate">{skill.name}</div>
                  <div className="text-[10px] text-muted-foreground truncate">{skill.desc}</div>
                </div>
                <Switch checked={skill.enabled} onCheckedChange={() => toggleSkill(skill.id)} />
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="bg-muted/30 p-4 border-t border-border flex justify-end gap-3">
        <Button size="sm" className={`transition-colors ${saved ? "bg-green-600 dark:bg-green-500 text-white" : "bg-primary text-primary-foreground"}`} onClick={handleSave}>
          {saved ? <><CheckCircle2 className="w-4 h-4 mr-1.5" /> 已保存</> : "保存更改"}
        </Button>
      </div>
    </div>
  );
}
